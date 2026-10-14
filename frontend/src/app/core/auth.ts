import { HttpClient, HttpHeaders, HttpInterceptorFn } from '@angular/common/http';
import { InjectionToken, Injectable, computed, inject, signal } from '@angular/core';
import { catchError, firstValueFrom, throwError } from 'rxjs';
import { environment } from '../../environments/environment';
import { OidcSettings } from '../../environments/environment.model';

/*
 * Sign-in through WSO2 (gateway mode only): OAuth 2.0 authorization code flow with PKCE.
 *
 *  1. login(): create a random "code verifier", send only its SHA-256 hash (the "challenge") while
 *     redirecting the browser to WSO2's login page.
 *  2. The user logs in on WSO2; WSO2 redirects back to /auth/callback?code=...&state=...
 *  3. handleCallback(): exchange the code + the original verifier for tokens. WSO2 checks that the
 *     verifier hashes to the challenge, so a code intercepted on the way back is useless on its own.
 *     That is what lets a browser app (which cannot keep a client secret) be a "public client".
 */

/** Navigates the whole page; a token so tests can replace it. */
export const PAGE_REDIRECT = new InjectionToken<(url: string) => void>('PAGE_REDIRECT', {
  providedIn: 'root',
  factory: () => (url: string) => window.location.assign(url),
});

/** OIDC settings of the active environment (null in demo mode); a token so tests can supply them. */
export const OIDC_SETTINGS = new InjectionToken<OidcSettings | null>('OIDC_SETTINGS', {
  providedIn: 'root',
  factory: () => (environment.auth.mode === 'oidc' ? environment.auth : null),
});

interface StoredSession {
  accessToken: string;
  idToken: string | null;
  scopes: string[];
  expiresAt: number; // epoch ms
}

interface PendingLogin {
  verifier: string;
  state: string;
  returnUrl: string;
}

interface TokenResponse {
  access_token: string;
  id_token?: string;
  scope?: string;
  expires_in: number;
}

const SESSION_KEY = 'itaccess.session';
const PENDING_KEY = 'itaccess.pendingLogin';
export const REVIEW_SCOPE = 'access_request:review';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly redirect = inject(PAGE_REDIRECT);
  private readonly settings = inject(OIDC_SETTINGS);

  /*
   * Kept in sessionStorage so a page reload does not log you out (cleared when the tab closes).
   * Trade-off for a learning project: script running on the page (XSS) could read it. Production
   * SPAs often use a backend-for-frontend so the browser never holds the token.
   */
  private readonly session = signal<StoredSession | null>(readJson<StoredSession>(SESSION_KEY));

  readonly isAuthenticated = computed(() => {
    const s = this.session();
    return s !== null && s.expiresAt > Date.now();
  });
  readonly scopes = computed(() => this.session()?.scopes ?? []);
  readonly isAdmin = computed(() => this.scopes().includes(REVIEW_SCOPE));

  accessToken(): string | null {
    return this.isAuthenticated() ? this.session()!.accessToken : null;
  }

  async login(returnUrl = '/'): Promise<void> {
    const settings = this.requireSettings();
    const clientId = await this.clientId();
    const verifier = randomUrlSafe(32);
    const state = randomUrlSafe(16);
    writeJson<PendingLogin>(PENDING_KEY, { verifier, state, returnUrl });

    const params = new URLSearchParams({
      response_type: 'code',
      client_id: clientId,
      redirect_uri: settings.redirectUri,
      scope: settings.scope,
      state,
      code_challenge: await pkceChallenge(verifier),
      code_challenge_method: 'S256',
    });
    this.redirect(`${settings.authorizeUrl}?${params}`);
  }

  /** Completes the login on /auth/callback. Returns where to go next. */
  async handleCallback(query: URLSearchParams): Promise<string> {
    const settings = this.requireSettings();
    const error = query.get('error');
    if (error) {
      throw new Error(query.get('error_description') ?? error);
    }
    const pending = readJson<PendingLogin>(PENDING_KEY);
    removeKey(PENDING_KEY);
    const code = query.get('code');
    // "state" ties this callback to the login we started; anything else could be a forged redirect.
    if (!pending || !code || query.get('state') !== pending.state) {
      throw new Error('This sign-in link is invalid or expired. Please sign in again.');
    }

    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      redirect_uri: settings.redirectUri,
      client_id: await this.clientId(),
      code_verifier: pending.verifier,
    });
    const token = await firstValueFrom(
      this.http.post<TokenResponse>(settings.tokenUrl, body.toString(), {
        headers: new HttpHeaders({ 'Content-Type': 'application/x-www-form-urlencoded' }),
      }),
    );
    const session: StoredSession = {
      accessToken: token.access_token,
      idToken: token.id_token ?? null,
      scopes: (token.scope ?? '').split(' ').filter(Boolean),
      expiresAt: Date.now() + token.expires_in * 1000,
    };
    writeJson(SESSION_KEY, session);
    this.session.set(session);
    return pending.returnUrl || '/';
  }

  /** Forgets the token here and ends the WSO2 login session (otherwise the next login is silent). */
  logout(): void {
    const settings = this.requireSettings();
    const idToken = this.session()?.idToken;
    removeKey(SESSION_KEY);
    this.session.set(null);
    const params = new URLSearchParams({ post_logout_redirect_uri: settings.postLogoutRedirectUri });
    if (idToken) params.set('id_token_hint', idToken);
    this.redirect(`${settings.logoutUrl}?${params}`);
  }

  private requireSettings(): OidcSettings {
    if (!this.settings) throw new Error('Sign-in is only available in gateway mode');
    return this.settings;
  }

  private clientIdPromise: Promise<string> | null = null;

  private clientId(): Promise<string> {
    this.clientIdPromise ??= firstValueFrom(
      this.http.get<{ clientId: string }>(this.requireSettings().runtimeConfigUrl),
    ).then((c) => c.clientId);
    return this.clientIdPromise;
  }
}

/** Gateway mode: adds the access token to API calls; a 401 from the gateway means "sign in again". */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(environment.apiBaseUrl)) {
    return next(req);
  }
  const auth = inject(AuthService);
  const token = auth.accessToken();
  const authorized = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
  return next(authorized).pipe(
    catchError((err) => {
      if (err?.status === 401) {
        void auth.login(window.location.pathname + window.location.search);
      }
      return throwError(() => err);
    }),
  );
};

/** RFC 7636: BASE64URL(SHA-256(verifier)). */
export async function pkceChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}

function randomUrlSafe(byteCount: number): string {
  return base64Url(crypto.getRandomValues(new Uint8Array(byteCount)));
}

function base64Url(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function readJson<T>(key: string): T | null {
  try {
    const raw = sessionStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

function writeJson<T>(key: string, value: T): void {
  try {
    sessionStorage.setItem(key, JSON.stringify(value));
  } catch {
    // storage unavailable: the session just won't survive a reload
  }
}

function removeKey(key: string): void {
  try {
    sessionStorage.removeItem(key);
  } catch {
    // ignore
  }
}
