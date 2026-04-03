import base64
import io
import logging
import math
import tempfile
import os
from typing import Any

logger = logging.getLogger(__name__)

# Lazy-loaded – model ładowany przy pierwszym użyciu (szybszy boot)
_whisper_model = None
_whisper_module = None


def _get_model(model_name: str):
    """Lazy-load Whisper model on first use."""
    global _whisper_model, _whisper_module
    if _whisper_model is None:
        import whisper as _w
        _whisper_module = _w
        logger.info("[ASR] Ładowanie modelu Whisper: model=%s", model_name)
        _whisper_model = _w.load_model(model_name)
        logger.info("[ASR] Model Whisper załadowany pomyślnie")
    return _whisper_model


def _compute_confidence_from_segments(segments: list[dict]) -> float:
    """
    Whisper nie dostarcza bezpośrednio confidence score.
    Obliczamy go jako exp(avg_logprob) uśredniony po segmentach.
    avg_logprob jest typowo ujemny; exp(0) = 1.0, exp(-inf) ≈ 0.0.
    """
    if not segments:
        return 0.0
    avg_logprob = sum(s.get("avg_logprob", -1.0) for s in segments) / len(segments)
    confidence = math.exp(avg_logprob)
    return min(1.0, max(0.0, confidence))


async def transcribe(audio_base64: str, audio_format: str, model_name: str) -> dict[str, Any]:
    """
    Transkrybuje audio zakodowane w base64 przy użyciu Whisper.

    Returns:
        dict z polami: text (str), language (str), segments (list), confidence (float)
    """
    # Dekoduj base64 do bajtów
    try:
        audio_bytes = base64.b64decode(audio_base64)
    except Exception as exc:
        logger.warning("[ASR] Błąd dekodowania base64: %s", exc)
        return {"text": "", "language": "pl", "segments": [], "confidence": 0.0}

    if len(audio_bytes) < 100:
        logger.warning("[ASR] Audio zbyt krótkie: %d bajtów", len(audio_bytes))
        return {"text": "", "language": "pl", "segments": [], "confidence": 0.0}

    # Zapisz do pliku tymczasowego – wymagane przez ffmpeg/Whisper
    suffix = f".{audio_format}"
    tmp_path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp.write(audio_bytes)
            tmp_path = tmp.name

        model = _get_model(model_name)
        result = model.transcribe(tmp_path, language="pl")

        segments = result.get("segments", [])
        confidence = _compute_confidence_from_segments(segments)
        text = (result.get("text") or "").strip()

        logger.info(
            "[ASR] Transkrypcja zakończona: text_len=%d, language=%s, confidence=%.3f",
            len(text), result.get("language", "pl"), confidence,
        )
        return {
            "text": text,
            "language": result.get("language", "pl"),
            "segments": segments,
            "confidence": confidence,
        }

    except Exception as exc:
        logger.error("[ASR] Błąd transkrypcji Whisper: %s", exc, exc_info=True)
        return {"text": "", "language": "pl", "segments": [], "confidence": 0.0}

    finally:
        if tmp_path and os.path.exists(tmp_path):
            try:
                os.unlink(tmp_path)
            except OSError:
                pass
