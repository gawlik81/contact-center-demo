# Voicebot Service (BE-014)

Mikrousługa Python realizująca ASR + NLU dla voicebota Contact Center.

## Wymagania

- Python 3.12+
- ffmpeg (wymagany przez Whisper)
- Redis 7+
- RabbitMQ 3.13+

## Uruchomienie lokalne

```bash
# Instalacja zależności
pip install -r requirements.txt

# Uruchomienie serwisu (port 8001)
uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload
```

Swagger UI dostępny pod: http://localhost:8001/docs

## Uruchomienie przez Docker Compose

```bash
# Uruchom cały stack z serwisem voicebot (profil ai)
docker compose --profile ai up -d

# Tylko voicebot + zależności (Redis, RabbitMQ muszą być uruchomione)
docker compose --profile ai up voicebot
```

## Testy

```bash
# Instalacja zależności testowych
pip install pytest pytest-asyncio

# Uruchomienie testów
pytest tests/ -v
```

## API

### POST /voicebot/turn

Przetwarza jedną turę konwersacji (audio → intent).

**Request:**
```json
{
  "session_id": "call-abc-123",
  "tenant_id": "uuid",
  "contact_id": "uuid",
  "audio_base64": "<base64 WAV/PCM>",
  "audio_format": "wav"
}
```

**Response:**
```json
{
  "session_id": "call-abc-123",
  "transcript": "mam reklamację",
  "intent": "complaint",
  "confidence": 0.85,
  "escalate": false,
  "escalation_reason": null,
  "full_transcript": ["mam reklamację"]
}
```

Gdy `confidence < 0.70`, serwis:
- ustawia `escalate: true`
- publikuje event na RabbitMQ (`cc.events`, routing key `voicebot.escalate`, priority=9)

### DELETE /voicebot/session/{session_id}

Usuwa sesję konwersacji z Redis (wywoływane po hangup).

### GET /health

Healthcheck endpoint.

## Konfiguracja (zmienne środowiskowe)

| Zmienna                  | Domyślna wartość                      | Opis                              |
|--------------------------|---------------------------------------|-----------------------------------|
| `REDIS_URL`              | `redis://localhost:6379`              | URL Redis                         |
| `RABBITMQ_URL`           | `amqp://guest:guest@localhost:5672/`  | URL RabbitMQ                      |
| `WHISPER_MODEL`          | `base`                                | Model Whisper (tiny/base/small)   |
| `CONFIDENCE_THRESHOLD`   | `0.70`                                | Próg eskalacji                    |
| `SESSION_TTL_SECONDS`    | `900`                                 | TTL sesji Redis (15 min)          |

## Architektura

```
POST /voicebot/turn
    ↓
asr.py   – Whisper (lokalne przetwarzanie, bez zewnętrznego API)
    ↓
nlu.py   – keyword-based intent detection (PL)
    ↓
session.py – Redis (voicebot:session:{session_id}, TTL 15min)
    ↓
rabbit.py  – RabbitMQ publish (jeśli escalate=true)
    ↓
TurnResponse
```

## Integracja z Spring Boot

Spring Boot wywołuje serwis przez `VoicebotClient` (RestClient, timeout 1s/3s).
Klient aktywowany przez `voicebot.enabled=true` w `application.yml`.

Węzeł IVR `VOICEBOT` w `IvrEngineService` obsługuje:
- `escalate=true` → przejście do węzła `escalate` lub QUEUE_TRANSFER z `queueId`
- `escalate=false` → przejście do węzła `next`
- błąd/timeout → przejście do węzła `fallback`
