import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth';

/** WSO2 redirects here after login (?code=...&state=...). Finishes the sign-in, then moves on. */
@Component({
  selector: 'app-auth-callback',
  template: `
    @if (error(); as message) {
      <div class="alert alert-error" role="alert">{{ message }}</div>
      <button class="btn btn-primary" type="button" (click)="retry()">Sign in again</button>
    } @else {
      <div class="state" aria-live="polite"><span class="spinner"></span> Signing you in…</div>
    }
  `,
})
export class AuthCallback {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly error = signal<string | null>(null);

  constructor() {
    this.auth
      .handleCallback(new URLSearchParams(window.location.search))
      .then((returnUrl) => this.router.navigateByUrl(returnUrl, { replaceUrl: true }))
      .catch((err: unknown) => {
        const message = err instanceof Error ? err.message : 'Sign-in failed.';
        this.error.set(message.startsWith('Http failure') ? 'Could not reach the sign-in server.' : message);
      });
  }

  protected retry(): void {
    void this.auth.login('/');
  }
}
