import json
import logging
from datetime import datetime, timezone
from typing import Any, Optional

import redis.asyncio as aioredis

logger = logging.getLogger(__name__)

# Klucz Redis: voicebot:session:{session_id}
_SESSION_KEY_PREFIX = "voicebot:session:"


def _make_key(session_id: str) -> str:
    return f"{_SESSION_KEY_PREFIX}{session_id}"


async def get_session(redis_client: aioredis.Redis, session_id: str) -> Optional[dict[str, Any]]:
    """
    Pobiera sesję z Redis.

    Returns:
        dict z historią sesji lub None jeśli sesja nie istnieje.
    """
    try:
        raw = await redis_client.get(_make_key(session_id))
        if raw is None:
            return None
        return json.loads(raw)
    except Exception as exc:
        logger.error("[Session] Błąd odczytu sesji: session_id=%s, error=%s", session_id, exc)
        return None


async def update_session(
    redis_client: aioredis.Redis,
    session_id: str,
    tenant_id: str,
    contact_id: str,
    turn_data: dict[str, Any],
    ttl_seconds: int,
) -> dict[str, Any]:
    """
    Dodaje turę do sesji i zapisuje ją z odnowionym TTL.

    turn_data powinien zawierać: transcript, intent, confidence.

    Returns:
        Zaktualizowany słownik sesji.
    """
    key = _make_key(session_id)
    try:
        existing_raw = await redis_client.get(key)
        if existing_raw:
            session = json.loads(existing_raw)
        else:
            session = {
                "session_id": session_id,
                "tenant_id": tenant_id,
                "contact_id": contact_id,
                "turns": [],
                "created_at": datetime.now(timezone.utc).isoformat(),
            }

        session["turns"].append(turn_data)

        await redis_client.setex(key, ttl_seconds, json.dumps(session))
        logger.debug(
            "[Session] Sesja zaktualizowana: session_id=%s, turns=%d",
            session_id, len(session["turns"]),
        )
        return session

    except Exception as exc:
        logger.error("[Session] Błąd zapisu sesji: session_id=%s, error=%s", session_id, exc)
        # Zwróć minimalną sesję, żeby nie blokować przepływu
        return {
            "session_id": session_id,
            "tenant_id": tenant_id,
            "contact_id": contact_id,
            "turns": [turn_data],
            "created_at": datetime.now(timezone.utc).isoformat(),
        }


async def delete_session(redis_client: aioredis.Redis, session_id: str) -> None:
    """
    Usuwa sesję z Redis (np. po hangup).
    """
    try:
        deleted = await redis_client.delete(_make_key(session_id))
        if deleted:
            logger.info("[Session] Sesja usunięta: session_id=%s", session_id)
        else:
            logger.debug("[Session] Sesja nie istniała (już usunięta?): session_id=%s", session_id)
    except Exception as exc:
        logger.error("[Session] Błąd usuwania sesji: session_id=%s, error=%s", session_id, exc)
