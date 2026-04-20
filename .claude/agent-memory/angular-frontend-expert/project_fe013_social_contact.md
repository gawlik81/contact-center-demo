---
name: Social contact component (FE-013)
description: SocialContactComponent – czat social media (FB/IG/WA) w Agent Desktop, WS events, infinite scroll
type: project
---

SocialContactComponent zaimplementowany w `frontend/src/app/features/agent/pages/agent-desktop/social-contact/`.

**Pliki:**
- `social-contact.component.ts` – selector `cc-social-contact`, inputs: `contactId` (required), `contactStatus`
- `social-contact.component.html` – skeleton/error/chat view, load-more button na górze, bąbelki INBOUND lewo / OUTBOUND prawo
- `social-contact.component.scss` – design tokens zgodne z email-contact, platforma badge (kolorowe kółka), animacja shimmer skeleton
- `social-contact.service.ts` – GET `/api/contacts/{id}/social/messages?page&size`, POST `/api/contacts/{id}/social/message`
- `social-message.model.ts` – `SocialMessage`, `SocialMessagesPage`, `SendSocialMessageRequest`, `SocialMessageReceivedPayload`

**Rozszerzenia istniejących plików:**
- `ws-event.model.ts` – dodano `'SOCIAL_MESSAGE_RECEIVED'` do `WsEventType`
- `contact-tab.model.ts` – dodano `'SOCIAL'` do `ContactType`
- `agent-desktop.component.ts` – import `SocialContactComponent`, case 'SOCIAL' w getTabTypeIcon/getTabTypeLabel/getQueueTypeIcon
- `agent-desktop.component.html` – `[class.contact-tab--social]` + `@case ('SOCIAL')` renderujące `cc-social-contact`
- `agent-desktop.component.scss` – `.contact-tab--social` badge: background #fff7ed, color #c2410c

**Wzorzec WS:** subskrypcja `ws.events$` filtrowana po `SOCIAL_MESSAGE_RECEIVED`, dodaje message do listy bez przeładowania.
**Tryb read-only:** `contactStatus === 'COMPLETED'` blokuje textarea i przycisk Wyślij, pokazuje żółty banner.
**Infinite scroll:** load-more button na górze listy, ładuje starsze wiadomości (page+1), prepend do tablicy.

**Why:** nowy kanał komunikacji w Contact Center – social media (Facebook Messenger, Instagram, WhatsApp).
**How to apply:** przy kolejnych zadaniach dot. social media referencja do tych plików; ContactType teraz ma 4 wartości.
