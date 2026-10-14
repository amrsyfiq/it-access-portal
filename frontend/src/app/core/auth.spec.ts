import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { OidcSettings } from '../../environments/environment.model';
import { AuthService, OIDC_SETTINGS, PAGE_REDIRECT, authInterceptor, pkceChallenge } from './auth';

const SETTINGS: OidcSettings = {
  mode: 'oidc',
  authorizeUrl: 'https://wso2.test/oauth2/authorize',
  tokenUrl: '/oauth2/token',
  logoutUrl: 'https://wso2.test/oidc/logout',
  runtimeConfigUrl: '/gateway-config.json',
  redirectUri: 'http://localhost:4200/auth/callback',
  postLogoutRedirectUri: 'http://localhost:4200/',
  scope: 'openid access_request:read access_request:review',
};

describe('pkceChallenge', () => {
  it('matches the RFC 7636 (Appendix B) test vector', async () => {
    expect(await pkceChallenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).toBe(
      'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
    );
  });
});

describe('AuthService (authorization code + PKCE)', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  let redirects: string[];

  beforeEach(() => {
    sessionStorage.clear();
    redirects = [];
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: OIDC_SETTINGS, useValue: SETTINGS },
        { provide: PAGE_REDIRECT, useValue: (url: string) => redirects.push(url) },
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function startLogin(returnUrl: string): Promise<URL> {
    const done = auth.login(returnUrl);
    http.expectOne('/gateway-config.json').flush({ clientId: 'client-123' });
    await done;
    return new URL(redirects.at(-1)!);
  }

  it('redirects to WSO2 with an S256 challenge derived from a stored verifier', async () => {
    const url = await startLogin('/requests/7');

    expect(url.origin + url.pathname).toBe(SETTINGS.authorizeUrl);
    expect(url.searchParams.get('response_type')).toBe('code');
    expect(url.searchParams.get('client_id')).toBe('client-123');
    expect(url.searchParams.get('redirect_uri')).toBe(SETTINGS.redirectUri);
    expect(url.searchParams.get('code_challenge_method')).toBe('S256');
    const pending = JSON.parse(sessionStorage.getItem('itaccess.pendingLogin')!);
    expect(url.searchParams.get('state')).toBe(pending.state);
    expect(url.searchParams.get('code_challenge')).toBe(await pkceChallenge(pending.verifier));
    // the verifier itself never leaves the browser in this step
    expect(url.toString()).not.toContain(pending.verifier);
  });

  it('exchanges the code with the verifier and derives the admin role from the scopes', async () => {
    const url = await startLogin('/requests/7');
    const pending = JSON.parse(sessionStorage.getItem('itaccess.pendingLogin')!);

    const done = auth.handleCallback(new URLSearchParams({ code: 'the-code', state: url.searchParams.get('state')! }));
    await Promise.resolve();
    const req = http.expectOne('/oauth2/token');
    const form = new URLSearchParams(req.request.body as string);
    expect(req.request.method).toBe('POST');
    expect(form.get('grant_type')).toBe('authorization_code');
    expect(form.get('code')).toBe('the-code');
    expect(form.get('code_verifier')).toBe(pending.verifier);
    expect(form.get('client_id')).toBe('client-123');
    expect(form.has('client_secret')).toBe(false); // public client
    req.flush({ access_token: 'at-1', id_token: 'id-1', scope: 'openid access_request:read access_request:review', expires_in: 3600 });

    expect(await done).toBe('/requests/7');
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.isAdmin()).toBe(true);
    expect(auth.accessToken()).toBe('at-1');
  });

  it('rejects a callback whose state does not match (forged or stale redirect)', async () => {
    await startLogin('/');

    await expect(
      auth.handleCallback(new URLSearchParams({ code: 'the-code', state: 'not-the-state' })),
    ).rejects.toThrow(/invalid or expired/);
    http.expectNone('/oauth2/token');
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('reports an error returned by WSO2', async () => {
    await expect(
      auth.handleCallback(new URLSearchParams({ error: 'access_denied', error_description: 'User denied consent' })),
    ).rejects.toThrow('User denied consent');
  });

  it('employee scopes do not make an admin', async () => {
    const url = await startLogin('/');
    const done = auth.handleCallback(new URLSearchParams({ code: 'c', state: url.searchParams.get('state')! }));
    await Promise.resolve();
    http.expectOne('/oauth2/token').flush({ access_token: 'at', scope: 'access_request:read access_request:submit', expires_in: 3600 });
    await done;

    expect(auth.isAdmin()).toBe(false);
  });

  it('adds the bearer token to API calls only', async () => {
    sessionStorage.setItem('itaccess.session', JSON.stringify({
      accessToken: 'at-2', idToken: null, scopes: [], expiresAt: Date.now() + 60_000,
    }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: OIDC_SETTINGS, useValue: SETTINGS },
      ],
    });
    const client = TestBed.inject(HttpClient);
    const ctrl = TestBed.inject(HttpTestingController);

    void firstValueFrom(client.get('/api/requests'));
    void firstValueFrom(client.get('/gateway-config.json'));
    expect(ctrl.expectOne('/api/requests').request.headers.get('Authorization')).toBe('Bearer at-2');
    expect(ctrl.expectOne('/gateway-config.json').request.headers.has('Authorization')).toBe(false);
    http = ctrl;
  });

  it('logout clears the session and ends the WSO2 session', () => {
    sessionStorage.setItem('itaccess.session', JSON.stringify({
      accessToken: 'at', idToken: 'id-9', scopes: [], expiresAt: Date.now() + 60_000,
    }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: OIDC_SETTINGS, useValue: SETTINGS },
        { provide: PAGE_REDIRECT, useValue: (url: string) => redirects.push(url) },
      ],
    });
    const service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);

    service.logout();

    expect(service.isAuthenticated()).toBe(false);
    expect(sessionStorage.getItem('itaccess.session')).toBeNull();
    const url = new URL(redirects.at(-1)!);
    expect(url.origin + url.pathname).toBe(SETTINGS.logoutUrl);
    expect(url.searchParams.get('id_token_hint')).toBe('id-9');
    expect(url.searchParams.get('post_logout_redirect_uri')).toBe(SETTINGS.postLogoutRedirectUri);
  });
});
