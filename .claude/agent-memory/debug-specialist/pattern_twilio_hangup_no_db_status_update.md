---
name: TwilioTelephonyAdapter.hangupCall — brak aktualizacji contact.status w DB
description: TwilioTelephonyAdapter nie wywołuje updateContactStatusOnTelephonyEvent po hangup, więc contact.status pozostaje QUEUED w DB; MockTelephonyAdapter robi to poprawnie
type: feedback
---

`TwilioTelephonyAdapter.hangupCall()` aktualizuje tylko lokalną mapę sesji i publikuje `CALL_HANGUP` na RabbitMQ, ale nie aktualizuje statusu rekordu `contact` w bazie danych.

W rezultacie po rozłączeniu połączenia przez Twilio, rekord `contact` pozostaje w statusie `QUEUED` (pierwotny status przy tworzeniu przez webhook `ringing`). Gdy agent próbuje zapisać dyspozycję przez `PATCH /api/contacts/{contactId}/disposition`, `ContactService.setDisposition()` odczytuje `QUEUED` z DB i rzuca `InvalidOperationException` (HTTP 409).

`MockTelephonyAdapter.hangupCall()` robi to poprawnie (linia ~151): wywołuje `contactRepository.updateContactStatusOnTelephonyEvent(contactId, tenantId, "COMPLETED", endedAt)`.

**Why:** Asymetria między MockTelephonyAdapter a TwilioTelephonyAdapter — Mock był rozwijany wcześniej i testowany ręcznie; Twilio adapter miał osobny ścieżkę dla webhooków, ale nie zamknął cyklu życia contact w DB przy lokalnym hangup.

**How to apply:** Przy każdej nowej metodzie w TwilioTelephonyAdapter modyfikującej stan sesji na ENDED (hangupCall, updateWebhookStatus dla completed/canceled/failed) — sprawdź czy `contactRepository.updateContactStatusOnTelephonyEvent()` jest wywołany. Użyj MockTelephonyAdapter jako wzorca implementacyjnego dla side effectów domenowych.
