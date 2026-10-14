import { CanActivateFn } from '@angular/router';
import { Injectable, computed, inject } from '@angular/core';
import { AuthService, OIDC_SETTINGS } from './auth';
import { DemoUserService } from './demo-user';

/**
 * "Who is using the app", whatever the mode. Pages ask this service and do not care whether the
 * answer comes from the demo-user switcher (direct mode) or from a WSO2 sign-in (gateway mode).
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly demo = inject(DemoUserService);
  private readonly auth = inject(AuthService);

  readonly mode: 'demo' | 'oidc' = inject(OIDC_SETTINGS) ? 'oidc' : 'demo';

  readonly isAdmin = computed(() => (this.mode === 'oidc' ? this.auth.isAdmin() : this.demo.isAdmin()));

  readonly roleLabel = computed(() => (this.isAdmin() ? 'ADMIN' : 'EMPLOYEE'));

  /** Prefill for "requester name". WSO2 only tells us a user id, so in gateway mode the user types it. */
  readonly displayName = computed(() => (this.mode === 'oidc' ? '' : this.demo.current().displayName));
}

/** Gateway mode: every page except /auth/callback needs a signed-in user. */
export const signedInGuard: CanActivateFn = (_route, state) => {
  const session = inject(SessionService);
  const auth = inject(AuthService);
  if (session.mode === 'demo' || auth.isAuthenticated()) {
    return true;
  }
  void auth.login(state.url);
  return false;
};
