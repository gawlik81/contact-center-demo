import json
import logging
from datetime import datetime, timezone
from typing import Any

import aio_pika
from aio_pika import DeliveryMode, Message
from aio_pika.abc import AbstractRobustConnection

logger = logging.getLogger(__name__)

_CC_EVENTS_EXCHANGE = "cc.events"
_ESCALATE_ROUTING_KEY = "voicebot.escalate"
_ESCALATE_PRIORITY = 9  # 0–10; 9 = HIGH


async def publish_escalation(
    connection: AbstractRobustConnection,
    session_id: str,
    tenant_id: str,
    contact_id: str,
    transcript: str,
    intent: str,
    confidence: float,
    reason: str,
) -> None:
    """
    Publikuje event eskalacji voicebota na RabbitMQ.

    Exchange:    cc.events (topic)
    Routing key: voicebot.escalate
    Priority:    9 (HIGH)
    """
    payload: dict[str, Any] = {
        "eventType": "VOICEBOT_ESCALATE",
        "sessionId": session_id,
        "tenantId": tenant_id,
        "contactId": contact_id,
        "transcript": transcript,
        "intent": intent,
        "confidence": confidence,
        "reason": reason,
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }

    try:
        async with connection.channel() as channel:
            exchange = await channel.declare_exchange(
                _CC_EVENTS_EXCHANGE,
                aio_pika.ExchangeType.TOPIC,
                durable=True,
                passive=False,
            )

            message = Message(
                body=json.dumps(payload).encode("utf-8"),
                delivery_mode=DeliveryMode.PERSISTENT,
                content_type="application/json",
                priority=_ESCALATE_PRIORITY,
            )

            await exchange.publish(message, routing_key=_ESCALATE_ROUTING_KEY)

            logger.info(
                "[RabbitMQ] Opublikowano eskalację: session_id=%s, tenant_id=%s, intent=%s, confidence=%.3f",
                session_id, tenant_id, intent, confidence,
            )

    except Exception as exc:
        logger.error(
            "[RabbitMQ] Błąd publikacji eskalacji: session_id=%s, error=%s",
            session_id, exc, exc_info=True,
        )
        raise
