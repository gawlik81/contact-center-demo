---
name: Agent calendar component (FE-043)
description: AgentCalendarComponent – widok tygodniowy/dzienny kalendarza agenta jako zakładka w AgentDesktopComponent
type: project
---

AgentCalendarComponent zaimplementowany w `agent-desktop/agent-calendar/` (3 pliki: ts/html/scss).

**Kluczowe decyzje:**
- Zakładka Kalendarz jest stała w dolnym pasku nawigacyjnym (`desktop__tab-nav`) — nie jest w tablicy dynamicznych zakładek kontaktów
- `calendarTabActive = signal(false)` w AgentDesktopComponent steruje widocznością; customer panel (aside) jest ukryty gdy kalendarz aktywny
- Widok tygodniowy (domyślnie) i dzienny — przełączane sygnałem `viewMode`; na < 640px automatycznie dzień
- Dane: `AgentCalendarService.getCalendar(from, to)` wywoływane przy starcie i każdej nawigacji
- Zdarzenia: 3 typy z kolorami (callback=#f97316, campaign=#3b82f6, break=#22c55e)
- Callback klik → `RescheduleCallbackModalComponent` (signal `selectedCallback`)
- Przerwa klik → placeholder modal (FE-045 doimplementuje `AddBreakModalComponent`)
- Kampania klik → inline details panel (`selectedCampaign` signal, toggle)
- FAB "Dodaj przerwe" → `addBreakMode.set(true)` (FE-045)
- `ContactTabStore.clearActiveTab()` NIE istnieje — nie dodawać do store, zamiast tego kalendarz po prostu nadpisuje widok

**Why:** FE-043 — nowa zakładka w AgentDesktop, przygotowanie pod FE-044 (AddCallbackModal) i FE-045 (AddBreakModal)

**How to apply:** Przy FE-045 — zaimplementuj `AddBreakModalComponent`, podmień placeholder w `agent-calendar.component.html` (`@if (selectedBreak() || addBreakMode())`).
