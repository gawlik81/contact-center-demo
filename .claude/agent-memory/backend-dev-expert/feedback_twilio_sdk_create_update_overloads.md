---
name: Twilio SDK per-client create/update overloads w testach
description: CallCreator.create(TwilioRestClient) i CallUpdater.update(TwilioRestClient) to jedyne przeciążenia w SDK 10.x – bezargumentowe nie istnieją; mocki muszą to uwzględniać
type: feedback
---

W Twilio SDK 10.x (np. 10.9.1) metody `create()` i `update()` przyjmują wyłącznie `TwilioRestClient` jako argument:
- `CallCreator.create(TwilioRestClient client)` — brak `create()` bez argumentów
- `CallUpdater.update(TwilioRestClient client)` — brak `update()` bez argumentów

**Why:** BE-058 refaktoryzował `TwilioTelephonyAdapter` z globalnego `Twilio.init()` na per-tenant `TwilioRestClient`. Po tej zmianie wszelkie stuby Mockito typu `when(mockCreator.create()).thenReturn(...)` przestają działać — trzeba używać `when(mockCreator.create(any(TwilioRestClient.class))).thenReturn(...)`.

**How to apply:** Zawsze gdy mockujesz wywołania Twilio SDK w testach adaptera, używaj `any(com.twilio.http.TwilioRestClient.class)` jako parametru dla `create(...)` i `update(...)`.
