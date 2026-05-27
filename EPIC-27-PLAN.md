# EPIC-27: Własne dyspozycje per kampania i kolejka — Plan realizacji

**Branch:** `custom-dispozition`
**Data planu:** 2026-05-27
**Status epica:** ⬜ Do zrobienia

---

## 1. Podsumowanie

| Metryka | Wartość |
|---------|---------|
| Tickety | 8 (DB-040, BE-092, BE-093, BE-094, FE-090, FE-091, FE-092, FE-093) |
| Fale wykonania | 5 |
| Złożoność łączna | M (backend) + M (frontend) |
| Kluczowa zależność | DB-040 → BE-092 blokuje wszystko |

**Problem:** Dyspozycje po kontakcie (SALE, NO_INTEREST, CALLBACK…) są zakodowane statycznie w frontendzie.  
**Rozwiązanie:** Supervisor konfiguruje własny zestaw dyspozycji per kampania lub per kolejka. Agent widzi je dynamicznie. Gdy brak konfiguracji — system wraca do 6 domyślnych.

---

## 2. Graf zależności

```
DB-040 (V069 migration)
  └─► BE-092 (CustomDisposition entity + service)
        ├─► BE-093 (Supervisor REST API)  ─┐
        └─► BE-094 (Agent endpoint)       ─┤
                                           └─► FE-090 (Angular service + models)
                                                 ├─► FE-091 (Campaign panel)
                                                 ├─► FE-092 (Queue panel)
                                                 └─► FE-093 (Agent panel — dynamic loading)
```

---

## 3. Fale wykonania

### Fala 1 — Baza danych (blokuje wszystko)

| Ticket | Opis | Złożoność | Agent |
|--------|------|-----------|-------|
| **DB-040** | Migracja V069 — tabela `custom_disposition` | S | `db-schema-architect` |

**Wyjście:** plik `V069__create_custom_disposition.sql` zastosowany na dev.

**DDL gotowe w TASKS-DATABASE.md** — wystarczy skopiować i uruchomić.

---

### Fala 2 — Warstwa domenowa backendu (blokuje BE-093, BE-094)

| Ticket | Opis | Złożoność | Agent |
|--------|------|-----------|-------|
| **BE-092** | `CustomDisposition` encja, repozytorium, `CustomDispositionService` | M | `backend-dev-expert` |

**Pliki do stworzenia:**
```
backend/app/src/main/java/com/contactcenter/
  domain/model/CustomDisposition.java
  domain/repository/CustomDispositionRepository.java
  domain/service/CustomDispositionService.java
  api/disposition/dto/AvailableDispositionDto.java
  api/disposition/dto/CustomDispositionDto.java
  api/disposition/dto/CreateCustomDispositionRequest.java
  api/disposition/dto/UpdateCustomDispositionRequest.java
```

**Klucz implementacji:** metoda `resolveForContact(contactId, tenantId)` — priorytet kampania → kolejka → system default. Nigdy nie zwraca pustej listy.

---

### Fala 3 — Endpointy REST (równolegle, obie zależą od BE-092)

| Ticket | Opis | Złożoność | Agent |
|--------|------|-----------|-------|
| **BE-093** | `CustomDispositionController` — CRUD dla supervisora | S | `backend-dev-expert` |
| **BE-094** | `GET /api/contacts/{contactId}/available-dispositions` — dla agenta | S | `backend-dev-expert` |

**BE-093 — nowy kontroler** (`api/disposition/CustomDispositionController.java`):
```
GET    /api/dispositions/campaigns/{campaignId}
POST   /api/dispositions/campaigns/{campaignId}
PUT    /api/dispositions/campaigns/{campaignId}/{id}
DELETE /api/dispositions/campaigns/{campaignId}/{id}
GET    /api/dispositions/queues/{queueId}
POST   /api/dispositions/queues/{queueId}
PUT    /api/dispositions/queues/{queueId}/{id}
DELETE /api/dispositions/queues/{queueId}/{id}
```
Autoryzacja: `@PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN')")`

**BE-094 — nowa metoda w ContactController.java** (linia ~314):
```
GET /api/contacts/{contactId}/available-dispositions
```
Autoryzacja: `hasAnyRole('AGENT','SUPERVISOR','ADMIN')`

**UWAGA SecurityConfig:** oba endpointy są chronione JWT — nie wymagają wpisu w `permitAll`. Nie ma potrzeby edycji SecurityConfig ani TenantFilter.

---

### Fala 4 — Warstwa danych Angular (blokuje FE-091, FE-092, FE-093)

| Ticket | Opis | Złożoność | Agent |
|--------|------|-----------|-------|
| **FE-090** | `CustomDispositionService` i modele TypeScript | S | `angular-frontend-expert` |

**Pliki do stworzenia:**
```
frontend/src/app/features/dispositions/
  models/custom-disposition.model.ts
  services/custom-disposition.service.ts
```

**Krytyczna uwaga — mapowanie tonów:** Backend zwraca `tone: 'positive'|'negative'|'neutral'|'warning'`. Istniejący frontend używa `DispositionTone = 'accent'|'success'|'warning'|'danger'|'violet'|'neutral'`. W FE-090 (lub FE-093) stwórz helper mapping:
```typescript
const TONE_MAP: Record<string, string> = {
  positive: 'success',
  negative: 'danger',
  warning: 'warning',
  neutral: 'neutral',
};
```

