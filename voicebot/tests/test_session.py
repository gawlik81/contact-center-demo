"""
Testy jednostkowe dla modułu session.py (Redis session manager).
Używa Mock zamiast prawdziwego Redis.
Uruchomienie: pytest tests/test_session.py -v
"""
import json
from unittest.mock import AsyncMock, MagicMock, patch, call

import pytest

from app.session import get_session, update_session, delete_session, _SESSION_KEY_PREFIX


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def mock_redis():
    """Mocka klienta Redis z async metodami."""
    client = AsyncMock()
    return client


@pytest.fixture
def sample_turn():
    return {"transcript": "mam reklamację", "intent": "complaint", "confidence": 0.85}


# ---------------------------------------------------------------------------
# get_session
# ---------------------------------------------------------------------------

class TestGetSession:

    @pytest.mark.asyncio
    async def test_returns_none_when_key_not_found(self, mock_redis):
        mock_redis.get.return_value = None
        result = await get_session(mock_redis, "session-123")
        assert result is None
        mock_redis.get.assert_awaited_once_with(f"{_SESSION_KEY_PREFIX}session-123")

    @pytest.mark.asyncio
    async def test_returns_parsed_session(self, mock_redis, sample_turn):
        session_data = {
            "session_id": "session-123",
            "tenant_id": "tenant-abc",
            "contact_id": "contact-xyz",
            "turns": [sample_turn],
            "created_at": "2026-04-02T10:00:00+00:00",
        }
        mock_redis.get.return_value = json.dumps(session_data)

        result = await get_session(mock_redis, "session-123")

        assert result is not None
        assert result["session_id"] == "session-123"
        assert len(result["turns"]) == 1

    @pytest.mark.asyncio
    async def test_returns_none_on_redis_error(self, mock_redis):
        mock_redis.get.side_effect = Exception("Redis connection error")
        result = await get_session(mock_redis, "session-123")
        assert result is None


# ---------------------------------------------------------------------------
# update_session
# ---------------------------------------------------------------------------

class TestUpdateSession:

    @pytest.mark.asyncio
    async def test_creates_new_session_when_not_exists(self, mock_redis, sample_turn):
        mock_redis.get.return_value = None

        result = await update_session(
            mock_redis,
            session_id="session-new",
            tenant_id="tenant-abc",
            contact_id="contact-xyz",
            turn_data=sample_turn,
            ttl_seconds=900,
        )

        assert result["session_id"] == "session-new"
        assert result["tenant_id"] == "tenant-abc"
        assert len(result["turns"]) == 1
        assert result["turns"][0] == sample_turn
        mock_redis.setex.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_appends_turn_to_existing_session(self, mock_redis, sample_turn):
        existing = {
            "session_id": "session-123",
            "tenant_id": "tenant-abc",
            "contact_id": "contact-xyz",
            "turns": [{"transcript": "stara tura", "intent": "unknown", "confidence": 0.3}],
            "created_at": "2026-04-02T10:00:00+00:00",
        }
        mock_redis.get.return_value = json.dumps(existing)

        result = await update_session(
            mock_redis,
            session_id="session-123",
            tenant_id="tenant-abc",
            contact_id="contact-xyz",
            turn_data=sample_turn,
            ttl_seconds=900,
        )

        assert len(result["turns"]) == 2
        assert result["turns"][-1] == sample_turn

    @pytest.mark.asyncio
    async def test_sets_correct_ttl(self, mock_redis, sample_turn):
        mock_redis.get.return_value = None

        await update_session(
            mock_redis,
            session_id="session-123",
            tenant_id="tenant-abc",
            contact_id="contact-xyz",
            turn_data=sample_turn,
            ttl_seconds=900,
        )

        args, kwargs = mock_redis.setex.call_args
        assert args[1] == 900  # ttl_seconds

    @pytest.mark.asyncio
    async def test_returns_minimal_session_on_error(self, mock_redis, sample_turn):
        mock_redis.get.side_effect = Exception("Redis failure")

        result = await update_session(
            mock_redis,
            session_id="session-err",
            tenant_id="tenant-abc",
            contact_id="contact-xyz",
            turn_data=sample_turn,
            ttl_seconds=900,
        )

        # Powinien zwrócić minimalną sesję zamiast rzucać wyjątkiem
        assert result["session_id"] == "session-err"
        assert len(result["turns"]) == 1


# ---------------------------------------------------------------------------
# delete_session
# ---------------------------------------------------------------------------

class TestDeleteSession:

    @pytest.mark.asyncio
    async def test_deletes_existing_session(self, mock_redis):
        mock_redis.delete.return_value = 1  # Redis zwraca liczbę usuniętych kluczy

        await delete_session(mock_redis, "session-123")

        mock_redis.delete.assert_awaited_once_with(f"{_SESSION_KEY_PREFIX}session-123")

    @pytest.mark.asyncio
    async def test_no_error_when_session_not_exists(self, mock_redis):
        mock_redis.delete.return_value = 0

        # Nie powinien rzucać wyjątku
        await delete_session(mock_redis, "session-missing")

    @pytest.mark.asyncio
    async def test_handles_redis_error_gracefully(self, mock_redis):
        mock_redis.delete.side_effect = Exception("Connection lost")

        # Nie powinien propagować wyjątku
        await delete_session(mock_redis, "session-123")
