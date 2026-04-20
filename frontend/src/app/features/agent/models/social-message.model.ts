export type SocialPlatform = 'FACEBOOK' | 'INSTAGRAM' | 'WHATSAPP';
export type SocialMessageDirection = 'INBOUND' | 'OUTBOUND';

export interface SocialMessage {
  messageId: string;
  platform: SocialPlatform;
  direction: SocialMessageDirection;
  content: string;
  attachments: string[];
  sentAt: string;
  senderExternalId: string;
}

export interface SocialMessagesPage {
  content: SocialMessage[];
  totalPages: number;
  totalElements: number;
}

export interface SendSocialMessageRequest {
  content: string;
  attachmentUrls: string[];
}

export interface SocialMessageReceivedPayload {
  contactId: string;
  message: SocialMessage;
}
