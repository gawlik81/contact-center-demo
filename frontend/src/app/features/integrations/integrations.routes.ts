import { Routes } from '@angular/router';

export const INTEGRATIONS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./pages/social-integrations/social-integrations.component').then(
        (m) => m.SocialIntegrationsComponent,
      ),
    data: { breadcrumb: 'nav.settingsSocialMedia' },
  },
  {
    path: 'oauth/callback/:platform',
    loadComponent: () =>
      import('./pages/oauth-callback/oauth-callback.component').then(
        (m) => m.OauthCallbackComponent,
      ),
    data: { breadcrumb: 'nav.oauthCallback' },
  },
];
