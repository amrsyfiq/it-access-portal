import { AppEnvironment } from './environment.model';

/**
 * Default (direct-to-backend) configuration: `npm start`.
 * '/api' is relative: the dev proxy (proxy.conf.json) forwards it to Spring Boot on :8080, so the
 * browser sees a single origin and no CORS setup is needed. Identity = the demo-user switcher.
 *
 * `npm run start:gateway` swaps in environment.gateway.ts (see angular.json "gateway" configuration).
 */
export const environment: AppEnvironment = {
  apiBaseUrl: '/api',
  auth: { mode: 'demo' },
};
