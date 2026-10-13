/**
 * Default (direct-to-backend) configuration.
 * '/api' is relative: in `ng serve` the dev proxy (proxy.conf.json) forwards it to Spring Boot on :8080,
 * so the browser sees a single origin and no CORS setup is needed.
 * Phase 4 adds a "gateway" configuration that points at the WSO2 API Manager gateway instead.
 */
export const environment = {
  apiBaseUrl: '/api',
};
