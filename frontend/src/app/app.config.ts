import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { environment } from '../environments/environment';
import { routes } from './app.routes';
import { authInterceptor } from './core/auth';
import { demoUserInterceptor } from './core/demo-user';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // Direct mode sends the demo-user headers; gateway mode sends the WSO2 access token.
    provideHttpClient(
      withInterceptors([environment.auth.mode === 'oidc' ? authInterceptor : demoUserInterceptor]),
    ),
  ],
};
