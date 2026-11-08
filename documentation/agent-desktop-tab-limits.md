# Pulpit agenta: limity kontaktów i karty (kontrakt BE/FE)

Powiązane zadania: **BE-156** (limit w routingu), **FE-163** (uzgodnienie frontu), FE-149 (zwrot do kolejki z listy nadmiaru), FE-136 (odtwarzanie kart), FE-148 (accept async), BE-209 (HTTP 409).

## Zasada

**Źródłem prawdy jest backend.** Liczbę kontaktów async dla agenta ustala routing: `queue.max_concurrent_contacts_per_agent` (domyślnie 3, V147), liczone **osobno dla każdej kolejki**, tylko dla EMAIL / CHAT / SOCIAL. PHONE jest poza limitem (steruje nim status `BUSY`).

Front **nie odrzuca** kontaktu przydzielonego przez backend. Ma tylko pojemność układu kart (1366×768). Kontakt, dla którego nie ma miejsca, trafia do listy nadmiaru (`cc-recovery-overflow-notice`) i jest **przyjęty** (`POST /contacts/{id}/accept`), więc `ContactAssignmentMonitor` go nie odsyła i nie oddaje do kolejki.

## Tabela kontraktu (stan docelowy)

| Aspekt | Backend (BE-156) | Frontend (`contact-tab.store.ts`, FE-163) | Zgodność |
|--------|------------------|-------------------------------------------|----------|
| Zakres liczenia | per kolejka (`queue_id`) | brak własnego limitu async; przyjmuje wszystko od backendu | tak |
| Źródło limitu | `queue.max_concurrent_contacts_per_agent` | nie dotyczy (front nie liczy limitu biznesowego) | tak |
| EMAIL / CHAT / SOCIAL | liczone do limitu kolejki | liczone jednakowo do pojemności układu | tak |
| PHONE | poza limitem (status `BUSY`) | `MAX_PHONE_TABS = 1` (jedna linia softphone), jedna karta | tak (inna przyczyna) |
| Razem | brak limitu łącznego | `MAX_TOTAL_TABS = 4` widocznych kart (układ), nie limit biznesowy | świadoma różnica |
| Miejsce dla telefonu | n/d | 1 z 4 miejsc zarezerwowane dla PHONE, dopóki nie ma karty PHONE: async zajmuje najwyżej 3 widoczne karty | decyzja właściciela 2026-11-08 (patrz niżej) |
| Zajęty slot | `ASSIGNED` + `ACTIVE` + `ON_HOLD` | karta otwarta (także szkic/ACW) lub pozycja na liście nadmiaru | zgodne co do zasady |
| Nadmiar | n/d | lista nadmiaru: przyjęty, bez karty; „Zwróć do kolejki” albo promocja do karty | — |

## Przepływ kontaktu async (CONTACT_ASSIGNED)

1. `IncomingCallAlertService` odbiera `CONTACT_ASSIGNED`.
2. Już ma kartę lub jest na liście nadmiaru: ponowna dostawa tylko ponawia ewentualnie nieudany `accept`.
3. Jest miejsce: karta (nieaktywna, nie kradnie aktywnej) i `accept`.
4. Brak miejsca: `AgentRecoveryService.parkAssigned` dodaje pozycję do listy nadmiaru i wysyła `accept`; agent dostaje ostrzeżenie „czeka poza limitem kart”.
5. Zwolnione miejsce (zamknięta karta, zwrot, nieudany zwrot, zamknięcie po dyspozycji PHONE): efekt w `AgentRecoveryService` otwiera karty z listy w kolejności backendu. Bez drugiego `accept`. Przed otwarciem karta jest rewalidowana (`GET /contacts/{id}`): kontakt zamknięty, zakończony, oddany do kolejki albo przepięty do innego agenta znika z listy z krótką informacją (jedna na serię), a nie staje się martwą kartą. Gdy sprawdzenie się nie powiedzie (sieć, 5xx), karta jest otwierana mimo to. Zegar karty startuje od czasu przydziału (`assignedAt` ze zdarzenia lub z recovery), nie od chwili promocji. Dyspozycja (ACW) dotyczy tylko PHONE; kontakty async nie mają ACW.
6. „Zwróć do kolejki” na pozycji: `POST /contacts/{id}/release`. Pozycja znika po potwierdzeniu (404/409 = już nie jego, też znika z wyjaśnieniem; inny błąd zostawia pozycję). Kontakt oddany ponownie w trakcie zwalniania jest otwierany lub parkowany.
7. Przeładowanie strony / reconnect: `GET /agent/me/active-contacts` odtwarza karty i listę nadmiaru; kontakt `RINGING` bez miejsca jest przyjmowany.

Recovery a wpisy zaparkowane na żywo: odpowiedź `active-contacts` jest migawką sprzed jej nadejścia. Pełna odpowiedź zastępuje listę nadmiaru, ale **zachowuje** wpisy zaparkowane po starcie żądania oraz wiersze w trakcie zwrotu (inaczej przyjęty kontakt `ACTIVE` zostałby bez karty i bez wiersza). Wpis starszy niż żądanie, którego backend już nie zwraca, jest usuwany.

