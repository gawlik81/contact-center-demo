"""
Testy jednostkowe dla modułu NLU (keyword-based intent detection).
Uruchomienie: pytest tests/test_nlu.py -v
"""
import pytest
from app.nlu import detect_intent, FALLBACK_INTENT


# ---------------------------------------------------------------------------
# Testy intencji per kategoria
# ---------------------------------------------------------------------------

class TestIntentDetection:

    def test_complaint_reklamacja(self):
        result = detect_intent("mam reklamację na produkt", asr_confidence=0.9)
        assert result["intent"] == "complaint"
        assert result["confidence"] > 0.0

    def test_complaint_nie_dziala(self):
        result = detect_intent("moje urządzenie nie działa", asr_confidence=0.85)
        assert result["intent"] == "complaint"

    def test_complaint_awaria(self):
        result = detect_intent("zgłaszam awarię systemu", asr_confidence=0.8)
        assert result["intent"] == "complaint"

    def test_billing_faktura(self):
        result = detect_intent("chcę sprawdzić fakturę za ostatni miesiąc", asr_confidence=0.9)
        assert result["intent"] == "billing"

    def test_billing_platnosc(self):
        result = detect_intent("mam problem z płatnością", asr_confidence=0.75)
        assert result["intent"] == "billing"

    def test_billing_rachunek(self):
        result = detect_intent("prosiłem o rachunek ale nie dotarł", asr_confidence=0.8)
        assert result["intent"] == "billing"

    def test_technical_internet(self):
        result = detect_intent("mój internet nie działa od rana", asr_confidence=0.88)
        assert result["intent"] == "technical"

    def test_technical_router(self):
        result = detect_intent("router przestał działać po aktualizacji", asr_confidence=0.9)
        assert result["intent"] == "technical"

    def test_technical_wifi(self):
        result = detect_intent("nie mogę się połączyć przez wifi", asr_confidence=0.85)
        assert result["intent"] == "technical"

    def test_cancellation_rezygnacja(self):
        result = detect_intent("chciałbym złożyć rezygnację z usługi", asr_confidence=0.9)
        assert result["intent"] == "cancellation"

    def test_cancellation_wypowiedzenie(self):
        result = detect_intent("chcę złożyć wypowiedzenie umowy", asr_confidence=0.82)
        assert result["intent"] == "cancellation"

    def test_sales_oferta(self):
        result = detect_intent("proszę o aktualną ofertę dla nowych klientów", asr_confidence=0.9)
        assert result["intent"] == "sales"

    def test_sales_promocja(self):
        result = detect_intent("czy jest jakaś promocja na abonament", asr_confidence=0.87)
        assert result["intent"] == "sales"

    def test_general_info_godziny(self):
        result = detect_intent("jakie są godziny otwarcia biura", asr_confidence=0.9)
        assert result["intent"] == "general_info"

    def test_general_info_adres(self):
        result = detect_intent("jaki jest adres waszego biura", asr_confidence=0.85)
        assert result["intent"] == "general_info"


# ---------------------------------------------------------------------------
# Testy fallback i edge case
# ---------------------------------------------------------------------------

class TestFallbackAndEdgeCases:

    def test_fallback_no_keywords(self):
        result = detect_intent("dzień dobry w czym mogę pomóc", asr_confidence=0.8)
        assert result["intent"] == FALLBACK_INTENT
        # Fallback confidence = asr_confidence * 0.5
        assert abs(result["confidence"] - 0.8 * 0.5) < 0.001

    def test_fallback_empty_transcript(self):
        result = detect_intent("", asr_confidence=0.8)
        assert result["intent"] == FALLBACK_INTENT

    def test_fallback_whitespace_only(self):
        result = detect_intent("   ", asr_confidence=0.8)
        assert result["intent"] == FALLBACK_INTENT

    def test_low_asr_confidence_skips_nlu(self):
        # ASR confidence < 0.30 → NLU pomijany, nawet jeśli keyword jest w tekście
        result = detect_intent("reklamacja faktura problem", asr_confidence=0.25)
        assert result["intent"] == FALLBACK_INTENT
        assert result["confidence"] == 0.25

    def test_asr_confidence_exactly_at_threshold(self):
        # 0.30 to granica – powinien przejść do NLU
        result = detect_intent("reklamacja", asr_confidence=0.30)
        assert result["intent"] == "complaint"

    def test_case_insensitive_matching(self):
        result = detect_intent("REKLAMACJA na produkt", asr_confidence=0.9)
        assert result["intent"] == "complaint"

    def test_case_insensitive_mixed(self):
        result = detect_intent("Faktura Za Usługę", asr_confidence=0.9)
        assert result["intent"] == "billing"

    def test_confidence_bounded_by_one(self):
        # Bardzo dużo słów kluczowych – confidence nie może przekroczyć 1.0
        text = "reklamacja skarga problem zepsuty nie działa awaria"
        result = detect_intent(text, asr_confidence=1.0)
        assert result["confidence"] <= 1.0

    def test_confidence_non_negative(self):
        result = detect_intent("cokolwiek", asr_confidence=0.5)
        assert result["confidence"] >= 0.0

    def test_multiple_keywords_increases_confidence(self):
        single = detect_intent("reklamacja", asr_confidence=0.9)
        multiple = detect_intent("reklamacja problem awaria", asr_confidence=0.9)
        # Więcej dopasowań → wyższe confidence
        assert multiple["confidence"] >= single["confidence"]
