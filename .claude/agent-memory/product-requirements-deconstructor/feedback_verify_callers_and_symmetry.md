---
name: feedback-verify-callers-and-symmetry
description: Przy dekonstrukcji sprawdzaj, czy funkcje SQL/metody mają WOŁAJĄCEGO w Javie, i weryfikuj skryptem symetrię Zależy od/Blokuje oraz strukturę ticketów przed oddaniem
metadata:
  type: feedback
---

1. Zanim zaplanujesz „rozszerzenie funkcji SQL/metody", zgreppuj jej wołających w `backend/app/src/main`, `frontend/`, `voicebot/`. W EPIC-30 `anonymize_customer`/`export_customer_data` (V013/V017) miały 0 wywołań, a realny przepływ RODO był w `GdprServiceImpl` — założenie ze zlecenia („rozszerzyć funkcje") było niepełne i zmieniło zakres D3.
2. Po wygenerowaniu ticketów uruchom prosty skrypt Python parsujący nagłówki `### XX-NNN` i pola `**Zależy od:**`/`**Blokuje:**`: sprawdź unikalność ID, komplet pól, kryteria `- [ ]` i SYMETRIĘ zależności (pierwszy przebieg EPIC-30 wykrył 21 asymetrii i tranzytywnych duplikatów). Pole „Blokuje" listuje tylko bezpośrednich zależnych.
3. Pisząc skrypty Python z polskimi cudzysłowami „…" w łańcuchach: zamykający ASCII `"` kończy literał — używaj typograficznego ” albo pliku skryptu z apostrofami (skrypt inline heredoc padł z SyntaxError, PROGRESS.md na szczęście nietknięty).
4. Notacja grafów zależności: „A → B = kolejność wykonania (B zależy od A)" — nie „←" (wieloznaczne).

**Why:** każdy z tych punktów kosztował poprawki lub groził błędnym zakresem ticketów. **How to apply:** przy każdej kolejnej dekonstrukcji epiku (kroki: verify callers → pisz tickety → skrypt walidujący → dopiero potem PROGRESS.md i zwrot).
