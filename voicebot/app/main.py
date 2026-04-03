import logging
from contextlib import asynccontextmanager

import aio_pika
import redis.asyncio as aioredis
from fastapi import FastAPI, HTTPException

from app.asr import transcribe
from app.config import settings
from app.models import TurnRequest, TurnResponse, HealthResponse
from app.nlu import detect_intent
from app.rabbit import publish_escalation
from app.session import delete_session as redis_delete_session, get_session, update_session

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Połączenia – tworzone przy starcie, zamykane przy shutdown (lifespan)
# ---------------------------------------------------------------------------
_redis_client: aioredis.Redis | None = None
_rabbit_connection: aio_pika.abc.AbstractRobustConnection | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _redis_client, _rabbit_connection

    logger.info("[Voicebot] Inicjalizacja połączeń – Redis i RabbitMQ")

    # Redis
    _redis_client = aioredis.from_url(
        settings.redis_url,
        encoding="utf-8",
        decode_responses=True,
        socket_connect_timeout=5,
    )
    try:
        await _redis_client.ping()
        logger.info("[Voicebot] Redis połączony: %s", settings.redis_url)
    except Exception as exc:
        logger.error("[Voicebot] Błąd połączenia z Redis: %s", exc)

    # RabbitMQ – robust connection (automatyczne reconnect)
    try:
        _rabbit_connection = await aio_pika.connect_robust(settings.rabbitmq_url)
        logger.info("[Voicebot] RabbitMQ połączony: %s", settings.rabbitmq_url)
    except Exception as exc:
        logger.error("[Voicebot] Błąd połączenia z RabbitMQ: %s", exc)
        _rabbit_connection = None

    yield

    # Shutdown – zamknij połączenia
    logger.info("[Voicebot] Zamykanie połączeń")
    if _redis_client:
        await _redis_client.aclose()
    if _rabbit_connection and not _rabbit_connection.is_closed:
        await _rabbit_connection.close()


app = FastAPI(
    title="Voicebot Service",
    description="ASR + NLU microservice for Contact Center (BE-014)",
    version="1.0.0",
    lifespan=lifespan,
)


# ---------------------------------------------------------------------------
# Helpery
# ---------------------------------------------------------------------------

def _get_redis() -> aioredis.Redis:
    if _redis_client is None:
        raise HTTPException(status_code=503, detail="Redis not available")
    return _redis_client


def _get_rabbit():
    if _rabbit_connection is None or _rabbit_connection.is_closed:
        raise HTTPException(status_code=503, detail="RabbitMQ not available")
    return _rabbit_connection


# ---------------------------------------------------------------------------
# Endpointy
# ---------------------------------------------------------------------------

@app.post("/voicebot/turn", response_model=TurnResponse)
async def process_turn(request: TurnRequest) -> TurnResponse:
    """
    Przetwarza jedną turę konwersacji voicebota:
    1. ASR – transkrypcja audio przez Whisper
    2. NLU – wykrycie intencji na podstawie słów kluczowych
    3. Decyzja o eskalacji (confidence < próg)
    4. Zapis tury do Redis (history sesji)
    5. Opcjonalna publikacja eventu eskalacji na RabbitMQ
    """
    logger.info(
        "[Voicebot] Przetwarzanie tury: session_id=%s, tenant_id=%s, contact_id=%s",
        request.session_id, request.tenant_id, request.contact_id,
    )

    redis = _get_redis()

    # 1. ASR
    asr_result = await transcribe(
        request.audio_base64,
        request.audio_format,
        settings.whisper_model,
    )
    transcript: str = asr_result["text"]
    asr_confidence: float = asr_result["confidence"]

    # 2. NLU
    nlu_result = detect_intent(transcript, asr_confidence)
    intent: str = nlu_result["intent"]
    confidence: float = nlu_result["confidence"]
    response_text: str = nlu_result.get("response_text", "")

    # 3. Decyzja o eskalacji
    escalate = confidence < settings.confidence_threshold
    escalation_reason: str | None = None
    if escalate:
        escalation_reason = "low_confidence"
        logger.info(
            "[Voicebot] Eskalacja: session_id=%s, confidence=%.3f < threshold=%.3f",
            request.session_id, confidence, settings.confidence_threshold,
        )

    # 3b. Decyzja o kontynuacji dialogu (multi-turn)
    # continue_conversation=True gdy:
    #   - voicebot nie eskaluje I rozpoznał intencję (prosi o doprecyzowanie)
    #   - voicebot nie eskaluje I nie rozpoznał (prosi o powtórzenie) – ale tylko gdy tura < max_turns
    _max_turns = settings.max_turns
    if not escalate and intent != "unknown":
        # Intencja rozpoznana – voicebot doprecyzowuje
        continue_conversation = True
    elif not escalate and intent == "unknown" and request.turn_number < _max_turns:
        # Brak rozpoznania – prośba o powtórzenie (mamy jeszcze tury)
        continue_conversation = True
    else:
        # Eskalacja lub przekroczono max_turns
        continue_conversation = False
        if not escalate and request.turn_number >= _max_turns:
            # Wyczerpano tury bez rozpoznania – eskaluj
            escalate = True
            escalation_reason = "max_turns_exceeded"
            logger.info(
                "[Voicebot] Eskalacja (max_turns): session_id=%s, turn_number=%d >= max_turns=%d",
                request.session_id, request.turn_number, _max_turns,
            )

    # 4. Aktualizacja sesji Redis
    turn_data = {
        "transcript": transcript,
        "intent": intent,
        "confidence": confidence,
        "turn_number": request.turn_number,
    }
    session = await update_session(
        redis,
        request.session_id,
        request.tenant_id,
        request.contact_id,
        turn_data,
        settings.session_ttl_seconds,
    )

    full_transcript = [t["transcript"] for t in session.get("turns", []) if t.get("transcript")]

    # 5. Publikacja eskalacji na RabbitMQ
    if escalate:
        try:
            rabbit = _get_rabbit()
            full_text = " ".join(full_transcript)
            await publish_escalation(
                rabbit,
                session_id=request.session_id,
                tenant_id=request.tenant_id,
                contact_id=request.contact_id,
                transcript=full_text,
                intent=intent,
                confidence=confidence,
                reason=escalation_reason,
            )
        except HTTPException:
            # RabbitMQ niedostępny – loguj, ale nie blokuj odpowiedzi
            logger.warning(
                "[Voicebot] RabbitMQ niedostępny – eskalacja nie opublikowana: session_id=%s",
                request.session_id,
            )
        except Exception as exc:
            logger.error(
                "[Voicebot] Błąd publikacji eskalacji: session_id=%s, error=%s",
                request.session_id, exc,
            )

    return TurnResponse(
        session_id=request.session_id,
        transcript=transcript,
        intent=intent,
        confidence=confidence,
        escalate=escalate,
        escalation_reason=escalation_reason,
        full_transcript=full_transcript,
        response_text=response_text,
        continue_conversation=continue_conversation,
    )


@app.delete("/voicebot/session/{session_id}", status_code=204)
async def delete_session_endpoint(session_id: str) -> None:
    """
    Usuwa sesję konwersacji po hangup.
    """
    redis = _get_redis()
    await redis_delete_session(redis, session_id)


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(status="ok")
