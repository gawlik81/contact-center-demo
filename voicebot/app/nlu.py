import logging
import re
from typing import Any

logger = logging.getLogger(__name__)

# Reguły intencji – słowa kluczowe po polsku, case-insensitive
INTENT_RULES: dict[str, list[str]] = {
    "complaint":    ["reklamacja", "skarga", "problem", "zepsuty", "nie działa", "awaria"],
    "billing":      ["faktura", "płatność", "rachunek", "opłata", "konto", "należność"],
    "technical":    ["internet", "router", "wifi", "sieć", "połączenie", "sygnał", "konfiguracja"],
    "cancellation": ["rezygnacja", "wypowiedzenie", "anulowanie", "odwołanie", "zrezygnować"],
    "sales":        ["oferta", "kupić", "zamówienie", "cena", "promocja", "rabat"],
    "general_info": ["godziny", "adres", "otwarcie", "jak", "kiedy", "gdzie", "informacja"],
}

FALLBACK_INTENT = "unknown"

# Minimalne ASR confidence wymagane do analizy NLU
_MIN_ASR_CONFIDENCE_FOR_NLU = 0.30

# Odpowiedzi voicebota per intent – do odesłania w TurnResponse.response_text
INTENT_RESPONSES: dict[str, str] = {
    "complaint":    "Rozumiem, zgłosiłeś reklamację. Czy mogę prosić o więcej szczegółów dotyczących problemu?",
    "billing":      "Rozumiem, masz pytanie dotyczące faktury. Proszę podaj numer faktury lub opisz problem.",
    "technical":    "Rozumiem, masz problem techniczny. Proszę opisz dokładniej co się dzieje.",
    "cancellation": "Rozumiem, chcesz zrezygnować z usługi. Czy mogę zapytać o powód rezygnacji?",
    "sales":        "Rozumiem, interesuje Cię nasza oferta. W czym konkretnie mogę pomóc?",
    "general_info": "Oczywiście, chętnie udzielę informacji. Proszę zadaj swoje pytanie.",
    "unknown":      "Przepraszam, nie zrozumiałem. Czy możesz powtórzyć lub opisać sprawę inaczej?",
}


def detect_intent(transcript: str, asr_confidence: float) -> dict[str, Any]:
    """
    Wykrywa intencję na podstawie słów kluczowych w transkrypcie.

    Algorytm:
    - Jeśli ASR confidence < 0.30 → zwraca unknown bez analizy NLU
    - Dla każdego intentu zlicza dopasowane słowa kluczowe
    - Wybiera intent z największą liczbą dopasowań
    - Confidence = min(1.0, matched_count / 2.0 * asr_confidence)
    - Przy braku dopasowania → confidence = asr_confidence * 0.5

    Returns:
        dict z polami: intent (str), confidence (float)
    """
    if asr_confidence < _MIN_ASR_CONFIDENCE_FOR_NLU:
        logger.debug(
            "[NLU] ASR confidence zbyt niskie (%.3f < %.3f) – pomijam analizę NLU",
            asr_confidence, _MIN_ASR_CONFIDENCE_FOR_NLU,
        )
        return {
            "intent": FALLBACK_INTENT,
            "confidence": asr_confidence,
            "response_text": INTENT_RESPONSES[FALLBACK_INTENT],
        }

    if not transcript or not transcript.strip():
        return {
            "intent": FALLBACK_INTENT,
            "confidence": asr_confidence * 0.5,
            "response_text": INTENT_RESPONSES[FALLBACK_INTENT],
        }

    text_lower = transcript.lower()

    best_intent = FALLBACK_INTENT
    best_matched = 0

    for intent, keywords in INTENT_RULES.items():
        matched = sum(1 for kw in keywords if kw in text_lower)
        if matched > best_matched:
            best_matched = matched
            best_intent = intent

    if best_intent == FALLBACK_INTENT or best_matched == 0:
        confidence = asr_confidence * 0.5
        logger.debug("[NLU] Brak dopasowania – fallback intent, confidence=%.3f", confidence)
        return {
            "intent": FALLBACK_INTENT,
            "confidence": confidence,
            "response_text": INTENT_RESPONSES[FALLBACK_INTENT],
        }

    # NLU confidence: każde dopasowane słowo daje 0.6 pewności (niezależnie od ASR),
    # ASR confidence jako czynnik pomocniczy (waga 0.25). Dzięki temu 1 trafienie
    # w słowo kluczowe wystarczy do przekroczenia progu 0.40 na nagraniach telefonicznych.
    nlu_score = min(1.0, best_matched * 0.6)
    confidence = min(1.0, nlu_score * 0.75 + asr_confidence * 0.25)
    logger.info(
        "[NLU] Wykryto intent: intent=%s, matched=%d, confidence=%.3f",
        best_intent, best_matched, confidence,
    )
    return {
        "intent": best_intent,
        "confidence": confidence,
        "response_text": INTENT_RESPONSES.get(best_intent, INTENT_RESPONSES[FALLBACK_INTENT]),
    }
