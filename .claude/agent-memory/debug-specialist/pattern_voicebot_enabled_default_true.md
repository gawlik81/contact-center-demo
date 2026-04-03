---
name: voicebot.enabled domyślnie true — serwis nie uruchomiony
description: application.yml ma voicebot.enabled=true jako domyślne, ale kontener voicebot wymaga docker compose --profile ai; Connection refused → fallback
type: project
---

W `application.yml` linia 341: `voicebot.enabled: ${VOICEBOT_ENABLED:true}` — domyślna wartość to `true`.
Serwis Python w `docker-compose.yml` ma `profiles: [ai]` i nie startuje przy zwykłym `docker compose up -d`.

Efekt: `VoicebotClient` bean jest tworzony (bo `enabled=true`), ale `POST http://localhost:8001/voicebot/turn`
dostaje `Connection refused` → `RestClientException` → `Optional.empty()` → `goToFallbackOrDefaultQueue` → fallback.

**Why:** Domyślna wartość `true` zakłada że ciężka zależność (Whisper ~140MB) zawsze jest dostępna, co jest niezgodne z profilem Docker `ai`.

**How to apply:** Gdy węzeł VOICEBOT wychodzi fallback a logi pokazują `Connection refused` na porcie 8001:
- zweryfikuj czy kontener `cc-voicebot` działa: `docker compose ps`
- albo zmień domyślne na `voicebot.enabled: ${VOICEBOT_ENABLED:false}` w `application.yml`
- żeby włączyć: `docker compose --profile ai up -d` + `VOICEBOT_ENABLED=true` przy starcie backendu
