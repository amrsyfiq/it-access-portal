import { Routes } from '@angular/router';

// Each page is lazy-loaded: its code is downloaded the first time the route is visited.
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'requests' },
  {
    path: 'catalog',
    title: 'Request access',
    loadComponent: () => import('./features/requests/system-catalog-page').then((m) => m.SystemCatalogPage),
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
  { path: '**', redirectTo: 'requests' },
];
