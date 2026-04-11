---
name: Voicebot Service BE-014
description: Mikrousługa Python (FastAPI) voicebot ASR+NLU z integracją Spring Boot IVR engine
type: project
---

Voicebot service (BE-014) zaimplementowany jako osobny mikroserwis Python w `voicebot/`.

**Why:** ADR-06 – Python dla AI/ML komponentów; Whisper wymaga Pythona.

**How to apply:** Przy rozszerzaniu voicebota pamiętaj:
- IvrNodeType.VOICEBOT dodany do enuma – opcje węzła: `next`, `escalate`, `fallback`
- `VoicebotClient` (@ConditionalOnProperty voicebot.enabled=true) – wstrzyknięty jako `@Autowired(required=false) VoicebotClient voicebotClient` w IvrEngineService (package-private dla testów)
- Eskalacja: confidence < 0.70 → RabbitMQ `cc.events` / routing key `voicebot.escalate` / priority=9
- Redis klucz: `voicebot:session:{session_id}`, TTL 15 min
- Docker Compose profile `ai` – nie blokuje normalnego `docker compose up -d`
- Uruchomienie: `docker compose --profile ai up -d`
- Testy Python: `pytest tests/ -v` (wymaga pytest-asyncio)
