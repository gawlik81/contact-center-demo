---
name: EmailContactCreator — duplikaty OUTBOUND (brak idempotentności createOutboundContact)
description: createOutboundContact() nie sprawdzał czy OUTBOUND dla danego inboundContactId już istnieje; każde email.sent tworzyło nowy rekord
type: project
---

`EmailContactCreator.handleEmailSent()` → `createOutboundContact()` tworzyło nowy kontakt OUTBOUND dla każdego eventu `email.sent`. Brak guard przed duplikatami powodował 3 rekordy gdy UI wysłał 3 requesty (brak debounce lub RabbitMQ retry).

**Poprawka (2026-04-25):**
W `createOutboundContact()` przed insertем wywołaj `contactRepository.findByChannelMetadataValue("inboundContactId", inboundContactId.toString(), tenantId)` i jeśli lista niepusta — zaloguj WARN i return.

**Why:** Metoda `findByChannelMetadataValue` już istniała w repo (używana przez `getRelatedContacts`). Duplikat można wykryć przez JSONB query `channel_metadata->>'inboundContactId'`.

**How to apply:** Każda metoda tworząca rekord w odpowiedzi na event RabbitMQ powinna być idempotentna — sprawdź czy rekord z danymi identyfikatorami już istnieje przed insertем.
