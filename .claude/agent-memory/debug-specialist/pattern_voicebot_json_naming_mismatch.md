---
name: VoicebotClient TurnRequest — niezgodność camelCase vs snake_case
description: Java record TurnRequest serializuje camelCase; Python/Pydantic oczekuje snake_case → HTTP 422 → graceful degradation → fallback
type: project
---

VoicebotClient.TurnRequest i TurnResponse używają Java record bez @JsonProperty — Jackson serializuje pola jako camelCase (sessionId, audioBase64…). FastAPI/Pydantic w voicebot/app/models.py oczekuje snake_case (session_id, audio_base64…). Efekt: HTTP 422 Unprocessable Entity, RestClientException, Optional.empty() → IVR wychodzi na fallback.

**Why:** Brak adnotacji @JsonProperty lub strategii PropertyNamingStrategies.SNAKE_CASE na RestClient voicebota. Odkryte 2026-04-03, incydent 07:30, Call SID CA70833a33e4b10ddc85f717034000fcd7.

**How to apply:** Dodaj @JsonProperty("snake_case") na wszystkich polach TurnRequest i TurnResponse w VoicebotClient.java. Alternatywnie: dedykowany ObjectMapper ze SNAKE_CASE dla tego RestClient. Szczegóły w BUGFIX-VOICEBOT-FALLBACK.md.
