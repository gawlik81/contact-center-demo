---
name: feedback-verify-callers-and-symmetry
description: Przy dekonstrukcji sprawdzaj wołających funkcji SQL/metod i cudze numery linii, waliduj skryptem (z testem negatywnym) symetrię Zależy od/Blokuje, cykle, liczniki PROGRESS.md; przy korektach nanoś zmiany w plikach cudzych ticketów zachowawczo
metadata:
  type: feedback
---

1. Zanim zaplanujesz „rozszerzenie funkcji SQL/metody", zgreppuj jej wołających w `backend/app/src/main`, `frontend/`, `voicebot/`. W EPIC-30 `anonymize_customer`/`export_customer_data` (V013/V017) miały 0 wywołań, a realny przepływ RODO był w `GdprServiceImpl` (+ druga ścieżka REST `DELETE /api/customers/{id}`) — założenie ze zlecenia było niepełne i zmieniło zakres D3.
2. Numery linii z cudzych notatek/zleceń weryfikuj `grep -n` przed wpisaniem (zlecenie podało V013 „35–43/67/108", faktycznie `UPDATE customer` l. 48–58, `UPDATE contact` l. 80, `EXCEPTION` l. 121; V010 komentarz `s3_url` to l. 44, nie 45).
3. Po wygenerowaniu/zmianie ticketów uruchom skrypt Python parsujący nagłówki `### XX-NNN` i pola `**Zależy od:**`/`**Blokuje:**`: unikalność ID, komplet pól (w tym `Wykonawca`, `Epic`), kryteria `- [ ]`, SYMETRIA zależności, brak cykli (Kahn), brak migracji > V093 wpisanych na sztywno, odwołania do nieistniejących ticketów, zgodność wierszy i liczników PROGRESS.md. **Przetestuj skrypt negatywnie** (mutacja kopii plików w scratchpadzie: usuń jedną zależność, wpisz V094, popsuj licznik) — zwykły zielony przebieg niczego nie dowodzi. Pole „Blokuje" listuje tylko bezpośrednich zależnych (tranzytywne wpisy = asymetria).
4. Skrypty Python z polskimi cudzysłowami „…" w łańcuchach: zamykający ASCII `"` kończy literał — używaj typograficznego ” albo plików skryptów z apostrofami/`'''`; edycje dużych plików TASKS rób skryptem z asercją na unikalność kotwicy (`assert count == 1`) i kopią zapasową w scratchpadzie.
5. Notacja grafów zależności: „A → B = kolejność wykonania (B zależy od A)" — nie „←". Zależność wspólnej funkcji pomocniczej (D9) wymaga jawnej krawędzi (DB-061 → DB-062), inaczej dwa tickety równolegle zdefiniują ją dwa razy.
6. Ścieżka pamięci tylko kanoniczna i absolutna (`/home/pawelm/contact-center/.claude/agent-memory/...`); literówka w ścieżce tworzy śmieciowy katalog w /tmp — sprzątnąć `rm`/`rmdir` po ścieżce, którą się utworzyło.

**Why:** każdy z tych punktów kosztował poprawki lub groził błędnym zakresem ticketów. **How to apply:** przy każdej kolejnej dekonstrukcji lub korekcie epiku (kroki: verify callers/linie → edytuj skryptem z asercjami → skrypt walidujący + test negatywny → dopiero potem PROGRESS.md i zwrot).