PHONE nie trafia do automatycznej promocji z listy (wymaga sesji softphone); „Sprawdź ponownie” obsługuje te przypadki.

## Rezerwa miejsca dla PHONE: decyzja właściciela

**Decyzja właściciela 2026-11-08: tak, 1 z 4 miejsc na pasku kart zostaje zarezerwowane dla PHONE** (async: najwyżej 3 widoczne karty), dopóki nie ma karty PHONE. Połączenie przychodzące nie jest wypierane przez karty async.

Rezerwa jest **bezwarunkowa**. Warunkowanie (agent bez możliwości telefonicznej dostaje 4 karty async) wymaga wiarygodnego sygnału zdolności telefonicznej agenta po stronie frontu. Ustalono, że go nie ma: claimy JWT (`tenant_id`, `user_id`, `role`, `email`) i odpowiedź `/users/me` (id, e-mail, imię, rola, status) nie opisują kolejek ani kanałów agenta, a `AgentStatusService`/`QueueStateService` ich nie przechowują. Zgadywanie z takich pośrednich danych (np. „nigdy nie było karty PHONE”) dałoby rezerwę, która znika i wraca w trakcie zmiany, więc zostaje bezwarunkowo. Warunkowanie wymaga pola w `/users/me` lub profilu agenta (np. kanały kolejek agenta) i jest osobnym zadaniem BE+FE; do tego czasu agent z kolejkami wyłącznie EMAIL/CHAT/SOCIAL ma 3 karty, a reszta czeka na liście nadmiaru (przyjęta, bez utraty).

## Rozmiar nadmiaru i autorytet limitu

- Rozmiar nadmiaru jest ograniczony tylko przez backend: do `liczba_kolejek × limit − 3` kontaktów async przy rezerwie (np. 2 kolejki × 3 = 6, z czego 3 karty i 3 w nadmiarze). Lista przewija się po 7,5 rem, jest fokusowalna (`tabindex="0"`, `aria-label`).
- **Autorytatywnym strażnikiem limitu jest atomowe `assignIfQueued`** w backendzie. Filtr `filterAgentsUnderAsyncLimit` w `DefaultRoutingEngine` jest *fail-open* (przy błędzie zapytania nie blokuje przydziału), więc w wyścigu BE może przydzielić więcej niż limit kolejki. Front tego nie zakłada ani nie egzekwuje: przyjmuje wszystko, co przydzielił backend, i pokazuje na liście nadmiaru.
- Kontakt zaparkowany jest `ACTIVE` w backendzie (zajmuje slot BE-156, liczony do limitu kolejki). Czy `ACTIVE` bez karty uruchamia czas obsługi, SLA i obciążenie widoczne dla nadzorcy, jest pytaniem do backendu (otwarte w CR-FRONTEND.md, FE-163).

## Tryby awarii

- Recovery w trakcie parkowania: patrz wyżej (scalanie, nie nadpisywanie).
- Zwrot do kolejki w trakcie recovery lub nieudany zwrot (5xx): wiersz zostaje, a przy wolnym slocie dostaje kartę od razu (`returningIds` śledzone przez efekt promocji).
- Komunikat o zaparkowanym kontakcie poza pulpitem (inna trasa): bez osobnego banera z przejściem; lista jest na pulpicie, a toasty są zagregowane (pierwszy z nazwą, kolejne w oknie 5 s jako jedno podsumowanie).

## HTTP 409 (BE-209)

`setDisposition` i `abandonContact` mogą zwrócić 409 przy wyścigu ze zmianą statusu/agenta (`updateContact` nie ma dziś wywołania we froncie: nie dotyczy). Front (`ContactConflictService` → `AgentRecoveryService.reconcileContact`) czyta realny stan kontaktu (`GET /contacts/{id}`, nie wspólny przebieg recovery, który mógł wystartować przed konfliktem) i reaguje zgodnie z tym, co znalazł:

| Stan kontaktu w BE | Co robi front | Komunikat |
|--------------------|---------------|-----------|
| Zamknięty, zakończony (dla PHONE: z zapisaną dyspozycją), oddany do kolejki, przepięty na innego agenta, 404 | zamyka kartę (bez pętli 409), tekst wpisany przez agenta kopiuje do schowka (dyspozycja, notatka, odpowiedź social, treść szkicu e-mail; schowek best effort) | `gone` / `goneNotesCopied` |
| Nadal należy do agenta | karta zostaje, zakończona rozmowa przechodzi w ACW; formularz i szkic bez zmian | komunikat wywołującego (`stillYours`) |
| Nie udało się sprawdzić | bez zmian | `unverified` |

Porzucenie e-maila (`cancelReply`) przy 409 nie zamyka karty i nie kasuje szkicu: dopiero potwierdzenie `abandon` (lub inny błąd niż 409, gdy agent świadomie wychodzi) zamyka kartę.
