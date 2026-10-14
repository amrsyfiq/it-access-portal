import { AppEnvironment } from './environment.model';

/**
 * Through the WSO2 API Manager gateway: `npm run start:gateway`.
 *
 * Browser -> ng serve (localhost:4200) --proxy--> WSO2 gateway (localhost:8243) -> Spring Boot (:8080)
 *
 * The dev server proxies '/gateway' and '/oauth2/token' (proxy.gateway.conf.json), so XHR calls stay
 * same-origin: no CORS setup on WSO2's :9443 endpoints and no browser rejection of WSO2's
 * self-signed certificate for background requests. Only the login/logout pages are opened on
 * https://localhost:9443 directly (accept the certificate warning once).
 */
export const environment: AppEnvironment = {
  apiBaseUrl: '/gateway/accessportal/1.0.0',
  auth: {
    mode: 'oidc',
    authorizeUrl: 'https://localhost:9443/oauth2/authorize',
    tokenUrl: '/oauth2/token',
    logoutUrl: 'https://localhost:9443/oidc/logout',
    runtimeConfigUrl: '/gateway-config.json',
    redirectUri: 'http://localhost:4200/auth/callback',
    postLogoutRedirectUri: 'http://localhost:4200/',
    // WSO2 only grants the scopes the user's roles allow (e.g. no "review" for employees).
    scope: 'openid access_request:read access_request:submit access_request:review',
  },
};
