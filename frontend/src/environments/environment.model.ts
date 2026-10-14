// Shape of the environment files. Kept separate because the gateway build *replaces*
// environment.ts with environment.gateway.ts (angular.json fileReplacements): types defined in
// environment.ts would disappear in that build.

export interface AppEnvironment {
  apiBaseUrl: string;
  auth: { mode: 'demo' } | OidcSettings;
}

export interface OidcSettings {
  mode: 'oidc';
  /** WSO2 login page (a full-page redirect, so it is not proxied). */
  authorizeUrl: string;
  /** Token endpoint, proxied by the dev server so the browser makes a same-origin request. */
  tokenUrl: string;
  logoutUrl: string;
  /** Holds the OAuth client id, written by infra/wso2/setup.mjs (it differs per WSO2 install). */
  runtimeConfigUrl: string;
  redirectUri: string;
  postLogoutRedirectUri: string;
  scope: string;
}
