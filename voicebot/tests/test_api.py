"""
Testy integracyjne API voicebota.
Mock: Whisper (ASR), Redis, RabbitMQ – brak zewnętrznych zależności.
Uruchomienie: pytest tests/test_api.py -v
"""
import base64
import json
from unittest.mock import AsyncMock, MagicMock, patch, PropertyMock

import pytest
from fastapi.testclient import TestClient

from app.main import app


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_audio_b64(content: bytes = b"\x00" * 200) -> str:
    """Zwraca base64 minimalnego pliku WAV (stub)."""
    return base64.b64encode(content).decode()


def _turn_payload(
    transcript: str = "mam reklamację",
    asr_confidence: float = 0.85,
    session_id: str = "sess-001",
    tenant_id: str = "tenant-abc",
    contact_id: str = "contact-xyz",
) -> dict:
    return {
        "session_id": session_id,
        "tenant_id": tenant_id,
        "contact_id": contact_id,
        "audio_base64": _make_audio_b64(),
        "audio_format": "wav",
    }


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def mock_redis():
    redis = AsyncMock()
    redis.ping = AsyncMock(return_value=True)
    redis.get = AsyncMock(return_value=None)
    redis.setex = AsyncMock(return_value=True)
    redis.delete = AsyncMock(return_value=1)
    redis.aclose = AsyncMock()
    return redis


@pytest.fixture
def mock_rabbit_connection():
    conn = AsyncMock()
    conn.is_closed = False
    conn.close = AsyncMock()

    channel = AsyncMock()
    exchange = AsyncMock()
    exchange.publish = AsyncMock()
    channel.declare_exchange = AsyncMock(return_value=exchange)
    channel.__aenter__ = AsyncMock(return_value=channel)
    channel.__aexit__ = AsyncMock(return_value=None)
    conn.channel = MagicMock(return_value=channel)

    return conn


@pytest.fixture
def client(mock_redis, mock_rabbit_connection):
    """
    TestClient z podmienionymi połączeniami Redis i RabbitMQ.
    Lifespan jest zastąpiony przez bezpośrednie wstrzyknięcie mocków.
    """
    import app.main as main_module

    main_module._redis_client = mock_redis
    main_module._rabbit_connection = mock_rabbit_connection

    with TestClient(app, raise_server_exceptions=True) as c:
        yield c

    main_module._redis_client = None
    main_module._rabbit_connection = None


# ---------------------------------------------------------------------------
# Testy POST /voicebot/turn
# ---------------------------------------------------------------------------

class TestProcessTurn:

    @patch("app.main.transcribe")
    def test_high_confidence_no_escalation(self, mock_transcribe, client, mock_redis):
        """Confidence >= 0.70 → escalate=False, brak eventu RabbitMQ."""
        mock_transcribe.return_value = {
            "text": "mam reklamację na produkt",
            "language": "pl",
            "segments": [],
            "confidence": 0.85,
        }

        resp = client.post("/voicebot/turn", json=_turn_payload())

        assert resp.status_code == 200
        data = resp.json()
        assert data["escalate"] is False
        assert data["escalation_reason"] is None
        assert data["transcript"] == "mam reklamację na produkt"
        assert data["intent"] == "complaint"
        assert data["confidence"] > 0.0

    @patch("app.main.transcribe")
    def test_low_confidence_triggers_escalation(self, mock_transcribe, client, mock_redis, mock_rabbit_connection):
        """Confidence < 0.70 → escalate=True, event opublikowany."""
        mock_transcribe.return_value = {
            "text": "coś tam mruczę pod nosem",
            "language": "pl",
            "segments": [],
            "confidence": 0.40,  # poniżej progu; NLU nie dopasuje słów → confidence * 0.5 = 0.20
        }

        resp = client.post("/voicebot/turn", json=_turn_payload())

        assert resp.status_code == 200
        data = resp.json()
        assert data["escalate"] is True
        assert data["escalation_reason"] == "low_confidence"

    @patch("app.main.transcribe")
    def test_response_contains_session_id(self, mock_transcribe, client):
        mock_transcribe.return_value = {
            "text": "godziny otwarcia",
            "language": "pl",
            "segments": [],
            "confidence": 0.9,
        }

        payload = _turn_payload(session_id="my-session-42")
        resp = client.post("/voicebot/turn", json=payload)

        assert resp.status_code == 200
        assert resp.json()["session_id"] == "my-session-42"

    @patch("app.main.transcribe")
    def test_full_transcript_accumulates_history(self, mock_transcribe, client, mock_redis):
        """full_transcript zawiera historię sesji."""
        existing_session = {
            "session_id": "sess-001",
            "tenant_id": "tenant-abc",
            "contact_id": "contact-xyz",
            "turns": [{"transcript": "pierwsza tura", "intent": "unknown", "confidence": 0.5}],
            "created_at": "2026-04-02T10:00:00+00:00",
        }
        mock_redis.get.return_value = json.dumps(existing_session)

        mock_transcribe.return_value = {
            "text": "druga tura z reklamacją",
            "language": "pl",
            "segments": [],
            "confidence": 0.8,
        }

        resp = client.post("/voicebot/turn", json=_turn_payload())
        assert resp.status_code == 200
        data = resp.json()
        assert len(data["full_transcript"]) == 2
        assert "pierwsza tura" in data["full_transcript"]

    @patch("app.main.transcribe")
    def test_asr_returns_empty_text(self, mock_transcribe, client):
        """Pusty transkrypt (zbyt krótkie audio) → unknown intent, eskalacja."""
        mock_transcribe.return_value = {
            "text": "",
            "language": "pl",
            "segments": [],
            "confidence": 0.0,
        }

        resp = client.post("/voicebot/turn", json=_turn_payload())
        assert resp.status_code == 200
        data = resp.json()
        assert data["intent"] == "unknown"
        assert data["escalate"] is True

    def test_missing_required_fields_returns_422(self, client):
        """Brak wymaganych pól → 422 Unprocessable Entity."""
        resp = client.post("/voicebot/turn", json={"session_id": "only-one-field"})
        assert resp.status_code == 422


# ---------------------------------------------------------------------------
# Testy DELETE /voicebot/session/{session_id}
# ---------------------------------------------------------------------------

class TestDeleteSession:

    def test_delete_session_returns_204(self, client, mock_redis):
        mock_redis.delete.return_value = 1
        resp = client.delete("/voicebot/session/sess-to-delete")
        assert resp.status_code == 204

    def test_delete_nonexistent_session_returns_204(self, client, mock_redis):
        mock_redis.delete.return_value = 0
        resp = client.delete("/voicebot/session/sess-nonexistent")
        assert resp.status_code == 204


# ---------------------------------------------------------------------------
# Testy GET /health
# ---------------------------------------------------------------------------

class TestHealth:

    def test_health_returns_ok(self, client):
        resp = client.get("/health")
        assert resp.status_code == 200
        assert resp.json() == {"status": "ok"}
