---
name: TwilioTelephonyAdapter — niezgodność klucza sesji callSid vs contactId
description: sessions indeksowane po callSid (CA...), ale frontend wysyła contactId (UUID); findCallSidByContactId zwraca empty gdy sip_call_id null w DB
type: feedback
---

TwilioTelephonyAdapter (Redis) używa Twilio callSid (format CA...) jako klucza sesji: `call-session:{callSid}`.
Frontend odbiera contactId przez WebSocket i używa go jako {callId}: POST /api/telephony/calls/{contactId}/answer|hangup.

AgentCallController.resolveCallSid() tłumaczy UUID contactId → callSid przez ContactRepository.findCallSidByContactId()
(channel_metadata->>'sip_call_id'). requireSession() ma analogiczny fallback.

**Przyczyna błędu "Sesja nie istnieje"**: findCallSidByContactId zwraca Optional.empty() gdy sip_call_id jest null w DB.
To zdarza się gdy:
- Kontakt OUTBOUND tworzony przed Twilio API (backfill sip_call_id przez handleWebhookStatusUpdate jest pending)
- Błąd zapisu DB w handleVoiceWebhook (sesja Redis istnieje, ale DB niespójna)
- Race condition: agent klika "Odbierz" zanim StatusCallback dotrze z Twilio

**Implementowane rozwiązanie (2026-04-22)**: Indeks odwrotny Redis `contact-session-index:{contactId}` → callSid (String).
- saveSession() tworzy indeks atomowo (setIfAbsent, StringRedisTemplate, TTL 24h) gdy contactId != null
- requireSession() sprawdza indeks PRZED zapytaniem do DB (Fallback 1a → 1b → restore from DB)
- deleteSession() usuwa indeks razem z sesją
- StringRedisTemplate (nie GenericJackson2JsonRedisSerializer) — wartość to czysty String bez cudzysłowów JSON

**Why:** DB lookup (sip_call_id) jest podatny na race condition i błędy zapisu. Indeks Redis jest pisany razem z sesją,
więc jest zawsze spójny z życiem sesji. Obsługuje też OUTBOUND backfill race.

**How to apply:** Gdy "Sesja połączenia nie istnieje: {UUID-format}" → sprawdź Redis pod kluczem
contact-session-index:{contactId}. Jeśli brak — sesja wygasła lub nigdy nie powstała (sprawdź logi handleVoiceWebhook).
