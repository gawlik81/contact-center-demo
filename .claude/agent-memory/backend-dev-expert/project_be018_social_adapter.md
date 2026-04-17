---
name: BE-018 Social Media Adapter
description: Implementacja adaptera social media – webhook handler, SocialMessage encja, adapterRegisterStrategy, async RabbitMQ processing
type: project
---

BE-018 Social Media Adapter zaimplementowany 2026-04-17.

**Nowe komponenty:**
- `SocialMessage` – encja JPA, tabela `social_message` (V010, już istniała), platform=ENUM `social_platform`, direction=VARCHAR
- `SocialMessageRepository` – extends TenantAwareRepository, findByContactId, findByExternalMessageId
- `SocialMediaAdapter` – interfejs z getPlatform/sendMessage/getConversationHistory
- `FacebookAdapter`, `InstagramAdapter`, `WhatsAppAdapter` – stuby w `infrastructure.social`
- `SocialAdapterRegistry` – Map<SocialPlatform, SocialMediaAdapter> wstrzykiwana przez konstruktor (List<SocialMediaAdapter>)
- `SocialMessageService` – processIncomingMessage + sendMessage; TenantContext ustawiany ręcznie (webhook bez JWT)
- `SocialMessagePublisher` – rabbitTemplate.convertAndSend do QUEUE_SOCIAL_INCOMING (bez exchange/routingKey – bezpośrednio do kolejki)
- `SocialMessageConsumer` – @RabbitListener(queues = QUEUE_SOCIAL_INCOMING)
- `SocialWebhookController` – GET/POST /api/webhooks/facebook|instagram|whatsapp, publiczne (no JWT)
- `SocialContactController` – POST /api/contacts/{contactId}/social/message, AGENT+

**Konfiguracja:**
- `RabbitMQConfig.QUEUE_SOCIAL_INCOMING = "cc.queue.social-incoming"` + @Bean `socialIncomingQueue()` (bez bindingu – direct send)
- `SecurityConfig`: dodano `.requestMatchers("/api/webhooks/**").permitAll()`
- `TenantFilter.PUBLIC_PATH_PREFIXES`: dodano `"/api/webhooks/"`

**Rozszerzenia istniejących:**
- `SocialIntegrationRepository.findByPlatformAndPageId(platform, pageId)` – cross-tenant lookup (brak setTenantContextInDb celowo)
- `ContactRepository.findActiveSocialContact(tenantId, senderExternalId, channel)` – natywny SQL, status IN ('QUEUED','ACTIVE')

**Wzorzec webhook:**
1. POST webhook → parsuj JsonNode defensywnie → SocialMessagePublisher.publish(IncomingSocialMessage) → HTTP 200
2. SocialMessageConsumer → SocialMessageService.processIncomingMessage → TenantContext.restore → logika → TenantContext.clear() w finally

**Why:** Brak JWT w webhookach platform social; tenant identyfikowany przez (platform, pageId) – SocialIntegration.findByPlatformAndPageId bypasses RLS.

**How to apply:** Nowe kanały social: dodaj SocialPlatform ENUM, implementację SocialMediaAdapter z @Component, mapowanie w platformToChannel/channelToPlatform.
