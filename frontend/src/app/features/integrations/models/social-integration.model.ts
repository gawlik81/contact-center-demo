export type SocialPlatform = 'FACEBOOK' | 'INSTAGRAM' | 'WHATSAPP';

export type WebhookStatus = 'ACTIVE' | 'INACTIVE' | 'ERROR' | 'EXPIRED_TOKEN';

export interface SocialIntegration {
  integrationId: string;
  platform: SocialPlatform;
  pageId: string;
  displayName: string;
  webhookStatus: WebhookStatus;
  tokenExpiresAt: string | null;
  webhookUrl: string;
  createdAt: string;
  updatedAt: string;
}

export interface OAuthInitiateResponse {
  platform: string;
  authorizationUrl: string;
  state: string;
}