---

### Fala 5 — UI (równolegle, wszystkie zależą od FE-090)

| Ticket | Opis | Złożoność | Agent |
|--------|------|-----------|-------|
| **FE-091** | Panel dyspozycji w edycji kampanii (supervisor) | M | `angular-frontend-expert` |
| **FE-092** | Panel dyspozycji w ustawieniach kolejki (supervisor) | S | `angular-frontend-expert` |
| **FE-093** | Aktualizacja `DispositionPanelComponent` — dynamiczne ładowanie | M | `angular-frontend-expert` |

**FE-091 + FE-092 — shared komponent:**
```
frontend/src/app/shared/components/disposition-list-editor/
  disposition-list-editor.component.ts   ← używany przez FE-091 i FE-092
frontend/src/app/features/campaigns/components/campaign-dispositions/
  campaign-dispositions.component.ts     ← FE-091
```
Kolejka: jeśli brak widoku edycji kolejki w supervisorze — komponent `QueueDispositionsComponent` dodać do istniejącej strony szczegółów kolejki lub stworzyć nową zakładkę.

**FE-093 — modyfikacje istniejących plików:**
```
frontend/src/app/features/agent/models/disposition.model.ts
  → oznaczyć DISPOSITION_CODES jako @deprecated (fallback)
frontend/src/app/features/agent/components/disposition-panel/
  disposition-panel.component.ts
  → dodać sygnały: availableDispositions, dispositionsLoading, dispositionsError
  → wywołać CustomDispositionService.getAvailableDispositions(contactId)
  → skeleton loader podczas ładowania
  → fallback do DISPOSITION_CODES przy błędzie API (5xx)
```

---

## 4. Ryzyka i uwagi

| Ryzyko | Ocena | Mitygacja |
|--------|-------|-----------|
| Brak widoku edycji kolejki w frontendzie | Średnie | Sprawdzić routes supervisora przed startem FE-092; ewentualnie stworzyć nową podstronę |
| Rozbieżność `tone` frontend vs backend | Niskie | Mapa w FE-090 (patrz Fala 4) — zdefiniować raz, użyć wszędzie |
| `resolveForContact` wymaga joinów na `contact` → `campaign` → `custom_disposition` | Niskie | Logika w serwisie (nie SQL join) — 2-3 zapytania, akceptowalne dla ACW |
| Backwards compatibility panelu agenta | Niskie | Fallback do DISPOSITION_CODES przy błędzie — graceful degradation |
| Duplikat `dispositionCode` per zakres | Brak (obsługone) | DB constraint + `409 Conflict` z serwisu |

---

## 5. Kryteria akceptacji epica

- [ ] **DB-040:** Migracja V069 aplikuje się bez błędów; RLS izoluje tenantów
- [ ] **BE-092:** `resolveForContact` priorytet kampania → kolejka → system; nigdy pusta lista; testy ≥5 scenariuszy
- [ ] **BE-093:** 8 endpointów CRUD dla supervisora; `403` dla AGENT; OpenAPI docs
- [ ] **BE-094:** `GET /api/contacts/{contactId}/available-dispositions`; zwraca custom lub 6 systemowych; `404` dla nieistniejącego kontaktu
- [ ] **FE-090:** Serwis i modele zgodne z BE; mapowanie `tone` zaimplementowane
- [ ] **FE-091:** Panel dyspozycji w edycji kampanii; inline CRUD; komunikat o dyspozycjach systemowych gdy pusta lista
- [ ] **FE-092:** Analogiczny panel dla kolejki; współdzielony `DispositionListEditorComponent`
- [ ] **FE-093:** Panel agenta ładuje z API; skeleton loader; fallback; wybrany kod wysyłany do `PATCH /api/contacts/{id}/disposition`
- [ ] `mvn verify -pl app` przechodzi (wszystkie testy backendu)
- [ ] `npm run lint && npm test` przechodzą (frontend)

---

## 6. Kolejność egzekucji krok po kroku

```
1. DB-040    → agent: db-schema-architect
               wynik: V069__create_custom_disposition.sql
               
2. BE-092    → agent: backend-dev-expert
               zależy od: DB-040 ✅
               wynik: CustomDisposition encja + serwis + DTO
               
3. BE-093    → agent: backend-dev-expert  (równolegle z BE-094)
   BE-094    → agent: backend-dev-expert
               zależy od: BE-092 ✅
               
4. FE-090    → agent: angular-frontend-expert
               zależy od: BE-093 ✅ + BE-094 ✅
               wynik: CustomDispositionService + modele

5. FE-091    → agent: angular-frontend-expert  (równolegle z FE-092, FE-093)
   FE-092    → agent: angular-frontend-expert
   FE-093    → agent: angular-frontend-expert
               zależy od: FE-090 ✅
```

---

## 7. Komendy weryfikacji

```bash
# Backend po każdej fali:
cd backend && mvn verify -pl app

# Frontend po każdej fali:
cd frontend && npm run lint && npm test

# Uruchomienie lokalne:
docker compose --env-file .env.local-demo -f docker-compose.yml -f docker-compose.local-demo.yml up -d
cd backend/app && mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd frontend && npm start
```

---

## 8. Następny krok

```
Wykonaj: DB-040 — migracja V069
Komenda: /execute-ticket DB-040
Agent:   db-schema-architect
```
