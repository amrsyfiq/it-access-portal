import { Routes } from '@angular/router';
import { signedInGuard } from './core/session';

// Each page is lazy-loaded: its code is downloaded the first time the route is visited.
export const routes: Routes = [
  {
    // WSO2 sends the browser back here after login; must work before the user is signed in.
    path: 'auth/callback',
    title: 'Signing in',
    loadComponent: () => import('./features/auth/auth-callback').then((m) => m.AuthCallback),
  },
  {
    // Gateway mode: everything below requires a WSO2 sign-in (a no-op in demo mode).
    path: '',
    canActivateChild: [signedInGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'requests' },
      {
        path: 'catalog',
        title: 'Request access',
        loadComponent: () =>
          import('./features/requests/system-catalog-page').then((m) => m.SystemCatalogPage),
      },
      {
        path: 'requests',
        title: 'Access requests',
        loadComponent: () => import('./features/requests/request-list').then((m) => m.RequestList),
      },
      {
        path: 'requests/new',
        title: 'New access request',
        loadComponent: () => import('./features/requests/request-form').then((m) => m.RequestForm),
      },
      {
        path: 'requests/:id',
        title: 'Access request',
        loadComponent: () => import('./features/requests/request-detail').then((m) => m.RequestDetail),
      },
    ],
  },
  { path: '**', redirectTo: 'requests' },
];
