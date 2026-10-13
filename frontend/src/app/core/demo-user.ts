import { HttpInterceptorFn } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { environment } from '../../environments/environment';

/*
 * DEVELOPMENT ONLY - NOT SECURITY.
 * Until WSO2 is integrated (Phase 4), the "current user" is picked from a dropdown and sent to the
 * backend as X-Demo-User / X-Demo-Role headers. Anyone can change them.
 */

export type Role = 'EMPLOYEE' | 'ADMIN';

export interface DemoUser {
  username: string;
  displayName: string;
  role: Role;
}

export const DEMO_USERS: readonly DemoUser[] = [
  { username: 'ted.l', displayName: 'Ted L', role: 'EMPLOYEE' },
  { username: 'alice.admin', displayName: 'Alice Admin', role: 'ADMIN' },
];

const STORAGE_KEY = 'itaccess.demoUser';

@Injectable({ providedIn: 'root' })
export class DemoUserService {
  readonly current = signal<DemoUser>(this.restore());
  readonly isAdmin = computed(() => this.current().role === 'ADMIN');

  select(username: string): void {
    const user = DEMO_USERS.find((u) => u.username === username);
    if (!user) return;
    this.current.set(user);
    try {
      localStorage.setItem(STORAGE_KEY, username);
    } catch {
      // storage unavailable (private mode etc.): the choice just isn't remembered
    }
  }

  private restore(): DemoUser {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      return DEMO_USERS.find((u) => u.username === saved) ?? DEMO_USERS[0];
    } catch {
      return DEMO_USERS[0];
    }
  }
}

/** Adds the demo identity headers to calls to our API (and nowhere else). */
export const demoUserInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(environment.apiBaseUrl)) {
    return next(req);
  }
  const user = inject(DemoUserService).current();
  return next(req.clone({ setHeaders: { 'X-Demo-User': user.username, 'X-Demo-Role': user.role } }));
};
