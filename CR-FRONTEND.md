# CR-FRONTEND.md – Code Review Frontend

---

## Review: FE-027 Manual Dialer Panel (ManualCampaignPanelComponent, DialerService, AgentDesktopComponent) — 2026-04-08

### Bugs / Critical Issues

**[manual-campaign-panel.component.ts:53–83] Polling timer nie jest zatrzymywany gdy komponent jest niszczony — potencjalny memory leak przy szybkiej nawigacji**

`timer(0, POLL_INTERVAL_MS).pipe(switchMap(...), takeUntilDestroyed(this.destroyRef))` — `takeUntilDestroyed` poprawnie anuluje subskrypcję przy niszczeniu. Jednak `switchMap` po każdym tyknięciu timera tworzy wewnętrzną subskrypcję HTTP. Jeśli podczas trwającego zapytania HTTP (np. powolne API) nastąpi destroy komponentu, `takeUntilDestroyed` zakończy zewnętrzną Observable, ale wewnętrzne Observable z `switchMap` może dokończyć. Dodatkowo, `loading.set(true)` jest wywoływane wewnątrz `switchMap` przed zapytaniem, ale `loading.set(false)` jest w `subscribe` — po `takeUntilDestroyed`. Jeśli komponent zostanie zniszczony w trakcie oczekiwania na HTTP, `loading.set(false)` nigdy nie zostanie wywołane (sygnał na zniszczonym komponencie). W praktyce Angular ignoruje aktualizacje sygnałów zniszczonych komponentów, ale jest to nieoczywiste. Zalecane dodanie `finalize(() => this.loading.set(false))` wewnątrz pipeline `switchMap`, przed `catchError`.

---

**[manual-campaign-panel.component.ts:86–112] Duplikacja logiki `refresh` vs timer — DRY violation i różne ścieżki obsługi błędów**

Metoda `refresh()` (linie 86–112) jest niemal identyczna z logiką wewnątrz `switchMap` w `ngOnInit` (linie 56–83). Jedyna różnica to brak `this.error.set(null)` resetowania błędu w `refresh` przed `loading.set(true)` — co jest niespójnością: po błędzie, ręczne kliknięcie "Odśwież" nie czyści komunikatu błędu przed wywołaniem API. Należy wyodrębnić metodę prywatną `private loadCampaigns()` i używać jej w obu miejscach.

---

### Security Concerns

_None identified._

### Architecture / Pattern Violations

**[dialer.service.ts] `DialerService` zdefiniowany w `supervisor/services/` — błędna lokalizacja**

`DialerService` jest serwisem używanym przez komponent agenta (`ManualCampaignPanelComponent` w `agent/components/`), ale plik leży w `supervisor/services/dialer.service.ts`. Serwis dotyczy funkcjonalności agenta (manualny dialer), nie supervisora. Prawidłowa lokalizacja: `agent/services/dialer.service.ts` lub `core/services/dialer.service.ts` (jeśli używany przez obie role). Obecne umiejscowienie narusza zasadę organizacji kodu per-feature i utrudni przyszłą nawigację po projekcie.

---

**[manual-campaign-panel.component.ts:35] `imports: []` — komponent nie importuje żadnych modułów Angular**

`ManualCampaignPanelComponent` używa w szablonie `@for`, `@if`, interpolacji, bindingów `[class.*]`, `[attr.*]`, `[disabled]` — wszystkie są częścią składni szablonów Angular i nie wymagają importów. Jednak `[disabled]` w Angular 17+ dla `<button>` może wymagać `CommonModule` lub `NgOptimizedImage` w zależności od wersji. Warto upewnić się, że brak importów jest faktycznie prawidłowy dla Angular 21 standalone (nie jest to błąd krytyczny, ale warto zweryfikować).

---

**[manual-campaign-panel.component.ts:122] Brak ochrony przed concurrent calls — user może kliknąć wiele razy przed odpowiedzią API**

```typescript
if (this.callingRecordId() !== null) return;
```

Guard `callingRecordId !== null` zapobiega jednoczesnemu wywołaniu dla **dowolnego** rekordu, ale sygnał jest ustawiany dopiero w następnej linii (`this.callingRecordId.set(record.recordId)`). W teorii między sprawdzeniem a ustawieniem (mimo że synchroniczne w JS) istnieje okno, gdyby Angular change detection uruchomił template re-render. W praktyce JS jest single-threaded i `callRecord` jest synchroniczne do linii `this.callingRecordId.set(...)`, więc race condition jest niemożliwy. Jednak wyłączenie przycisku w szablonie `[disabled]="!isAvailable() || callingRecordId() !== null"` jest prawidłowe i stanowi warstwę UI guard.

---

### Improvements & Suggestions

**[manual-campaign-panel.component.html:7–9] Brak polskich znaków diakrytycznych w komunikatach użytkownika**

```html
<span ... aria-label="{{ totalPending() }} rekordow do wydzwonienia">
```
```
Zmien status na Dostepny aby dzwonic
```

Komunikaty użytkownika w HTML zawierają słowa bez polskich diakrytyk: "rekordow" (powinno być "rekordów"), "wydzwonienia" ("wydzwonienia" — poprawne), "Zmien" ("Zmień"), "Dostepny" ("Dostępny"), "dzwonic" ("dzwonić"). W pliku `.ts` (linie 150–155): "Nie udalo sie pobrac kampanii." → "Nie udało się pobrać kampanii.", "polaczenia" → "połączenia" itp. Brak diakrytyk był wcześniej zgłoszony jako recurring anti-pattern (CR-FRONTEND 2026-03-20, pozycja #17).

**[manual-campaign-panel.component.ts:149–151] Komunikat sukcesu nie jest przycinany — może wyświetlić "Inicjowanie polaczenia do   ()"**

```typescript
`Inicjowanie polaczenia do ${record.firstName ?? ''} ${record.lastName ?? ''} (${record.phone})`.trim()
```

`.trim()` usuwa spacje tylko z początku i końca stringa. Gdy `firstName` i `lastName` są `null/undefined`, rezultat to `"Inicjowanie polaczenia do   (phone)"` (z podwójną spacją między "do" i "(phone)"). Należy zbudować wyświetlaną nazwę warunkow: `const name = [record.firstName, record.lastName].filter(Boolean).join(' ') || record.phone`.

**[agent-desktop.component.ts:124–135] `untracked` w `twilioDeviceEffect` — poprawna praktyka, ale wartościowy komentarz**

Użycie `untracked(() => this.softphoneService.twilioDeviceReady())` zapobiega circular tracking (efekt śledzi `currentStatus` i `twilioDeviceReady` jednocześnie, co mogłoby powodować podwójne wywołania). To jest prawidłowy i nieoczywisty wzorzec Angular signals — komentarz w kodzie dobrze to wyjaśnia.

**[manual-campaign-panel.component.html:67] `trackByCampaignId` wywołany z argumentami `($index, row)`**

```html
@for (row of campaignRows(); track trackByCampaignId($index, row))
```

W Angular 17+ `@for` z `track` przyjmuje wyrażenie trackBy jako `track <wyrażenie>`. Użycie `trackByCampaignId($index, row)` wywołuje funkcję dla każdego elementu, co jest poprawne. Jednak konwencja w projekcie (sprawdzone w `AgentDesktopComponent`: `trackByTabId`) używa `(_i, tab) => tab.id` jako arrow function bez osobnej nazwanej metody. Styl z dedykowaną protected readonly function jest bardziej czytelny — brak problemu.

---

### Positive Observations

- **`ChangeDetectionStrategy.OnPush`** na `ManualCampaignPanelComponent` — zgodne z konwencją projektu i dobrą praktyką wydajnościową.
- **`takeUntilDestroyed(this.destroyRef)`** konsekwentnie używane dla wszystkich subskrypcji — brak memory leaków z zapomnianych subskrypcji.
- **Zachowanie stanu `expanded` przy odświeżeniu** (linie 74–79) — przemyślana UX: lista nie zwija się po każdym poll, co byłoby irytujące dla agenta pracującego z listą.
- **Optimistic update** przy `callRecord` (linie 136–147) — rekord znika z listy natychmiast po kliknięciu, nie czekając na potwierdzenie z backendu, co poprawia responsywność UI.
- **Obsługa błędów HTTP ze statusami 409 i 404** z dedykowanymi komunikatami — agent dostaje zrozumiały feedback zamiast generycznego "coś poszło nie tak".
- **Skeleton loading** dla pierwszego ładowania (`loading() && campaignRows().length === 0`) — profesjonalne UX, nie blankuje ekranu.
- **`aria-label`, `aria-expanded`, `aria-controls`, `aria-busy`, `role="list"`, `role="status"`, `role="alert"`** — kompletna dostępność WCAG AA na komponencie.
- **`DialerService` jako `providedIn: 'root'`** singleton — prawidłowe dla serwisu HTTP bez stanu.

### Summary

Komponent `ManualCampaignPanelComponent` jest dobrze napisany pod kątem UX, dostępności i zarządzania subskrypcjami. Główne zastrzeżenia: duplikacja logiki ładowania (DRY), błędna lokalizacja `DialerService` w module `supervisor/` zamiast `agent/`, brak polskich diakrytyk w komunikatach (recurring pattern) oraz drobna niespójność w obsłudze `loading` przy destroy. Brak krytycznych bugów w samym komponencie.

**Ocena: 3.5/5** — solidny komponent agenta z drobnymi uchybieniami organizacyjnymi i UX.

---

**Data:** 2026-03-20
**Reviewer:** Senior Code Reviewer (AI)
**Zakres:** FE-011 (Panel profilu klienta), FE-017 (Disposition Panel) + weryfikacja poprzednich uwag

---

## Weryfikacja poprzednich uwag CR (review z 2026-03-17)

| # | Uwaga | Status | Komentarz |
|---|-------|--------|-----------|
| 1 | Access token w `localStorage` — XSS | **Otwarta** | `token.service.ts` nadal pisze access token do `localStorage`. Nie naprawione. |
| 2 | Module-level mutable state w `authInterceptor` | **Otwarta** | `isRefreshing` i `refreshTokenSubject` nadal jako zmienne modułowe. Nie naprawione. |
| 3 | Hardcoded tenant UUIDs w login form | **Naprawiona** | `login.component.html` korzysta teraz z dynamicznego `PublicTenantService`. Opcje `<option>` z UUID nie istnieją. |
| 4 | Hard-coded `size: 1000` w TenantListComponent | **Otwarta** | Nadal obecny. |
| 5 | `takeUntilDestroyed` przed `catchError` — kolejność operatorów | **Otwarta** | Wzorzec nie zmieniony w `user-list.component.ts`. |
| 6 | Stub routes ładują zły komponent | **Częściowo naprawiona** | W `supervisor.routes.ts` route `customers` ładuje teraz `CustomerListComponent`. Pozostałe (`queues`, `campaigns`, `reports`, `settings`) nadal prowadzą do dashboardu — brak TODO. |
| 7 | `AdminMetricsService` `shareReplay({ refCount: false })` + bare subscribe | **Otwarta** | Bez zmian. |
| 8 | Native `document.addEventListener` w komponentach | **Otwarta** | W `UserFormComponent` i modalach nadal używany bezpośredni `document.addEventListener`. |
| 9 | `logout()` fire-and-forget bez komentarza | **Otwarta** | Brak komentarza dokumentującego zamierzone zachowanie. |
| 10 | `filteredSkills` getter zamiast `computed()` | **Naprawiona** | Zamienione na `computed()` w `user-form.component.ts` w sesji 2026-03-20. |
| 11 | `isAuthenticated$()` misleading name | **Otwarta** | Nadal istnieje. |
| 12 | `hasError` flag zamiast RxJS flow | **Otwarta** | Bez zmian. |
| 13 | Module-level `nextId` w `NotificationService` | **Otwarta** | Bez zmian. |
| 14 | `window.innerWidth` w `AppShellComponent` — SSR unsafe | **Otwarta** | Bez zmian. |
| 15 | Async validator na każde keystroke | **Otwarta** | Bez zmian. |
| 16 | Dwa kroki formularza logowania zawsze w DOM | **Otwarta** | Oba kroki nadal renderowane jednocześnie. |
| 17 | Brak polskich znaków w komunikatach | **Naprawiona w CR** | Naprawione przez reviewera w `user-list.component.ts` podczas poprzedniej sesji (nie weryfikowane w tej). W nowym kodzie brak znaków wykryty i naprawiony w tej sesji. |
| 18 | `agentUtilizationPercent()` wywoływane 4x per row | **Naprawiona** | Zastąpione przez `tenantUtilizationMap` (computed Map) w sesji 2026-03-20. |
| 19 | Mylący komentarz w `sidenav.component.html` | **Otwarta** | Bez zmian. |
| 20 | `ForbiddenComponent.goBack()` nawiguje do `/login` | **Otwarta** | Bez zmian. |
| 21 | Brak testów jednostkowych | **Częściowo naprawiona** | Dodano `customer-lookup.service.spec.ts` (8 przypadków, dobra jakość). Pozostałe komponenty bez testów. |

---

## Nowe uwagi – FE-017 (Disposition Panel)

### Krytyczne (blokujące release)

**[disposition-panel.component.ts:49–68] Memory leak: `setInterval` bez `clearInterval` przy destroy komponentu**

Oryginalna implementacja deklarowała `ngOnInit` (uruchamia timer) i `stopAcwTimer()`, ale brakowało `ngOnDestroy` wywołującego `stopAcwTimer()`. Gdyby Angular zniszczył komponent (nawigacja, zamknięcie zakładki) bez wcześniejszego kliknięcia "Zapisz", interwał pozostałby aktywny w tle, co powoduje:
- aktualizacje zniszczonego sygnału `acwSeconds` — ostrzeżenia ExpressionChanged w dev,
- utrzymanie referencji do komponentu przez GC uniemożliwiając garbage collection,
- w ekstremalnych przypadkach (szybkie otwieranie/zamykanie kart) narastające timery.

**Naprawione w tej sesji:** Dodano `implements OnDestroy` i `ngOnDestroy(): void { this.stopAcwTimer(); }`.

---

### Ważne (wymagają poprawy przed merge)

**✅ NAPRAWIONE (2026-03-20) — [disposition-panel.component.ts:100–101] `takeUntilDestroyed` przed `catchError` — nieoczekiwane zachowanie przy destroy podczas zapisu**

```typescript
.pipe(
  takeUntilDestroyed(this.destroyRef),   // linia 95
  catchError(() => { ... }),              // linia 96
)
```

Jeśli komponent zostanie zniszczony (np. admin siłowo zamknie zakładkę) podczas trwającego żądania HTTP `setDisposition`, `takeUntilDestroyed` zakończy strumień przed `catchError`. W efekcie `isSaving` pozostanie `true` na zniszczonym komponencie (niegroźne) ale `startAcwTimer()` w catchError nie zostanie wywołany — timer nie zostanie wznowiony po ewentualnym błędzie sieci. Problem ujawni się szczególnie jeśli komponent jest re-montowany bez pełnego cyklu lifecycle (np. `@if` switch).

**Sugestia:** Przenieść `catchError` przed `takeUntilDestroyed`:
```typescript
.pipe(
  catchError(() => {
    this.notifications.error('...');
    this.isSaving.set(false);
    this.startAcwTimer();
    return EMPTY;
  }),
  takeUntilDestroyed(this.destroyRef),
)
```

**✅ NAPRAWIONE (2026-03-20) — [disposition-panel.component.html:1] Użycie `<dialog open>` zamiast `showModal()` — brak natywnej pułapki fokusa**

```html
<dialog class="disposition-dialog" open aria-modal="true" ...>
```

Atrybut `open` otwiera dialog jako **non-modal** — nie aktywuje natywnej pułapki fokusa przeglądarki, nie blokuje interakcji z tłem i nie ustawia `aria-modal` w sposób honorowany przez screen readery (np. NVDA/JAWS ignorują `aria-modal="true"` na non-modal dialogu). Użytkownik może Tab-em wydostać się z panelu i klikać przyciski zakładek podczas ACW.

Prawidłowe użycie wymaga referencji do elementu i wywołania `showModal()` z kodu TypeScript po zamontowaniu komponentu.

**Sugestia:**
```typescript
// W disposition-panel.component.ts
private readonly dialogRef = viewChild.required<ElementRef<HTMLDialogElement>>('dialogEl');

ngOnInit(): void {
  this.dialogRef().nativeElement.showModal();
  this.startAcwTimer();
}
```
```html
<!-- W szablonie -->
<dialog #dialogEl class="disposition-dialog" aria-labelledby="disposition-dialog-title">
```

**✅ NAPRAWIONE (2026-03-20) — [disposition-panel.component.ts:105] `statusService.changeStatus()` wywoływane fire-and-forget po zapisie**

```typescript
this.statusService.changeStatus('AVAILABLE');
this.notifications.success('...');
this.saved.emit();
```

`changeStatus()` jest asynchroniczna (HTTP PATCH + WebSocket publish). `saved.emit()` jest wywoływane natychmiast, przez co `AgentDesktopComponent.onDispositionSaved()` zamknie zakładkę WRAPPING **przed** potwierdzeniem zmiany statusu z serwera. Jeśli PATCH zakończy się błędem (np. chwilowy brak sieci), agent zobaczy toast z błędem zmiany statusu, ale zakładka jest już zamknięta — nie ma możliwości ponownego zapisu. Status pozostanie "BUSY" po stronie serwera.

**Sugestia:** Emitować `saved` dopiero po sukcesie `changeStatus`. Wymaga refaktoru `AgentStatusService.changeStatus()` na Observable-returning zamiast void-subscribe:
```typescript
this.statusService.changeStatus('AVAILABLE')
  .pipe(takeUntilDestroyed(this.destroyRef))
  .subscribe({
    next: () => { this.saved.emit(); },
    error: () => { /* status nie zmieniony, ale disposition zapisana */ }
  });
```

**✅ NAPRAWIONE (2026-03-20) — [contact.service.ts:6–18] `ContactResponse` jako interfejs zagnieżdżony w pliku serwisu — naruszenie separacji warstw**

`ContactResponse` jest zdefiniowany w pliku `contact.service.ts` zamiast w osobnym pliku modelu. Przy każdym imporcie `ContactResponse` w innych plikach importuje się razem z `ContactService`. W miarę rozrostu serwisu stanie się to źródłem cyklicznych zależności.

**Sugestia:** Przenieść `ContactResponse` i `SetDispositionRequest` do `models/contact.model.ts` (analog do istniejącego `disposition.model.ts`).

---

### Sugestie (nice-to-have)

**✅ NAPRAWIONE (2026-03-20) — [disposition-panel.component.html:39] `$any($event.target).value` zamiast typowanego handlera**

```html
(change)="onCodeChange($any($event.target).value)"
```

`$any()` wyłącza typowanie w szablonie. Lepsze podejście to rzutowanie w handlerze:
```typescript
protected onCodeChange(event: Event): void {
  this.selectedCode.set((event.target as HTMLSelectElement).value);
}
```

**✅ NAPRAWIONE (2026-03-20) — [disposition-panel.component.html:41] `aria-invalid` nie zmienia wizualnego stylu pola**

Selektor CSS `&[aria-invalid='true']` w SCSS ma `border-color: #bdbdbd` — identyczny z neutralnym stanem pola. Atrybut jest ustawiony poprawnie, ale nie daje użytkownikowi żadnego wizualnego sygnału błędu.

**Sugestia:** Zmienić na `border-color: #c62828` (czerwony, spójny z `.form-field__hint`).

**[disposition-panel.component.ts:41] `DISPOSITION_CODES` jako stała — brak wsparcia dla konfiguracji per-tenant**

Lista kodów dyspozycji jest hardcoded w bundlu. Różne tenantów mogą mieć różne kody.

**Sugestia (długoterminowa):** Pobrać kody z `GET /api/disposition-codes` przy inicjalizacji, z fallbackiem na DISPOSITION_CODES jako wartości domyślne.

---

## Review: FE-019 (Customer Detail) — 2026-03-21

### Pliki: `customer-detail.component.ts`, `customer-detail.component.html`, `customer-detail.component.scss`, `customer.service.ts`, `supervisor.routes.ts`

---

### Bugs / Critical Issues

**[customer-detail.component.ts:102–133] `loadContacts()` — bare subscribe bez `takeUntilDestroyed`**

`loadContacts()` jest publiczną metodą (wywoływaną też z paginacji), a jej pipe NIE zawiera `takeUntilDestroyed`. Dodano go tylko do łańcucha w `ngOnInit` (linia 115 — wewnątrz `loadContacts`, ale nie do wewnętrznego substrumienia na paginate calls). Sprawdzenie: pipe w `loadContacts()` ma `takeUntilDestroyed(this.destroyRef)` na linii 115. Jest to poprawne dla wywołania z `ngOnInit` przez switchMap, ale `loadContacts()` jest wywoływana bezpośrednio z `onContactsPrevPage()` (l. 138) i `onContactsNextPage()` (l. 143). Te bezpośrednie wywołania tworzą nowe subskrypcje z `takeUntilDestroyed` powiązanym z `destroyRef` — to zachowanie jest poprawne. Uwaga: jeśli komponent zostanie zniszczony **podczas trwającego** żądania HTTP inicjowanego przez przycisk paginacji, `takeUntilDestroyed` poprawnie przerwie strumień. Brak krytycznego błędu, ale warto dodać komentarz dokumentujący to zachowanie.

**[customer-detail.component.ts:54] `c.phone[0]` — brak guard na pustą tablicę**

```typescript
return full || c.phone[0] || c.email[0] || c.customerId;
```

`c.phone` i `c.email` są typowane jako `string[]`. Gdy obie tablice są puste i brak imienia/nazwiska, wynik to `c.customerId`. Samo w sobie poprawne. Jednak sprawdzenie `c.phone[0]` zwraca `undefined` gdy tablica jest pusta — w TypeScript `undefined` jest falsy, więc łańcuch działa. Brak rzeczywistego buga, ale kod jest mylący: sprawdzenie `c.phone.length > 0` byłoby bardziej czytelne i intencjonalne.

**[customer-detail.component.ts:125–132] `finalize` ustawia stan `'loaded'` nawet przy błędzie sieciowym**

```typescript
catchError(() => {
  this.notifications.error('...');
  return of({ content: [], totalElements: 0, ... });
}),
finalize(() => this.contactsLoadState.set('loaded')),
```

`catchError` zwraca `of(...)` — strumień kontynuuje normalnie, więc `finalize` wywoła `set('loaded')`. To jest zamierzone i poprawne. Brak buga. Pozytywna obserwacja: `contactsLoadState` nigdy nie pozostaje w stanie `'loading'` po błędzie.

Jednak uwaga: `contactsLoadState` nie ma stanu `'error'` analogicznego do `loadState`. W przypadku błędu HTTP użytkownik widzi toast i pustą tabelę bez informacji o błędzie — nie może odróżnić "brak wyników" od "błąd ładowania". Rozważ dodanie stanu `'error'` lub wyświetlenie osobnej informacji w sekcji historii kontaktów.

---

### Security Concerns

**[customer.service.ts:51] `getCustomerContacts` — brak max size guard**

```typescript
const httpParams = new HttpParams()
  .set('customerId', params.customerId)
  .set('page', params.page.toString())
  .set('size', params.size.toString());
```

`size` jest przekazywany bezpośrednio z parametrów bez limitowania. `CustomerDetailComponent` używa stałej `contactsPageSize = 10`, więc w aktualnym użyciu jest to bezpieczne. Jednak serwis jest `providedIn: 'root'` — każdy inny komponent mógłby wywołać `getCustomerContacts` z `size: 10000`. Warto dodać guard `Math.min(params.size, 100)` analogicznie do innych serwisów w projekcie.

---

### Architecture / Pattern Violations

**[supervisor.routes.ts:54–60] Brak guard `RoleGuard` na route `customers/:id`**

Trasa `customers/:id` nie deklaruje `data.roles` i nie ma `canActivate: [RoleGuard]`. Route `customers` (lista) — ta sama sytuacja. Oba routy dziedziczą ochronę z child routing, ale brak explicitnej deklaracji sprawia, że jest to niespójne z innymi routami w projekcie i utrudnia review bezpieczeństwa. Porównaj z routami w `admin` i `agent`, które explicite deklarują dozwolone role.

Sugestia:
```typescript
{
  path: 'customers/:id',
  data: { breadcrumb: 'Profil klienta', roles: ['SUPERVISOR', 'ADMIN'] },
  canActivate: [RoleGuard],
  loadComponent: ...
}
```

**[customer-detail.component.ts:17–18] Import `ContactResponse` z modułu agenta — naruszenie granicy warstw**

```typescript
import { ContactResponse } from '../../../../features/agent/models/contact.model';
```

`CustomerDetailComponent` jest komponentem supervisora, a importuje model z pakietu agenta. Tworzy to zależność między dwoma oddzielnymi obszarami funkcjonalnymi. `ContactResponse` jest modelem domenowym (historią kontaktów) — powinien być w wspólnym katalogu (`shared/models/` lub `core/models/contact.model.ts`), nie w `features/agent/`. Referencja do `features/agent/models` z modułu `features/supervisor` jest architektonicznie nieprawidłowa.

**[customer.service.ts:4] Import `environment` — bezpośredni dostęp do zmiennych środowiskowych w serwisie**

```typescript
import { environment } from '../../../../../../environments/environment';
```

Wzorzec jest spójny z pozostałymi serwisami w projekcie, więc nie jest nową regresją. Pozostaje jako otwarta uwaga z poprzednich CR — lepszym rozwiązaniem byłby `InjectionToken<string>` dla base URL API.

---

### Improvements & Suggestions

**[customer-detail.component.ts:243] `[title]` na przepełnionych notatkach — XSS-safe, ale nieprzyjazne mobilnie**

```html
<td class="contacts-table__notes" [title]="contact.notes ?? ''">
```

Tooltip (`title`) nie jest dostępny na urządzeniach dotykowych. Dla długich notatek rozważ rozwijany wiersz lub truncate z przyciskiem "Pokaż więcej" — szczególnie ważne w centrum kontaktowym gdzie notatki mogą być długie.

**[customer-detail.component.ts:158–167] `formatDuration` w klasie komponentu zamiast pipe**

`formatDuration` i `getChannelLabel`/`getStatusLabel` to funkcje prezentacyjne wywoływane w pętli `@for`. Przy zmianie danych Angular re-ewaluuje je dla każdego wiersza (OnPush łagodzi to dla niezmienionej referencji, ale przy paginacji tworzy nową tablicę). Rozważ przeniesienie do `@Pipe({ pure: true })` dla memoizacji przez Angulara.

**[customer-detail.component.ts:87] `active ? request.active() : true` — niepotrzebny operator trójkowy**

```typescript
.active(request.active() != null ? request.active() : true)
```
W `QueueService.createQueue` (analogia), ale w tym pliku warto zaznaczyć:
```typescript
.active(c.gdprConsent.marketing_consent)
```
Pole `marketing_consent` w `GdprConsent` jest `boolean | undefined`. W sekcji HTML renderowany jest badge "Nie" przy `false` **i** przy `undefined` (falsy). To jest prawdopodobnie zamierzone, ale warto byłoby jawnie sprawdzić `=== true` i `=== false`, aby nie mylić braku zgody (`undefined`) z explicite odmówioną zgodą (`false`).

**[customer-detail.component.scss:301] `.status-badge--wrap_up` — klasa CSS ze znakiem podkreślenia**

```scss
&--wrap_up {
```

Klasa CSS zawiera podkreślenie w nazwie modyfikatora (`--wrap_up`), co jest niespójne z konwencją BEM (`--wrap-up`). Klasa jest budowana dynamicznie w szablonie przez `'status-badge status-badge--' + contact.status.toLowerCase()`, co daje `wrap_up` dla statusu `WRAP_UP`. Nie jest to błąd funkcjonalny, ale CSS z podkreśleniami w nazwach klas jest niestandardowe.

**[customer-detail.component.ts:44–46] Kontekst `customerId` w sygnale — niepotrzebne powielenie stanu**

`customerId` jest przechowywany jako osobny sygnał, choć można go wyciągnąć z `customer()?.customerId`. Dwa źródła prawdy dla tego samego ID: sygnał `customerId` i `customer().customerId`. Rozbieżność jest niemożliwa w praktyce (ustawiają się razem), ale jest to niepotrzebna złożoność. Rozważ zastąpienie przez `computed(() => this.customer()?.customerId ?? '')`.

---

### Positive Observations

- **`OnPush` + `DestroyRef` + `takeUntilDestroyed`** — wzorzec zarządzania lifecycle wdrożony prawidłowo. `ngOnInit` z `switchMap` na `paramMap` automatycznie anuluje poprzednie żądania przy zmianie parametru trasy.
- **Stany ładowania** — cztery stany `LoadState` z dedykowanymi skeleton UI i error states. Skeleton dla tabeli kontaktów z animacją shimmer to dobra UX.
- **Paginacja** — obliczenia `contactsFirstIndex`/`contactsLastIndex` przez `computed()`. `aria-live="polite"` na informacji o stronie — poprawna dostępność.
- **Obsługa błędów 404** — odróżnienie `not-found` od `error` z różnymi komunikatami dla użytkownika jest dobrą praktyką.
- **`trackByContactId`** — zdefiniowany i używany w `@for`. Nie generuje ostrzeżeń.
- **ARIA w tabeli** — `scope="col"`, `role="region"` na wrapper, `aria-label` na paginacji, `aria-current="page"` na numerze strony. Solidna implementacja dostępności.
- **`formatDuration` obsługuje ujemny diff** — `if (diffSeconds < 0) return '—'` chroni przed niepoprawnymi danymi (endedAt przed startedAt).

### Summary

Solidny, produkcyjny komponent. Wzorce Angular (OnPush, signals, takeUntilDestroyed, computed) zastosowane konsekwentnie. Główne uwagi to: brak explicitnego `RoleGuard` na nowych trasach, architektoniczne naruszenie importu z `features/agent`, brak stanu `'error'` dla historii kontaktów i brak max-size guard w serwisie. Żaden problem nie jest blokujący release, ale naruszenie granicy modułów agenta/supervisora powinno być naprawione przed rozrostem kodu.

**Ocena: 4/5** — dobre wykonanie z kilkoma architektonicznymi usterkami do poprawy.

---

## Nowe uwagi – FE-011 (Panel profilu klienta)

### Krytyczne

**[customer-panel.component.ts:50–53] Brak zarządzania subskrypcją `lookupByPhone` — memory leak i aktualizacja po destroy**

Oryginalna implementacja:
```typescript
this.lookupService.lookupByPhone(phone).subscribe((result) => {
  this.profile.set(result);
  this.state.set(result ? 'known' : 'unknown');
});
```

Subskrypcja nie była w żaden sposób zarządzana: nie przez `takeUntilDestroyed`, nie przez ręczne przechowywanie i unsubscribe. Konsekwencje:
1. Jeśli użytkownik przeszedł między zakładkami (zmiana `cli`) zanim HTTP wrócił, obydwa żądania były w locie jednocześnie. Które zakończy się pierwsze, decydowało o stanie `profile` — klasyczny race condition; wyświetlany profil mógł nie odpowiadać aktualnemu numerowi.
2. Po zniszczeniu komponentu (zamknięcie kontaktu) odpowiedź mogła wciąż aktualizować sygnały na zniszczonym obiekcie.

**Naprawione w tej sesji:** Dodano `DestroyRef`, import `Subscription`, pole `lookupSub: Subscription | null`, anulowanie poprzedniego żądania przy każdej zmianie CLI oraz `takeUntilDestroyed` dla ochrony po destroy.

---

### Ważne

**✅ NAPRAWIONE (2026-03-20) — [customer-panel.component.ts:99–101] Nawigacja agenta do `/supervisor/customers` — naruszenie Role Guard**

```typescript
protected navigateToFullProfile(): void {
  const p = this.profile();
  if (p) {
    this.router.navigate(['/supervisor/customers', p.id]);
  }
}
```

`CustomerPanelComponent` jest montowany w `AgentDesktopComponent` — ekranie agenta. Agent (rola AGENT) nie ma dostępu do ścieżek `/supervisor/**` — są chronione przez `RoleGuard` z `data.roles: ['SUPERVISOR', 'ADMIN']`. Kliknięcie "Zobacz pełny profil" skończy się przekierowaniem do `/forbidden`.

Komponent nie sprawdza roli przed wyświetleniem przycisku — każdy agent zobaczy przycisk, który zawsze kończy się błędem 403.

**Sugestia:** Wstrzyknąć `AuthService` i owinąć przycisk w `@if`:
```html
@if (authService.currentRole() === 'SUPERVISOR' || authService.currentRole() === 'ADMIN') {
  <button ...>Zobacz pełny profil</button>
}
```
Lub: jeśli funkcja ma być dostępna dla agentów, dodać dedykowaną trasę agenta `/agent/customers/:id` z odpowiednim widokiem tylko do odczytu.

**✅ NAPRAWIONE (2026-03-20) — [customer-panel.component.ts:50] Brak obsługi błędu HTTP w `ngOnChanges` — błędy 5xx są połykane bez informacji dla agenta**

`CustomerLookupService.lookupByPhone()` obsługuje 404 (zwraca `null`) i loguje toast dla 5xx, ale zwraca `of(null)` dla wszystkich błędów. `CustomerPanelComponent` nie rozróżnia między "klient nie znaleziony" (404) a "błąd serwera" (5xx) — w obu przypadkach wyświetla stan `unknown`. Agent może myśleć, że klient nie istnieje, gdy w rzeczywistości backend jest niedostępny.

**Sugestia:** Dodać do modelu stan `'error'` i obsługiwać go osobno w szablonie, lub zmienić `lookupByPhone` by rzucał błąd zamiast zwracać `null` dla 5xx, a panel niech pokaże stan `'error'` z ikoną błędu.

---

### Sugestie

**[customer-panel.component.ts — brak] Brak `evict()` po `navigateToCreateProfile()`**

Gdy agent nawiguje do tworzenia profilu i wróci do zakładki agenta, panel nadal wyświetla stan `unknown` (z cache). Cache nie zostanie odświeżony dopóki TTL 5 minut nie wygaśnie. Nowy profil pojawi się z opóźnieniem.

**Sugestia:** Po sukcesie tworzenia profilu (w komponencie formularza klienta) wywołać `CustomerLookupService.evict(phone)`. Ewentualnie komponent mógłby nasłuchiwać eventu routera na powrót i wymuszać refresh.

**[customer-panel.component.html:69] `profile()!.firstName.charAt(0)` — nie-null assertion potencjalnie ryzykowna**

Wzorzec jest w bloku `@if (state() === 'known' && profile())` więc `profile()` nie jest `null` w tym momencie. Jednak TypeScript nie zawęża tutaj typu przez warunki `@if` w szablonie — stąd konieczność `!`. Jest to akceptowalne, ale warto to skomentować lub użyć `profile()?.firstName.charAt(0) ?? '?'` dla defensywności.

**[customer-lookup.service.ts — brak czyszczenia cache przy zmianie tenanta/wylogowaniu]**

`CustomerLookupService` jest `providedIn: 'root'` z Map-ową cache w pamięci. Przy wylogowaniu się agenta X i zalogowaniu agenta Y w tej samej przeglądarce (ten sam Angular DI), cache będzie zawierać dane z poprzedniej sesji. Jeśli agent Y obsługuje ten sam numer, zobaczy profil natychmiast z cache — bez weryfikacji aktualności danych.

**Sugestia:** Wywołać `this.cache.clear()` w `AuthService.logout()` lub nasłuchiwać na zdarzenie wylogowania w serwisie.

---

## Naprawione w trakcie review

| Sesja | Plik | Zmiana |
|-------|------|--------|
| CR (senior-reviewer) | `disposition-panel.component.ts` | Dodano `implements OnDestroy` i `ngOnDestroy(): void { this.stopAcwTimer(); }` — eliminuje memory leak setInterval |
| CR (senior-reviewer) | `customer-panel.component.ts` | Dodano `DestroyRef`, `Subscription`, pole `lookupSub`, anulowanie poprzedniego żądania przy zmianie CLI, `takeUntilDestroyed` — eliminuje race condition i memory leak |
| CR (senior-reviewer) | `disposition-panel.component.ts` | Poprawiono polskie znaki w komunikacie: "Nie udało się zapisać dyspozycji. Spróbuj ponownie." |
| CR (senior-reviewer) | `disposition-panel.component.ts` | Poprawiono: "Dyspozycja zapisana. Status zmieniony na Dostępny." |
| CR (senior-reviewer) | `disposition-panel.component.html` | Poprawiono polskie znaki w opcji select: "-- Wybierz dyspozycję --" oraz w hincie błędu |
| CR (senior-reviewer) | `disposition.model.ts` | Poprawiono polskie znaki we wszystkich etykietach: "Sprzedaż", "Błędny numer", "Zgłoszenie techniczne" |
| CR (senior-reviewer) | `agent-desktop.component.ts` | Poprawiono polskie znaki w komunikatach limitów zakładek |
| CR (senior-reviewer) | `agent-status.service.ts` | Poprawiono: "Nie udało się zmienić statusu. Spróbuj ponownie." |
| CR (senior-reviewer) | `customer-lookup.service.ts` | Poprawiono: "Nie udało się pobrać danych klienta." |
| Poprawki (angular-fe) | `disposition-panel.component.html/ts` | `<dialog open>` → `showModal()` z `viewChild` — natywna pułapka fokusa, WCAG AA |
| Poprawki (angular-fe) | `disposition-panel.component.ts` | `catchError` przeniesiony przed `takeUntilDestroyed` — timer ACW wznawia się po błędzie |
| Poprawki (angular-fe) | `agent-status.service.ts` + `disposition-panel.component.ts` | `changeStatus()` zwraca `Observable<void>`; `saved.emit()` dopiero po potwierdzeniu z serwera |
| Poprawki (angular-fe) | `models/contact.model.ts` (nowy) | `ContactResponse` i `SetDispositionRequest` przeniesione z `contact.service.ts` do osobnego pliku modelu |
| Poprawki (angular-fe) | `customer-panel.component.html/ts` | Przycisk "Zobacz pełny profil" ukryty dla roli AGENT (`@if` + `AuthService`) |
| Poprawki (angular-fe) | `customer-panel.component.ts/html` + `customer-lookup.service.ts` | Stan `'error'` dla błędów 5xx — rozróżnienie 404 vs błąd serwera |
| Poprawki (angular-fe) | `disposition-panel.component.html/ts` | Usunięto `$any($event.target)` — typowany handler `(event.target as HTMLSelectElement).value` |
| Poprawki (angular-fe) | `disposition-panel.component.scss` | `aria-invalid` kolor `#c62828` (czerwony) — wizualny sygnał błędu |

---

## Pozytywne aspekty

1. **`DispositionPanelComponent` — prawidłowy double-submit guard.** `canSave = computed(() => selectedCode().length > 0 && !isSaving())` i `[disabled]="!canSave()"` razem zapobiegają wielokrotnym submitom. Wzorzec `isSaving` poprawnie zresetowany zarówno w gałęzi sukcesu jak i błędu.

2. **ACW timer zaimplementowany przez `setInterval` + `ReturnType<typeof setInterval>` — poprawny typ.** Użycie `ReturnType<typeof setInterval>` zamiast `number` jest przenośne między środowiskami Node/browser.

3. **`ContactTabStore` — czysta architektura signal store.** Cały stan zmutowany wyłącznie przez jawne metody (`openTab`, `closeTab`, `markAsWrapping`). Limity zakładek weryfikowane przez `checkLimits()` przed każdą operacją. Brak mutacji bezpośrednich z zewnątrz.

4. **`softphoneEndedEffect` poprawnie obsługuje przejście ENDED → WRAPPING.** Effect jest zarejestrowany jako pole klasy (nie w konstruktorze, nie w `ngOnInit`), co jest preferowanym wzorcem Angular 21. Warunek `t.status !== 'WRAPPING'` zapobiega wielokrotnemu przejściu tej samej zakładki.

5. **`CustomerLookupService` — in-memory cache z TTL 5 minut i metodą `evict()`.** Cache poprawnie obsługuje `null` (nieznany numer), 5-minutowy TTL, trimowanie whitespace z CLI. Metoda `evict()` umożliwia odświeżenie po akcjach CUD na kliencie.

6. **`customer-lookup.service.spec.ts` — solidny zestaw testów.** 8 przypadków pokrywających: sukces, 404, cache hit, cache null, TTL wygaśnięcie, evict, pusty CLI, trimowanie. Testy używają `HttpTestingController` z `provideHttpClientTesting()` (poprawny wzorzec Angular 18+, bez Jasmine/Karma). To pierwszy plik testowy z prawdziwą wartością w projekcie.

7. **`DispositionPanelComponent` — poprawne ARIA na timerze ACW.** `aria-live="polite" aria-atomic="true"` na kontenerze timera i `[attr.aria-label]="'Czas po kontakcie: ' + formattedAcwTime()"` na samym timerze — screen reader ogłosi zmianę co każdą sekundę bez przerywania aktywnego odczytu.

8. **`AgentDesktopComponent` — `ngOnDestroy` rozłącza WebSocket.** `this.ws.disconnect()` wywoływane przy destroy komponentu zapobiega wiszącym połączeniom STOMP po opuszczeniu strony agenta.

9. **Obsługa błędów w `save()` poprawnie wznawia timer.** Po błędzie HTTP `startAcwTimer()` jest wywoływany ponownie — agent widzi aktualny czas ACW i może ponowić zapis bez utraty kontekstu.

---

## Podsumowanie

**FE-017 (Disposition Panel): ~~3.5/5~~ → 4.5/5** (po poprawkach 2026-03-20)
Solidna implementacja z dobrym UX. Wszystkie krytyczne i ważne problemy naprawione: memory leak timera, kolejność operatorów, `showModal()`, fire-and-forget status, separacja modeli. Otwarta jedynie sugestia długoterminowa (kody per-tenant z API).

**FE-011 (Panel profilu klienta): ~~3.5/5~~ → 4.5/5** (po poprawkach 2026-03-20)
Poprawna architektura stanu, solidny cache z TTL, dobra jakość testów. Wszystkie ważne problemy naprawione: race condition subskrypcji, Role Guard dla przycisku, stan `'error'` dla 5xx. Otwarte jedynie sugestie nice-to-have (evict po tworzeniu profilu, czyszczenie cache przy wylogowaniu).

---

## Review: FE-024 (Panel konfiguracji kolejek) — 2026-03-21

### Pliki: `queue.model.ts`, `queue.service.ts`, `queue-list.component.ts`, `queue-list.component.html`, `queue-form.component.ts`, `queue-form.component.html`, `queue-delete-modal.component.ts`, `supervisor.routes.ts`

---

### Bugs / Critical Issues

**[queue-list.component.ts:77] `takeUntilDestroyed` w metodzie `loadQueues()` — subskrypcja tworzona poza kontekstem injection**

`loadQueues()` jest metodą publiczną wywoływaną z `ngOnInit`, `onFormSaved()`, `onDeleteConfirmed()`, `onNextPage()`, `onPrevPage()` i `toggleActive()`. Każde wywołanie tworzy nową subskrypcję z `takeUntilDestroyed(this.destroyRef)`. Angular wymaga, aby `takeUntilDestroyed` był wywoływany w kontekście injection (konstruktor lub pole klasy). Wywołanie wewnątrz metody instancji działa poprawnie **wyłącznie gdy** `DestroyRef` jest wstrzyknięty przez `inject()` w polu klasy — co ma tutaj miejsce. Technicznie działa, ale jest wzorcem ryzykownym: jeśli `loadQueues()` zostałaby przeniesiona do serwisu lub wywołana przed gotowością DI, rzuci `NG0203`. Dodatkowo, gdy użytkownik szybko przewija strony (wiele wywołań `loadQueues()` z debounce 0), wiele równoległych żądań HTTP będzie aktywnych jednocześnie, gdyż brak operatora `switchMap` czy `exhaustMap` — każde nowe wywołanie tworzy nową subskrypcję, nie anulując poprzednich.

Sugestia: zamiast bezpośredniego `this.queueService.getQueues(...).pipe(takeUntilDestroyed(...)).subscribe(...)` w każdym wywołaniu — użyć `Subject<void>` z `switchMap`, analogicznie do wzorca stosowanego w `customer-detail.component.ts`.

---

**[queue-list.component.ts:148–149] `onDeleteConfirmed()` wywołuje `closeDeleteModal()` w `finalize` przed sprawdzeniem wyniku — zamknięcie modala przy błędzie może dezorientować użytkownika**

```typescript
finalize(() => {
  this.deleting.set(false);
  this.closeDeleteModal();   // zamknięcie ZAWSZE, nawet przy błędzie
}),
```

Modal zamykany jest w `finalize()` — zarówno po sukcesie jak i po błędzie. W gałęzi błędu `catchError` wyświetla toast i zwraca `of(null)`, następnie `finalize` zamyka modal. Użytkownik widzi toast z błędem, ale modal znika — nie ma możliwości ponowienia próby bez ponownego otwarcia modala. To zachowanie jest niespójne z wzorcem `queue-form.component.ts`, gdzie modal zamykany jest wyłącznie po sukcesie (`saved.emit()` wewnątrz `next`).

Sugestia: przenieść `this.closeDeleteModal()` do bloku `next` po sukcesie, a w błędzie pozostawić modal otwarty z widocznym komunikatem.

---

### Security Concerns

**[supervisor.routes.ts:29–35] Brak `canActivate: [roleGuard]` na trasie `/queues` — dostęp dla wszystkich zalogowanych użytkowników**

```typescript
{
  path: 'queues',
  data: { breadcrumb: 'Kolejki' },
  loadComponent: () => import('./pages/queues/queue-list/queue-list.component')...
},
```

Trasa `queues` nie ma `canActivate: [roleGuard]` z deklaracją `data.roles`. Porównaj: trasa `customers/:id` (linia 55) explicite deklaruje `data: { roles: ['SUPERVISOR', 'ADMIN'] }` i `canActivate: [roleGuard]`. Bez `roleGuard` każdy zalogowany użytkownik (w tym AGENT) może przejść bezpośrednio pod adres `/supervisor/queues` — co jest chronione jedynie przez ochronę backendu (SUPERVISOR/ADMIN w kontrolerze), ale po stronie UI AGENT będzie widzieć panel zarządzania kolejkami i otrzymywać błędy HTTP 403 przy każdej akcji, zamiast być zredirektowanym do `/forbidden`.

Sugestia:
```typescript
{
  path: 'queues',
  data: { breadcrumb: 'Kolejki', roles: ['SUPERVISOR', 'ADMIN'] },
  canActivate: [roleGuard],
  loadComponent: () => import('./pages/queues/queue-list/queue-list.component')...
},
```

---

**[queue.service.ts:6] Import `PagedResponse` z `models/user.model` — naruszenie granicy modułów**

```typescript
import { PagedResponse } from '../models/user.model';
```

`PagedResponse` jest typem generycznym, który nie należy do modelu użytkownika — jest odpowiedzią paginacji wspólną dla całego projektu. Import z `user.model` tworzy nielogiczną zależność: `QueueService` importuje typ ze sceny `user`. Jeśli `user.model.ts` zostałby zrefaktorowany lub przeniesiony, `queue.service.ts` przestałby się kompilować.

Analogiczny problem został wcześniej zidentyfikowany dla `ContactResponse` w CR FE-019 (naruszenie granicy między `features/agent` a `features/supervisor`).

Sugestia: przenieść `PagedResponse<T>` do `core/models/paged-response.model.ts` i importować stamtąd. Wszystkie serwisy w projekcie powinny używać tej samej lokalizacji.

---

### Architecture / Pattern Violations

**[queue-form.component.ts:129] `setTimeout(() => this.showSkillDropdown.set(false), 150)` w `onSkillInputBlur` — niebezpieczny hack, ryzyko przy szybkim destroy**

```typescript
onSkillInputBlur(): void {
  setTimeout(() => this.showSkillDropdown.set(false), 150);
}
```

`setTimeout` z opóźnieniem 150ms jest klasycznym hackiem pozwalającym na przechwycenie kliknięcia w dropdown przed jego ukryciem. Problem: jeśli komponent zostanie zniszczony w ciągu tych 150ms (np. nagłe zamknięcie modala), callback nadal się wykona i spróbuje wywołać `this.showSkillDropdown.set(false)` na zniszczonym komponencie. W Angular 21 z sygnałami powoduje to ostrzeżenie `ExpressionChangedAfterItHasBeenCheckedError` lub błąd w trybie ścisłym.

Sugestia: przechować referencję `private blurTimer: ReturnType<typeof setTimeout> | null = null` i wyczyścić ją w `ngOnDestroy()`. Ewentualnie zastąpić `setTimeout` przez obserwowanie `focusout` z `relatedTarget` — sprawdzenie czy fokus przeszedł poza kontener skill.

---

**[queue-form.component.html:5] Obsługa kliknięcia na dialog backdrop przez porównanie referencji w szablonie — niebezpieczny wzorzec**

```html
(click)="$event.target === dialogEl ? onCancel() : null"
```

`dialogEl` jest zmienną szablonową odnoszącą się do elementu `<dialog>`. Porównanie `$event.target === dialogEl` zakłada, że kliknięcie na backdrop trafi bezpośrednio na element `<dialog>` (nie na dziecko). Jest to poprawne dla natywnego `<dialog>` i kliknięcia w obszar backdrop — przeglądarka dostarcza click event z `target === dialog`. Jednak `dialogEl` w szablonie to zmienna blokowa, a nie bezpośrednie odwołanie do `nativeElement`. W Angular `#dialogEl` w szablonie jest referencją do `ElementRef`, natomiast `$event.target` to `HTMLElement`. To porównanie `HTMLElement === ElementRef` zawsze zwróci `false`.

Jest to błąd logiczny — zamknięcie kliknięciem w backdrop nigdy nie zadziała. Ten sam wzorzec występuje w `queue-delete-modal.component.html` (linia 6) i `queue-form.component.html` (linia 5).

Sugestia: porównywać `$event.target === $event.currentTarget` (kliknięcie bezpośrednio na dialog, nie na dziecko):
```html
(click)="$event.target === $event.currentTarget ? onCancel() : null"
```
Lub wyciągnąć logikę do handlera w komponencie TypeScript.

---

**[queue-form.component.ts:87–107] Brak obsługi błędu w `loadOptions()` — `loadingOptions` pozostaje `true` przy błędzie obu żądań**

```typescript
forkJoin({
  strategies: this.queueService.getRoutingStrategies().pipe(catchError(() => of<string[]>([]))),
  skills: this.userService.getSkills().pipe(catchError(() => of<string[]>([]))),
})
  .pipe(takeUntilDestroyed(this.destroyRef))
  .subscribe(({ strategies, skills }) => {
    this.routingStrategies.set(strategies);
    this.availableSkills.set(skills);
    this.loadingOptions.set(false);   // ustawiane tylko w .subscribe next
```

Gdy oba `catchError` zwracają `of([])`, `forkJoin` nadal wyemituje wartość i `loadingOptions.set(false)` zostanie wywołane. Scenariusz problematyczny: gdy serwis sieciowy rzuci błąd, który nie zostanie przechwycony przez `catchError` wewnątrz `forkJoin` (np. błąd parsowania JSON przed HTTP), cały `forkJoin` zakończy się błędem, a `loadingOptions` pozostanie `true` — formularz będzie wyglądał jak stale wczytywany. Brak `finalize(() => this.loadingOptions.set(false))` na zewnętrznym pipe.

Sugestia: dodać `finalize(() => this.loadingOptions.set(false))` po `takeUntilDestroyed`:
```typescript
.pipe(
  takeUntilDestroyed(this.destroyRef),
  finalize(() => this.loadingOptions.set(false))
)
```

---

### Improvements & Suggestions

**[queue.model.ts:13–17] `description` brak w `CreateQueueRequest` — pole modelu `Queue` niedostępne przy tworzeniu**

```typescript
export interface Queue {
  description?: string;   // pole istnieje w modelu
}

export interface CreateQueueRequest {
  name: string;
  routingStrategy: string;
  requiredSkills?: string[];
  // brak description
}
```

`Queue` ma opcjonalne pole `description`, ale `CreateQueueRequest` go nie zawiera — użytkownik nie może ustawić opisu przy tworzeniu kolejki, tylko przy edycji (gdy `UpdateQueueRequest` zawiera `name?`). Brak `description` również w `UpdateQueueRequest`. Jeśli opis jest planowany, obie DTOs powinny go zawierać. Jeśli nie jest używany, pole powinno zostać usunięte z `Queue`.

**[queue-list.component.ts:193–194] `firstItemIndex` i `lastItemIndex` jako pola klasy przypisane do funkcji strzałkowych — niestandardowy wzorzec**

```typescript
readonly firstItemIndex = (): number => this.currentPage() * this.pageSize + 1;
readonly lastItemIndex = (): number => Math.min(...);
```

Pola przypisane do funkcji strzałkowych są wykonywane przy każdym wywołaniu bez memoizacji (inaczej niż `computed()`). Wzorzec mylący: wyglądają jak sygnały `signal()` przez `readonly`, ale są zwykłymi funkcjami. Warto zastąpić przez `computed()`:
```typescript
readonly firstItemIndex = computed(() => this.currentPage() * this.pageSize + 1);
readonly lastItemIndex = computed(() => Math.min(...));
```

**[queue-form.component.ts:75–77] Zbędna gałąź `if (!this.isEditMode())` dla `isActive`**

```typescript
if (!this.isEditMode()) {
  this.form.get('isActive')?.setValue(true);
}
```

`isActive` jest inicjalizowane jako `[true]` w `FormGroup` (linia 59). Warunek jest martwy kodem — nie zmienia stanu, który był już ustawiony przy tworzeniu formy. Można go usunąć.

**[queue-list.component.html:67] `aria-live="polite"` na elemencie `<table>` — nieprawidłowe użycie ARIA**

```html
<table class="queue-table" aria-live="polite">
```

`aria-live` na `<table>` może powodować nieoczekiwane zachowanie screen readerów, które mogą ogłaszać całą zawartość tabeli przy każdej zmianie. `aria-live` powinno być umieszczone na kontenerze wyświetlającym krótkie komunikaty statusu, nie na tabeli danych. Tabela jest renderowana jako całość po załadowaniu (`@if (!loading())`), więc ogłoszenie przez `aria-live` na tablicy spowoduje przeczytanie wszystkich wierszy naraz.

Sugestia: usunąć `aria-live` z `<table>` i przenieść na informację o wynikach (np. `Wyświetlanie X–Y z Z kolejek`), które już ma poprawny `aria-live="polite"` na pagination info.

**[queue-delete-modal.component.html:23] `autofocus` na przycisku "Anuluj" — dostępność**

```html
<button class="btn btn-cancel" type="button" (click)="onCancel()" autofocus>Anuluj</button>
```

`autofocus` na "Anuluj" zamiast na "Usuń" jest dobrą praktyką bezpieczeństwa (zapobiega przypadkowemu usunięciu przez Enter). Jednak w kontekście destruktywnej operacji WCAG zaleca, aby domyślny fokus był na akcji mniej destruktywnej — co tutaj jest spełnione. Pozytywna obserwacja, warto odnotować jako świadomy wybór.

---

### Positive Observations

- **`OnPush` i sygnały konsekwentnie zastosowane** w `QueueListComponent`, `QueueFormComponent` i `QueueDeleteModalComponent`. `signal()` i `computed()` (w `filteredSkills`) zamiast `BehaviorSubject`.
- **`takeUntilDestroyed(this.destroyRef)` we wszystkich subskrypcjach** — brak niezarządzanych subskrypcji. `DestroyRef` wstrzyknięty przez `inject()`, co jest poprawnym wzorcem Angular 21.
- **`forkJoin` do równoległego ładowania opcji** (`routingStrategies` + `skills`) zamiast sekwencyjnych wywołań — dobra praktyka wydajnościowa.
- **`showModal()` przez `ngAfterViewInit`** — oba modale (`QueueFormComponent`, `QueueDeleteModalComponent`) poprawnie używają `showModal()` z `viewChild`, aktywując natywną pułapkę fokusa. Wzorzec naprawiony w poprzednich CR jest tutaj zastosowany od razu.
- **Escape key przez `(document:keydown.escape)` w `host`** — zamiast ręcznego `document.addEventListener` (stary problem z user-form). Nowy kod nie powtarza błędu z poprzednich sesji.
- **`trackByQueueId` zdefiniowany i użyty** w `@for (queue of queues(); track queue.id)` — poprawna optymalizacja renderowania listy.
- **Skeleton loading z `aria-busy="true"`** i empty state z kontekstowym przyciskiem CTA — dobry UX.
- **ARIA na combobox skills** — `role="combobox"`, `aria-autocomplete="list"`, `aria-expanded`, `aria-controls` — poprawna implementacja wzorca dostępności dla pola z podpowiedziami.
- **`routingStrategy` walidowany przez formularz** — `Validators.required` i select z opcją disabled `value=""` zapewniają, że użytkownik musi wybrać strategię.
- **Lazy loading trasy `queues`** — komponent ładowany przez `loadComponent: () => import(...)` — spójne z innymi trasami supervisora.

---

### Summary

Panel kolejek jest solidną implementacją z dobrym wzorcem sygnałów i właściwym użyciem `showModal()`. Jeden błąd logiczny jest krytyczny: porównanie `$event.target === dialogEl` w szablonie (typ `HTMLElement` vs `ElementRef`) sprawia, że zamknięcie modala kliknięciem w backdrop nigdy nie działa. Brakujący `roleGuard` na trasie `queues` to luka bezpieczeństwa po stronie UI. Problem z wieloma równoległymi żądaniami HTTP (`loadQueues` bez `switchMap`) może powodować niespójność stanu przy szybkiej paginacji. Pozostałe uwagi to ulepszenia jakości i dostępności.

**Ocena: 3.5/5** — poprawny kod z jednym błędem logicznym (backdrop click), brakującym guard bezpieczeństwa i kilkoma wzorcami do dopracowania przed release.

## Review: queue-form.component.ts, queue-form.component.html, queue.model.ts — 2026-03-26

### Bugs / Critical Issues

_None identified._

### Security Concerns

_None identified._

### Architecture / Pattern Violations

_None identified._

### Improvements & Suggestions

**[queue-form.component.ts:74] `emailAddress` inicjalizowany jako `''` w trybie edycji zamiast `null` — niespójna semantyka z modelem**

```typescript
emailAddress: editQueue.emailAddress ?? '',
```

Gdy `editQueue.emailAddress` jest `null` (kolejka bez adresu email), formularz inicjalizuje pole jako pusty string `''`. Po edycji kolejki bez zmiany emailAddress, `onSubmit()` wyśle `emailAddress: null` (linia 200: `raw.emailAddress?.trim() || null`). Wynik poprawny. Jednak jeśli użytkownik otworzy formularz, nie zmieni nic i kliknie Zapisz — formularz wyśle `null` zamiast `undefined`. Zależy od implementacji backendu (PATCH), czy `null` jest rozumiane jako "wyczyść pole" vs "nie zmieniaj". Warto ujednolicić inicjalizację do `null`:
```typescript
emailAddress: editQueue.emailAddress ?? null,
```
Co wymaga zmiany inicjalizacji FormControl z `['', [...]]` na `[null, [...]]`.

**[queue-form.component.ts:62] `Validators.email` Angular nie obsługuje poprawnie adresów RFC 5322 z display name**

```typescript
emailAddress: ['', [Validators.email, Validators.maxLength(255)]],
```

`Validators.email` Angular stosuje uproszczoną walidację (sprawdza obecność `@` i strukturę domeny). Nie obsługuje jednak formatu `"Name <email@domain.com>"`. Jeśli użytkownik wklei adres w formacie z display name (co jest powszechne przy kopiowaniu z klientów email), Angular oznaczy pole jako invalid przez `Validators.email`. Jest to właściwe zachowanie — baza danych powinna przechowywać czysty adres email, nie RFC 5322 encoded. Warto jednak dodać hint w szablonie informujący, że format powinien być `email@domena.pl` (bez display name). Aktualny placeholder `"np. support@firma.pl"` jest dobrym startem, ale można dodać instrukcję do `form-hint`.

**[queue-form.component.ts:80–83] Zbędny blok `if (!this.isEditMode())` — martwy kod**

```typescript
if (!this.isEditMode()) {
  this.form.get('isActive')?.setValue(true);
}
```

`isActive` jest inicjalizowane jako `[true]` w FormGroup (linia 63). Ten blok ustawia tę samą wartość, która jest już domyślna. Można go usunąć bez żadnego efektu na zachowanie.

**[queue-form.component.html:87–88] Brak `aria-required="false"` lub dodatkowego tekstu dla screen readerów przy polu opcjonalnym**

```html
<label class="form-label" for="queue-email">
  Adres email kolejki (opcjonalnie)
</label>
```

Pole jest opisane jako "(opcjonalnie)" w etykiecie tekstowej — to dobra praktyka. Jednak screen readery nie rozróżniają wizualnie "Adres email kolejki (opcjonalnie)" od pola wymaganego. Brak `aria-required="false"` (choć wartość domyślna to `false`) i brak `aria-describedby` wskazującego na hint gdy nie ma błędu jest pominięciem. Aktualnie `aria-describedby` wskazuje na `queue-email-hint` gdy brak błędu — to jest właśnie prawidłowe i pokrywa ten przypadek. Obserwacja: wzorzec `[attr.aria-describedby]="emailAddressError ? 'queue-email-error' : 'queue-email-hint'"` zapewnia stały dostęp do opisu zarówno w stanie błędu jak i normalnym.

### Positive Observations

- **`emailAddress` poprawnie opcjonalne w obu DTO** — `CreateQueueRequest.emailAddress?: string | null` i `UpdateQueueRequest.emailAddress?: string | null` z typem union `string | null` poprawnie modeluje stan "nie podano" vs "wyczyść". 
- **`raw.emailAddress?.trim() || null`** — prosta, poprawna konwersja pustego stringa na null przed wysłaniem do API. Zapewnia, że backend nigdy nie dostanie pustego stringa zamiast null.
- **`Validators.email` + `Validators.maxLength(255)`** — walidacja klienta spójna z CHECK constraint w bazie (`email_address LIKE '%@%'` i `VARCHAR(255)`). 
- **`get emailAddressError()` z pełną obsługą stanów** — sprawdza `invalid`, `dirty` i `touched` przed wyświetleniem błędu, co zapobiega przedwczesnemu wyświetlaniu błędów przy pustym formularzu.
- **Pole email widoczne zarówno w trybie tworzenia jak i edycji** — brak warunku `@if (isEditMode())` wokół pola email, co jest prawidłowe (adres można ustawić przy obu operacjach).
- **`type="email"` na `<input>`** — przeglądarka na mobile automatycznie wyświetli klawiaturę email z `@` i `.com`.
- **Hint z opisem funkcjonalności** — "Emaile przychodzące na ten adres będą automatycznie kierowane do tej kolejki" bezpośrednio w formularzu edukuje użytkownika bez dokumentacji.

### Summary

Implementacja frontendowa jest wysokiej jakości: pole email jest poprawnie opcjonalne, walidacja działa, null/pusty string jest prawidłowo konwertowany, a ARIA jest zaimplementowane starannie. Jeden potencjalny problem z inicjalizacją `''` zamiast `null` w trybie edycji jest edge case bez realnego wpływu na działanie (bo `onSubmit` konwertuje oba na `null`). Martwy kod w bloku `if (!this.isEditMode())` powinien być usunięty dla czystości.

**Ocena: 4.5/5** — poprawna implementacja z drobnymi usprawnieniami kosmetycznymi, brak błędów krytycznych.

---

## Review: EPIC-24 Transfer połączenia — pliki frontendowe — 2026-05-15

Scope: call-session.model.ts, softphone.service.ts, softphone.component.ts/html/scss, transfer-agent-list.component.ts/html/scss, transfer-queue-list.component.ts/html/scss, i18n/pl.json, en.json, de.json.

---

## [KRYTYCZNE] Sesja zostaje w stanie TRANSFERRING po błędzie attended transfer do agenta

**Plik:** `softphone.service.ts` — metody `initiateAttendedTransferToAgent`, `initiateAttendedTransfer`

**Problem:** Wzorzec stosowany przy attended transfer:
```typescript
this.session.set({ ...s, state: 'TRANSFERRING' }); // optymistyczna zmiana przed HTTP
this.http.post(...).pipe(catchError(() => of(null))).subscribe((resp) => {
  // po błędzie: onSettled() -> isTransferring.set(false), attendedConnected.set(true)
  // sesja zostaje w TRANSFERRING, secondLegCallId === null
});
```
`catchError(() => of(null))` konwertuje błąd HTTP na null — subscribe zawsze się uruchamia. Gdy backend zwraca błąd (409, 500, 403), sesja przechodzi w `TRANSFERRING`, `attendedConnected.set(true)` jest wywołane, ale `secondLegCallId` pozostaje null. Agent widzi panel "konsultacji" bez możliwości powrotu do połączenia — jest zablokowany w stanie TRANSFERRING.

**Rekomendacja:** Obsłużyć błąd HTTP i przywrócić stan sesji do `ACTIVE`:
```typescript
this.http.post<{ secondLegCallId?: string }>(url, body)
  .pipe(
    catchError(() => {
      this.session.set({ ...s, state: 'ACTIVE', transferTarget: null });
      onSettled?.();
      return EMPTY;
    })
  )
  .subscribe((resp) => {
    if (resp?.secondLegCallId) {
      this.secondLegCallId = resp.secondLegCallId;
    }
    this.attendedConnected.set(true);
    onSettled?.();
  });
```
Ten sam problem dotyczy `initiateAttendedTransfer` (PHONE attended) i `initiateAttendedTransferToAgent`.

---

## [WAŻNE] Niezgodność typów statusu agenta między backendem a frontendem

**Plik:** `call-session.model.ts` linia 11

**Problem:** `TransferAgentItem.status` jest typowany jako:
```typescript
status: 'AVAILABLE' | 'BUSY' | 'BREAK' | 'ON_CALL';
```
Backend (`TransferAgentResponse.java`) zwraca statusy: `AVAILABLE`, `BUSY`, `AFTER_CONTACT`, `ACTIVE`, `BREAK`, `INACTIVE`. Brakuje `AFTER_CONTACT`, `ACTIVE`, `INACTIVE` na frontendzie; `ON_CALL` nie istnieje w backendzie. TypeScript nie wykryje tego w runtime — stringi zawsze zostaną przypisane, ale klasy CSS oparte na statusie nie będą miały dopasowania.

**Rekomendacja:** Zsynchronizować typ ze specyfikacją backendu:
```typescript
status: 'AVAILABLE' | 'BUSY' | 'AFTER_CONTACT' | 'ACTIVE' | 'BREAK' | 'INACTIVE';
```
I dodać obsługę CSS klas dla brakujących statusów w SCSS transfer-agent-list.

---

## [WAŻNE] Hardcoded polskie stringi zamiast i18n — zakładki i teksty w transfer komponentach

**Pliki:**
- `softphone.component.ts` linia 52–54 — etykiety zakładek `'Telefon'`, `'Agent'`, `'Kolejka'`
- `transfer-agent-list.component.html` — placeholder `"Szukaj agenta..."`, aria-label, "Przekaż"/"Konsultuj", "Brak dostępnych agentów.", "Nie udało się załadować listy agentów."
- `transfer-queue-list.component.html` — "Nie udało się załadować listy kolejek.", "czeka", "agentów", "Brak dostępnych kolejek."
- `softphone.component.html` linia ~248 — `aria-label="Cel transferu"`

**Problem:** Projekt używa Transloco, wszystkie widoczne teksty muszą być w plikach i18n. Nowe komponenty zawierają kilkanaście hardcoded polskich stringów, co narusza standard i18n projektu. Aplikacja obsługuje pl/en/de.

**Rekomendacja:** Przenieść wszystkie stringi do `pl.json`/`en.json`/`de.json` i użyć `| transloco`. Przykład dla szablonu:
```html
<input [placeholder]="'agent.transfer.searchAgent' | transloco" />
<p>{{ 'agent.transfer.noAgentsAvailable' | transloco }}</p>
```
Dla zakładek w TypeScript — etykiety powinny być pobierane przez TranslocoService lub wbudowane bezpośrednio w template zamiast tablicy w komponencie.

---

## [WAŻNE] Brak `[disabled]` na przyciskach w `transfer-agent-list` i `transfer-queue-list` podczas transferu

**Plik:** `transfer-agent-list.component.html`, `transfer-queue-list.component.html`

**Problem:** Blokada podwójnych kliknięć (`isTransferring`) jest obsługiwana wyłącznie w `softphone.component.ts` (`onAgentSelected`, `onQueueSelected`). Przyciski w komponentach dzieci nie mają `[disabled]` binding — użytkownik może kliknąć w agenta lub kolejkę wielokrotnie (np. klawiaturą) i wywołać kilka requestów HTTP.

**Rekomendacja:**
1. Przekazać stan `isTransferring` jako `input()` do obu komponentów:
```typescript
isTransferring = input<boolean>(false);
```
2. Dodać `[disabled]="isTransferring()"` na każdym przycisku agenta/kolejki w template.

---

## [WAŻNE] Dead code — przyciski "Complete attended" w sekcji PHONE attended są nieosiągalne

**Plik:** `softphone.component.html` linia ~320–353

**Problem:** Sekcja `@else { completeAttended button }` w PHONE attended (linia ~340) jest widoczna tylko gdy `s.state === 'ACTIVE' && _showTransferPanel()`. Jednak `initiateAttendedTransfer` natychmiast ustawia `session.state = 'TRANSFERRING'` — panel transferu (wymagający `s.state === 'ACTIVE'`) znika przed wywołaniem `attendedConnected.set(true)`. Przycisk "Complete" w tej sekcji jest zatem **nieosiągalny** w praktyce — attended PHONE complete jest obsługiwany przez panel TRANSFERRING (linia 507–514).

**Rekomendacja:** Usunąć martwy kod `@else` bloku dla PHONE attended z sekcji panelu transferu (linie ~343–353).

---

## [WAŻNE] Brak obsługi `secondLegCallId === null` w `completeAttendedTransfer`

**Plik:** `softphone.service.ts` linia ~403–424

**Problem:** Gdy `secondLegCallId` jest null (backend nie zwrócił `secondLegCallId` lub błąd HTTP przy attended), `completeAttendedTransfer` wywołuje `of(null)` zamiast bridge HTTP — sesja zostaje ustawiona na `ENDED` bez faktycznego bridge. Agent nie wie, że bridge się nie wykonał.

**Rekomendacja:**
```typescript
if (!this.secondLegCallId) {
  // brak secondLegCallId - błąd operacji, nie można sfinalizować attended transfer
  onSettled?.();
  return;
  // + pokazać toast error użytkownikowi
}
```

---

## [SUGESTIA] `transferTargetTabs` nie reaguje na zmianę języka Transloco

**Plik:** `softphone.component.ts` linia 51–55

**Problem:** Tablica `transferTargetTabs` jest zadeklarowana jako statyczna stała z hardcoded polskimi etykietami — nie odświeży się po zmianie języka.

**Rekomendacja:** Przenieść etykiety do szablonu bezpośrednio z `| transloco` pipe zamiast budować tablicę etykiet w TypeScript:
```html
@for (tab of transferTargetTabValues; track tab) {
  <button ...>{{ 'agent.transfer.tab.' + tab | transloco }}</button>
}
```
gdzie `transferTargetTabValues = ['PHONE', 'AGENT', 'QUEUE'] as const`.

---

## Podsumowanie EPIC-24 Frontend

**Ocena: 3/5** — Architektura jest poprawna: `OnPush`, signals, `takeUntilDestroyed`, standalone components, spinner blokujący podwójne kliknięcia, skeleton loading states — solidne fundamenty. Jednak wykryto jeden krytyczny bug (sesja zablokowana w TRANSFERRING po błędzie attended HTTP), ważne problemy z typami (status niezgodny z backendem) i wszechobecne hardcoded polskie stringi zamiast i18n.

**Najważniejsze do poprawy przed merge:**
1. Przywrócenie stanu `ACTIVE` po błędzie attended transfer HTTP — blokuje agenta
2. Synchronizacja `TransferAgentItem.status` z wartościami backendu (AFTER_CONTACT, ACTIVE, INACTIVE)
3. Przeniesienie hardcoded polskich stringów do plików i18n (zakładki, placeholdery, komunikaty błędów)
4. Dodanie `[disabled]="isTransferring"` na przyciskach w `transfer-agent-list` i `transfer-queue-list`
5. Usunięcie dead code — blok "Complete" w PHONE attended section panelu transferu

---

## Review: EPIC-27 — Dyspozycje frontend (DispositionListEditor, panel agenta, widoki supervisora) — 2026-05-27

**Branch:** custom-dispozition
**Reviewer:** senior-code-reviewer agent
**Pliki:** `custom-disposition.model.ts`, `custom-disposition.service.ts`, `disposition-list-editor.component.ts/html/scss`, `campaign-dispositions.component.ts/html`, `queue-dispositions.component.ts/html`, `campaign-form.component.ts/html` (zmiany EPIC-27), `queue-form.component.ts/html` (zmiany EPIC-27), `disposition-panel.component.ts/html`, `disposition.model.ts`

---

### [MAJOR] `DispositionListEditorComponent` nie reaguje na zmiany input signals — brak reload przy zmianie campaignId/queueId

**Plik:** `disposition-list-editor.component.ts:67-68`

**Problem:** Komponent implementuje `OnInit` i ładuje dane jednorazowo w `ngOnInit()`. Jeśli parent zmieni wartość `campaignId` lub `queueId` (np. user przełączy kampanię bez niszczenia komponentu), `loadDispositions()` nie zostanie wywołana ponownie. Przy obecnej architekturze (osadzony w campaign-form/queue-form) ryzyko jest niskie, ale komponent jest reużywalny (`shared/`) — kolejne użycie może ujawnić ten problem.

**Sugestia:** Użyć `effect()` do obserwowania sygnałów wejściowych:
```typescript
constructor() {
  effect(() => {
    const cId = this.campaignId();
    const qId = this.queueId();
    if (cId || qId) {
      this.loadDispositions();
    }
  });
}
```
Lub zachować `ngOnInit` ale dodać `ngOnChanges` z detekcją zmiany. Przy `effect()` można usunąć `implements OnInit`.

---

### [MAJOR] Fallback w `DispositionPanelComponent` używa `d.code` jako etykiety zamiast przetłumaczonej nazwy

**Plik:** `disposition-panel.component.ts:105`

**Problem:** Przy błędzie API, fallback mapuje `DISPOSITION_CODES` jako:
```typescript
DISPOSITION_CODES.map((d) => ({ code: d.code, label: d.code, tone: 'neutral' }))
```
`label` jest ustawiany na `d.code` (np. `"NO_INTEREST"`) zamiast na przetłumaczoną nazwę. Agent widzi techniczne kody zamiast czytelnych etykiet. `DISPOSITION_CODES` ma pole `labelKey` do użycia z Transloco, ale jest ono ignorowane.

**Sugestia:**
```typescript
DISPOSITION_CODES.map((d) => ({
  code: d.code,
  label: this.transloco.translate(d.labelKey),
  tone: 'neutral',
}))
```

---

### [MINOR] Niespójność typów tonu między `disposition.model.ts` a API

**Plik:** `disposition.model.ts:1`, `disposition-panel.component.ts:105`

**Problem:** `DispositionTone` w starym modelu agenta definiuje typy `'accent' | 'success' | 'warning' | 'danger' | 'violet' | 'neutral'`, które nie odpowiadają wartościom API (`'positive' | 'negative' | 'neutral' | 'warning'`). W fallback hardkodowane jest `tone: 'neutral'` dla wszystkich — `toneClass()` działa poprawnie (domyślna wartość), ale `DISPOSITION_CODES` zawiera `'violet'`, `'accent'`, `'danger'`, które nie mają odpowiednika w `toneClass()`.

**Sugestia:** Usunąć `DispositionTone` z `disposition.model.ts` lub ujednolicić typy tonu z `DispositionToneApi` z `custom-disposition.model.ts`. Pole `tone: DispositionTone` w `DispositionCode` jest de facto martwe w nowym flow.

---

### [MINOR] Brak zabezpieczenia przed podwójnym zapisem w `loadDispositions` gdy `takeUntilDestroyed` jest w kombinacji z kolejnymi wywołaniami

**Plik:** `disposition-list-editor.component.ts:86-97`

**Problem:** `loadDispositions()` jest wywoływana po każdym sukcesie zapisu i usunięcia. Każde wywołanie tworzy nową subskrypcję z `takeUntilDestroyed`. Jeśli poprzedni request jest in-flight i nastąpi kolejne wywołanie `loadDispositions()`, oba requesty będą aktywne równolegle. Ostatni który wróci "wygra" i ustawi stan — może to powodować migotanie listy lub stary stan.

**Sugestia:** Użyć `switchMap` lub `Subject` + `switchMap` do anulowania poprzednich requestów:
```typescript
private readonly reload$ = new Subject<void>();

constructor() {
  this.reload$.pipe(
    switchMap(() => this.buildList$()),
    takeUntilDestroyed(this.destroyRef)
  ).subscribe({ next: items => this.dispositions.set(items), error: () => ... });
}

loadDispositions(): void { this.reload$.next(); }
```

---

### [MINOR] Brak obsługi przypadku, gdy oba `campaignId` i `queueId` są `undefined` — brak komunikatu dla użytkownika

**Plik:** `disposition-list-editor.component.ts:75-77`

**Problem:** Gdy oba inputy są `undefined`, komponent cicho nic nie robi (`return`). Loading pozostaje `false`, lista jest pusta, ale nie wyświetla się żaden błąd. Ponieważ komponent jest w `shared/`, nieprawidłowe użycie (brak obu inputów) nie daje żadnej diagnostyki.

**Sugestia:** Dodać co najmniej `console.warn`:
```typescript
if (!campaignId && !queueId) {
  console.warn('[DispositionListEditor] Neither campaignId nor queueId provided — component is idle.');
  return;
}
```

---

### [MINOR] `onDeleteExecute()` używa `queueId!` gdy `campaignId` jest undefined — brak guard dla null `queueId`

**Plik:** `disposition-list-editor.component.ts:195`

**Problem:** `delete$` jest budowany jako:
```typescript
const delete$ = campaignId
  ? this.service.deleteFromCampaign(campaignId, id)
  : this.service.deleteFromQueue(queueId!, id);
```
Jeśli oba `campaignId` i `queueId` są `undefined` (co jest możliwe przez API komponentu), `queueId!` jest wymuszone i wywołanie API wyśle request z `undefined` w URL → `/api/dispositions/queues/undefined/{id}`. To samo dotyczy `onSubmit()`.

**Sugestia:** Dodać guard przed budowaniem requestu:
```typescript
if (!campaignId && !queueId) {
  this.notifications.error('Brak kontekstu — nie można zapisać dyspozycji.');
  this.submitting.set(false);
  return;
}
```

---

### Pozytywne obserwacje

- Wszystkie nowe komponenty mają `standalone: true` i `ChangeDetectionStrategy.OnPush` — pełna zgodność z architekturą.
- Stan zarządzany przez `signal()` / `computed()` — zgodny z konwencją projektu.
- `takeUntilDestroyed(this.destroyRef)` poprawnie stosowany we wszystkich subskrypcjach — brak wycieków pamięci.
- Obsługa błędu 409 w `onSubmit()` jest user-friendly — oddzielny komunikat zamiast generycznego błędu.
- `catchError` + `return EMPTY` we wszystkich operacjach — komponent nie crashuje przy błędach API.
- Formularz reaktywny z pełną walidacją po stronie klienta: `@Pattern(/^[A-Z0-9_]+$/)`, `maxLength`, `required` — spójne z backendem.
- Dostępność: atrybuty `aria-invalid`, `aria-describedby`, `role="alert"` na polach formularza — wzorowe.
- `TONE_CSS_CLASS` w modelu jako `Record<DispositionToneApi, string>` — type-safe mapowanie, brak magic strings.
- Fallback agenta używa `console.warn` zamiast `console.error` — poprawne traktowanie degradacji graceful jako niekreytycznej.
- `CampaignDispositionsComponent` i `QueueDispositionsComponent` jako cienkie wrappery — czyste rozdzielenie odpowiedzialności.
- Osadzanie sekcji dyspozycji tylko w trybie edycji (`@if (campaignId())` / `@if (queueId(); as id)`) — prawidłowe.

### Summary

Frontend jest dobrze zorganizowany: OnPush, signal-based state, standalone components, prawidłowe zarządzanie lifecycle subskrypcji. Główne problemy to brak reaktywności na zmiany inputów (komponent nie przeładuje danych jeśli parent zmieni kontekst), błędne etykiety w fallback agenta (techniczne kody zamiast tłumaczeń) oraz ryzyko wywołania API z `undefined` w URL gdy oba inputy są niezainicjowane.

**Ocena: 3.5/5** — solidna podstawa, ale wymaga naprawienia etykiet w fallback i dodania `effect()` dla reaktywności inputów przed mergem.

---

## Review: EPIC-27 — Frontend zestawów dyspozycji (model, service, page, shared editor, routes, sidenav) — 2026-05-28

**Branch:** custom-dispozition

### Bugs / Critical Issues

- **disposition-sets-page.component.ts:94 (Validators.maxLength(200) — niezgodność z backendowym limitem 100)** Formularz zestawów ma `Validators.maxLength(200)` dla pola `name`, podczas gdy backend (`CreateDispositionSetRequest`) i baza (`VARCHAR(100)`) akceptują maksymalnie 100 znaków. Oznacza to, że formularz zaakceptuje nazwy 101-200 znaków, które następnie zostaną odrzucone przez backend z HTTP 400 (błąd walidacji Bean Validation). Użytkownik wpisuje 150 znaków, klika "Utwórz", dostaje error z serwera — zamiast walidacji inline.
  - Fix: Zmień na `Validators.maxLength(100)` i `maxlength="100"` w szablonie (linia 39 w HTML).

- **disposition-sets-page.component.html:39 (maxlength="200" w atrybucie HTML)** Powiązany problem z powyższym — atrybut HTML `maxlength="200"` pozwala na wpisanie 200 znaków w input, co jest niespójne z backendowym limitem 100. Obie zmiany muszą być zsynchronizowane.

### Security Concerns

_None identified._

### Architecture / Pattern Violations

- **disposition-sets-page.component.ts:110-131 (loadSets w constructor — nie w ngOnInit)** `loadSets()` jest wywoływane w konstruktorze zamiast w ciele klasy (Angular 16+ preferuje inicjalizację w polu klasy lub `afterNextRender`). W przypadku `standalone + OnPush`, wywołanie HTTP w konstruktorze odbywa się przed zakończeniem DI i przed pierwszym cyklem detekcji zmian. Praktycznie działa, ale jest anty-wzorcem w nowoczesnym Angular — konstruktor powinien być zarezerwowany tylko dla inicjalizacji DI.
  - Fix: Przenieś `this.loadSets()` do pola klasy jako `effect()` lub wywołaj w `afterNextRender`.

- **disposition-list-editor.component.ts:66-71 (effect z podwójnym odczytem signalów bez untracked)** Konstrukt:
  ```ts
  private readonly loadEffect = effect(() => {
    this.campaignId();
    this.queueId();
    untracked(() => this.loadDispositions());
  });
  ```
  jest poprawny — `untracked` zapobiega głębszym subskrypcjom w `loadDispositions`. To jest wzorzec zgodny z architekturą projektową.

- **disposition-list-editor.component.ts:240-257 (sets ładowane tylko raz — set picker nie odświeża po zmianach)** `availableSets` jest ładowany tylko gdy `availableSets().length === 0`. Jeśli użytkownik doda/usunie zestaw w innej zakładce, set picker pokaże nieaktualną listę. Cache nie jest inwalidowany gdy otwarty zestaw zostaje usunięty.
  - Fix: Dodaj timestamp ostatniego ładowania lub zawsze ładuj przy otwarciu pickera (usuwając warunek `this.availableSets().length === 0`).

- **disposition-sets-page.component.ts:234 (sets.update prepend — nowy zestaw trafia na górę, mimo sortowania backendowego `name ASC`)** Po utworzeniu zestawu komponent dodaje go na początek listy `[created, ...list]`, ale backend sortuje listę `name ASC`. Po odświeżeniu strony nowy element pojawi się w innej pozycji. Lokalny state jest niespójny z stanem serwera.
  - Fix: Po `createSet` wykonaj `loadSets()` w celu przeładowania posortowanej listy, lub wstaw element w alfabetycznej pozycji lokalnie.

### Improvements & Suggestions

- **disposition-sets-page.component.ts:412 (isSetFieldInvalid używa `ctrl.touched` — brak walidacji po 'blur' dla `name`)** Walidacja `name` wyświetla się dopiero po `touched`. Przy pierwszym submitcie bez kliknięcia w pole, `markAllAsTouched()` (linia 177) poprawnie oznacza pole jako touched i uruchamia walidację. Zachowanie jest poprawne, ale warto dodać komentarz wyjaśniający dlaczego `markAllAsTouched` jest konieczny.

- **disposition-sets-page.component.html:109 (pluralizacja — tylko 1/nie-1, brak form 2-4)** Kod:
  ```html
  {{ set.itemCount === 1 ? 'dyspozycja' : 'dyspozycji' }}
  ```
  jest niepoprawny dla języka polskiego. Właściwa pluralizacja: 1 = "dyspozycja", 2-4 = "dyspozycje", 5+ = "dyspozycji". Przy itemCount=3 wyświetli "3 dyspozycji" zamiast "3 dyspozycje".
  - Fix: Użyj pipe `| i18nPlural` z Transloco lub zdefiniuj helper `pluralizeDispositions(count: number)`.

- **disposition-list-editor.component.html:170-176 (interpolacja HTML w atrybucie message confirm-dialog)** Kod przekazuje do `[message]` string zbudowany przez template expression z `pendingSet()!.itemCount`. Wartość `itemCount` pochodzi z backendu i jest liczbą, więc brak ryzyka XSS. Jednak `&quot;` encje HTML są używane bezpośrednio w stringu TypeScript — lepiej zdefiniować `pendingSetMessage = computed(...)` w komponencie.

- **sidenav.component.ts:188-191 (nowy element menu "Zestawy dyspozycji" jest poprawnie dodany)** Nowa pozycja w `SUPERVISOR_NAV` wskazuje na `/supervisor/settings/disposition-sets` — spójne z routingiem. Dodana jako child `nav.configuration` — poprawna hierarchia.

- **supervisor.routes.ts:131-139 (route disposition-sets — canActivate z roleGuard)** Trasa jest chroniona przez `roleGuard` z `roles: ['SUPERVISOR', 'ADMIN']` i używa lazy loadingu komponentu. Zgodne z wzorcem pozostałych tras settings.

- **disposition-set.service.ts (empty body w POST apply)** Metody `applyToCampaign` i `applyToQueue` wysyłają `{}` jako body POST. Semantycznie POST powinno mieć body lub być bez body (`null`). Wysyłanie pustego obiektu `{}` nie jest błędem, ale może być mylące.

### Positive Observations

- `DispositionSetsPageComponent` używa `ChangeDetectionStrategy.OnPush` — poprawnie.
- Wszystkie Observable są zarządzane przez `takeUntilDestroyed(this.destroyRef)` — brak wycieków subskrypcji.
- `inject()` zamiast constructor DI — zgodne z wzorcem projektu.
- Stan zarządzany przez `signal()` i `computed()` — zgodne z wytycznymi projektu, nie ma `BehaviorSubject`.
- `catchError` + `EMPTY` wzorzec obsługi błędów HTTP — poprawne zakończenie strumienia bez rzucania błędu w górę.
- Formularze używają `getRawValue()` po wyłączeniu pola `dispositionCode` — poprawna obsługa wyłączonych pól w reactive forms.
- `DispositionSetService` (Angular) jest poprawnie `providedIn: 'root'` — singleton bez wycieków.
- Model TypeScript `disposition-set.model.ts` jest spójny z DTO backendu — kompletna i poprawna typizacja.
- Accessibility: `aria-label`, `role="button"`, `aria-pressed`, `aria-invalid`, `role="alert"` na komunikatach błędów — solidna implementacja a11y.
- Dialogi potwierdzenia usunięcia (zestaw i element) są obsługiwane przez `ConfirmDialogComponent` — spójne z resztą aplikacji.

### Summary

Frontend jest dobrze zorganizowany: standalone components, OnPush, signal-based state, prawidłowe zarządzanie lifecycle subskrypcji, kompletna a11y. Krytyczna usterka to niezgodność `maxLength(200)` w formularzu vs limit `100` backendu/bazy — użytkownik może wpisać za dużą nazwę, która zostanie odrzucona przez server. Drobne problemy to pluralizacja w języku polskim i lokalny stan listy niespójny po createSet. Dodanie zestawu dyspozycji do sidenav i routing są poprawne.

**Ocena: 4/5** — wymaga tylko naprawy maxLength(200)→(100) przed mergem; pozostałe to quality of life improvements.

## Review: `externalId` na Customer (CRM external ID) — 2026-07-05

**Branch:** `customer-refactor`

**Zakres:** `customer.model.ts`, `customer-profile.model.ts`, `customer.service.ts`, `customer-create-modal`
(ts+html), `customer-edit` (ts+html), `customer-detail.component.html`, `customer-list` (ts+html+spec),
`customer-import.component.ts`, `customer-panel.component.html` (agent), i18n (pl/en/de/uk).

### 🐛 Bugs / Critical Issues

- **`customer-edit.component.ts:161` — nie da się wyczyścić już ustawionego `externalId`.** `externalId: raw.externalId?.trim() || undefined` — gdy użytkownik czyści pole (wpisuje pusty string), `''` jest falsy, więc payload dostaje `undefined`. `HttpClient` (przez `JSON.stringify`) całkowicie pomija pole o wartości `undefined` w body requestu, a backendowa semantyka PATCH (`null = brak zmiany`) traktuje brak pola identycznie jak `null`. Efekt: raz ustawiony `externalId` (np. przez import CSV albo pomyłkowo wpisany) **nie da się usunąć przez formularz edycji** — w przeciwieństwie do `phone`/`email`, które mają jawny sposób wyczyszczenia (`[]`). Powiązane ze znaleziskiem w `CR-BACKEND.md` (`CustomerServiceImpl` też nie normalizuje pustego stringa do `NULL`).
  - Fix: wymaga wspólnej decyzji FE+BE — np. backend powinien traktować jawny pusty string `""` jako "wyczyść do NULL" (odróżnione od pominiętego pola/`null` = "bez zmiany"), a frontend powinien wtedy wysyłać `raw.externalId?.trim() ?? undefined` (bez `|| undefined`, żeby pusty string przechodził jako `""`, nie ginął).

### ⚠️ Security Concerns

_Nie zidentyfikowano._

### 🏗️ Architecture / Pattern Violations

- **`CustomerSummary` (`customer-search.model.ts`) i `agent-customer-card.component.ts` NIE zostały rozszerzone o `externalId`, mimo że backend już zwraca to pole na tym endponcie.** Notatka w pamięci frontendowego agenta zakłada: "backend only added the field to CustomerResponse, not to the lightweight `/api/customers?search=` DTO" — **to założenie jest błędne**. Zweryfikowałem: `CustomerSearchService.search()` (`customer-search.service.ts:19-23`) woła `GET /api/customers?q=...&size=...`, czyli **dokładnie ten sam** endpoint/kontroler (`CustomerController.listOrSearchCustomers`), który zwraca `PagedResponse<CustomerResponse>` — a `CustomerResponse` w tym PR zostało rozszerzone o `externalId` dla KAŻDEGO wyniku (zarówno fuzzy search po `q`, jak i zwykłej paginacji). Innymi słowy: agentowski search (`agent-customer-card.component.ts`) już dziś dostaje `externalId` w surowym JSON-ie po sieci — tylko interfejs TypeScript `CustomerSummary` go nie deklaruje, więc komponent nie może go wyświetlić.
  - Fix: dodać `externalId?: string` do `CustomerSummary` i wyświetlić je w `agent-customer-card.component.ts` analogicznie do już istniejących bloków `@if (customer.phone.length > 0)`/`@if (customer.email.length > 0)` — to "darmowe" usprawnienie, bez żadnej zmiany backendu ani dodatkowego payloadu (dane już tam są).
- **`customer-create-modal.component.ts` i `customer-edit.component.ts` — generyczna obsługa błędów HTTP nie rozróżnia nowego 409 (duplikat `externalId`).** Oba komponenty łapią wszystkie błędy przez `catchError(() => { notifications.error(genericMessage); return of(null); })`. Backend zwraca teraz konkretny, sensowny komunikat `ConflictException` ("Klient z identyfikatorem zewnętrznym 'X' już istnieje"), ale UI nigdy go nie pokazuje — użytkownik widzi tylko ogólne "nie udało się zapisać/utworzyć klienta" i musi się domyślić, że problemem jest `externalId`. To świadoma decyzja zakresu odnotowana w pamięci projektu (reużycie istniejącego generycznego wzorca), ale skoro backend już dostarcza precyzyjny komunikat 409, warto domknąć pętlę: rozróżnić `error.status === 409` i pokazać `error.error?.detail` (ProblemDetail) lub podświetlić pole `externalId` jako nieprawidłowe.

### 🔧 Improvements & Suggestions

- `customer-list.component.ts` `getExternalId()` poprawnie podąża za ustalonym wzorcem dash-fallback (`getFirstPhone`/`getFirstEmail`) i ma dopasowany test jednostkowy — dobra spójność, bez uwag.
- `customer-import.component.ts` — nowe wpisy `AUTO_MAP` (`external_id`, `externalid`, `crm_id`, `id_zewnetrzne`) to sensowna heurystyka; jeśli import ma docelowo współpracować z konkretnymi systemami CRM, warto rozważyć też popularne warianty (`sfid`, `salesforce_id`, `hubspot_id`) — opcjonalne, bez akcji jeśli zakres ma pozostać ogólny.

### ✅ Positive Observations

- **i18n kompletne i spójne we wszystkich 4 językach** (pl/en/de/uk) dla każdej nowej etykiety (create/edit/detail/list/panel agenta) — brak brakujących kluczy. Polskie znaki diakrytyczne poprawne ("ID zewnętrzne") — nawracający w poprzednich review anti-pattern braku diakrytyków (FE-011/017/027, EPIC-24) **nie powtórzył się** tym razem.
- `customer-edit.component.ts` `populateForm()` poprawnie patchuje `externalId` z `customer.externalId ?? ''`, zgodnie ze wzorcem `firstName`/`lastName`.
- Propagacja `CustomerLookupResponse`/`CustomerProfile.externalId` do panelu agenta (`customer-panel.component.html`) jest poprawnie okablowana end-to-end (zweryfikowane po stronie backendu — oba miejsca budujące `CustomerLookupResponse` uwzględniają pole).
- Świadoma, udokumentowana decyzja, żeby nie wprowadzać Transloco w `customer-import.component.ts` (plik sprzed rolloutu i18n) i zachować istniejącą konwencję (stringi ASCII po polsku) — spójny wybór, nawet jeśli brak rozróżnienia 409 (patrz wyżej) wciąż warto uzupełnić później.

### Summary

**Ocena: 3.5/5 ⭐** — okablowanie nowego pola w UI jest dokładne i spójne (i18n, tabela, widok szczegółów, panel agenta), ale przepływ "duplikat externalId" nie jest domknięty od strony UX (generyczny błąd, brak sposobu wyczyszczenia pola), a "darmowe" usprawnienie (karta wyszukiwania agenta) zostało pominięte na podstawie niezweryfikowanego założenia o kontrakcie backendu.

## Review: CustomerImportComponent — wielokolumnowy phone/email, nazwane custom_fields, opcje zgody RODO — 2026-07-05

**Branch:** `customer-refactor`

**Zakres:** `customer-import.component.ts/.html/.scss`, `customer.service.ts` (`importCsv` sygnatura),
i18n (pl/en/de/uk). Brak pliku testów (`customer-import.component.spec.ts` nie istnieje — potwierdzone,
stan sprzed tej zmiany, nie regresja).

### 🐛 Bugs / Critical Issues

- **`customer-import.component.ts:346-349` (`onImport`) — dwie kolumny CSV zmapowane na TĘ SAMĄ nazwę `custom_fields` cicho się nadpisują, bez ostrzeżenia i bez utraty danych sygnalizowanej użytkownikowi.** Budowa `customFieldsObj`:
  ```ts
  case 'custom_fields': {
    const key = m.customFieldKey?.trim();
    if (key) customFieldsObj[key] = index;
    break;
  }
  ```
  Jeśli użytkownik zmapuje np. kolumnę CSV #3 na `custom_fields` z nazwą `"vip"` ORAZ kolumnę CSV #7 również na `custom_fields` z nazwą `"vip"` (np. przez pomyłkę, kopiuj-wklej nazwy, albo dwa nagłówki CSV które użytkownik chce ujednolicić), drugi wpis w pętli `forEach` bezwarunkowo nadpisuje pierwszy pod tym samym kluczem JS-obiektu — wartość z kolumny #3 znika całkowicie z requestu, zero komunikatu o błędzie. To DOKŁADNIE ten sam rodzaj cichej utraty danych, przed którym chroni już istniejąca walidacja `hasUnnamedCustomField` (linie 315-321) dla pustych nazw — brakuje analogicznej walidacji dla duplikatów. Nie jest to teoretyczny przypadek: to jest właśnie ten bug, który PIERWOTNIE naprawiono w tym samym komponencie dla `phone`/`email` (dwie kolumny na `phone` nadpisywały się w starym kodzie) — tu identyczny wzorzec błędu odtworzył się dla nowego pola, tylko nie został naprawiony.
  - Fix: dodać walidację analogiczną do `hasUnnamedCustomField`, przed importem:
    ```ts
    const customFieldKeys = this.columnMappings()
      .filter((m) => m.systemField === 'custom_fields')
      .map((m) => m.customFieldKey?.trim())
      .filter((k): k is string => !!k);
    const hasDuplicateCustomFieldKey = new Set(customFieldKeys).size !== customFieldKeys.length;
    if (hasDuplicateCustomFieldKey) {
      this.mappingError.set('Nazwy pol dodatkowych musza byc unikalne.');
      return;
    }
    ```
  - Uwaga: potwierdzone w pamięci `angular-frontend-expert` (`project_fe020_customer_import_columnmapping_fix.md`), że to świadoma decyzja o pominięciu tej walidacji — ale biorąc pod uwagę, że backend nie ma żadnego sposobu wykrycia tej kolizji (dwa różne indeksy kolumn pod tym samym kluczem JSON to po prostu dwa oddzielne wpisy w obiekcie `custom_fields`, JSON nie pozwala na duplikat klucza, więc jeden ginie już na etapie budowania `mappingObj` we frontendzie, zanim request w ogóle wyjdzie do sieci) — to wyłącznie frontendowa odpowiedzialność i powinna być naprawiona tutaj.

### ⚠️ Security Concerns

_Nie zidentyfikowano._

### 🏗️ Architecture / Pattern Violations

_Brak nowych naruszeń architektury._ `columnMapping: Record<string, unknown>` w `customer.service.ts:91` to minimalna, poprawna zmiana typu (z `Record<string, number>`) — implementacja `importCsv` niezmieniona (dalej tylko `JSON.stringify`), zgodna z zasadą "DTO/kontrakt się zmienia, serwis HTTP pozostaje cienką warstwą".

### 🔧 Improvements & Suggestions

- **Recurring anti-pattern (5+ wystąpienie) — brak polskich znaków diakrytycznych w NOWYM stringu walidacyjnym.** `customer-import.component.ts:319`: `this.mappingError.set('Kazde pole dodatkowe musi miec nazwe.');` — powinno być `"Każde pole dodatkowe musi mieć nazwę."`. Cały plik konsekwentnie używa Polish-ASCII zamiast prawdziwych diakrytyków (`"ID zewnetrzne"`, `"musi byc zmapowane"`, `"sredník"` z błędnie postawionym akcentem) — to udokumentowana, świadoma konwencja tego konkretnego pliku (plik sprzed rolloutu i18n/Transloco, patrz poprzedni review `externalId` 2026-07-05), więc nowy string jest przynajmniej *spójny* z resztą pliku. Niemniej jest to piąte odnotowane wystąpienie tego wzorca w tym repo (po FE-011/017/027, EPIC-24) — jeśli plik kiedykolwiek zostanie zmigrowany na Transloco (i18n klucze `customFieldKeyRequiredError` już przygotowane w plikach tłumaczeń!), warto przy okazji naprawić diakrytyki we wszystkich hardkodowanych stringach na raz, nie tylko w nowych.
- **Zero pokrycia testami dla znacząco zwiększonej złożoności logiki.** `customer-import.component.spec.ts` nie istnieje (potwierdzone, stan sprzed zmiany). Ten PR dodaje nietrywialną logikę: łączenie wielu indeksów kolumn w tablice (`phoneIndices`/`emailIndices`), budowanie nazwanego obiektu (`customFieldsObj`), i dwie nowe ścieżki walidacji blokującej import. To dokładnie ten rodzaj logiki (transformacja danych, budowanie payloadu, walidacja warunkowa), który najbardziej zyskuje na testach jednostkowych i najbardziej boli przy regresji bez nich — np. bug z Bugs #1 powyżej zostałby wykryty przez prosty test `it('rejects duplicate custom field names')`. Nie blokujące (zgodne z istniejącą konwencją "brak testów dla tego kreatora"), ale warto rozważyć chociaż podstawowy plik spec przy następnej zmianie w tym komponencie, zamiast dalej powiększać nietestowany obszar.
- `customer-import.component.html:44-53` — `aria-label` nowego pola `<input>` nazwy custom field jest hardkodowany string budowany przez konkatenację (`'Nazwa pola dodatkowego dla ' + mapping.csvHeader`) — spójne z istniejącym wzorcem `[attr.aria-label]` na sąsiednim `<select>` w tym samym pliku, nie regresja, tylko do wiadomości przy ewentualnej migracji na Transloco.

### ✅ Positive Observations

- **Rzeczywisty bug naprawiony poprawnie**: stary kod (`mappingObj[m.systemField] = index`) faktycznie nadpisywał drugą kolumnę zmapowaną na `phone`/`email` — nowa implementacja z osobnymi tablicami `phoneIndices`/`emailIndices`, budowanymi przez `push` w `forEach`, poprawnie zbiera WSZYSTKIE zmapowane kolumny bez utraty żadnej z nich. Zweryfikowane też od strony backendu (`getMultiColumn` łączy poprawnie).
- `hasUnnamedCustomField` (linie 315-321) poprawnie blokuje import PRZED wysłaniem requestu, z komunikatem błędu w tym samym miejscu i tym samym mechanizmem (`mappingError` signal) co istniejąca walidacja `isPhoneMapped` — spójny wzorzec UX, dobry precedens dla sugerowanej poprawki Bugs #1.
- `updateCustomFieldKey` poprawnie podąża za istniejącym niemutującym wzorcem `updateMapping` (`{...mappings[i], customFieldKey: key}` zamiast mutacji w miejscu) — zgodne z konwencją `signal()`-owego stanu w projekcie.
- **i18n kompletne i spójne we wszystkich 4 językach** dla wszystkich nowych etykiet (`consentGivenOption`, `marketingConsentOption`, `customFieldKeyPlaceholder`, `multiColumnHint`, `customFieldKeyRequiredError`) — w tym poprawne diakrytyki w kluczach faktycznie użytych przez Transloco (`multiColumnHint` w hincie nad tabelą mapowania). Klucze przygotowane "na zapas" dla przyszłej migracji reszty pliku na Transloco (`customFieldKeyRequiredError` nieużywany dziś, bo `mappingError` jest hardkodowany) — dobra praktyka wyprzedzająca.
- `.mapping-hint` (SCSS) poprawnie używa istniejących design-tokenów projektu (`var(--accent-text)`, `var(--accent-soft)`, `color-mix(...)`) zamiast hardkodowanych kolorów — spójne z resztą arkusza stylów.

### Summary

**Ocena: 3.5/5 ⭐** — poprawnie naprawia realny, zgłoszony bug nadpisywania kolumn phone/email i dodaje solidną, kompletnie zi18n-izowaną obsługę nowych opcji mapowania, ale odtwarza dokładnie ten sam rodzaj bugu (cicha kolizja/nadpisanie bez ostrzeżenia) dla nowego pola `custom_fields` — świadomie pominięta walidacja duplikatu nazwy pola dodatkowego powinna zostać dodana przed uznaniem funkcji za w pełni zamkniętą, zwłaszcza że wzorzec naprawy (analogiczna walidacja dla pustej nazwy) już istnieje w tym samym pliku.

---

## Review: CustomerImportComponent / CustomerService — import JSON (nowa ścieżka równoległa do CSV) — 2026-07-06

### 🐛 Bugs / Critical Issues

- **Pusta tablica JSON (`[]`) jest akceptowana bez ostrzeżenia i pozwala na wysłanie pustego importu.** `customer-import.component.ts:262-267` (`parseJsonPreview`): gdy `records.length === 0`, kod ustawia `previewHeaders`/`previewRows` na puste tablice i **wraca bez wywołania `setInvalidJsonState()`** — w przeciwieństwie do przypadku "root nie jest tablicą obiektów", `selectedFile` NIE jest czyszczony. Efekt: sekcja podglądu się nie renderuje (`@if (previewHeaders().length > 0)`, html:215), ale przycisk główny pozostaje aktywny (`[disabled]="!selectedFile() || submitting()"`, html:248-253) i `goToImportOrMapping()` → dla formatu `json` od razu woła `onImport()` → `submitJsonImport()`, wysyłając plik z 0 rekordów do backendu. Użytkownik nie dostaje żadnego sygnału, że plik jest pusty, zanim potwierdzi import — łatwo przeoczyć, że np. eksport z CRM zwrócił pustą tablicę zamiast oczekiwanych rekordów. (Zweryfikowałem backend: pusta tablica kończy się cicho jako `COMPLETED` z `processed=0/total=0/imported=0`, bez odrębnego statusu — patrz `CR-BACKEND.md`, ten sam przypadek brzegowy.)
  - Fix: w bloku `records.length === 0` wywołać coś analogicznego do `setInvalidJsonState()` (nowy komunikat, np. `emptyJsonArrayError`) zamiast cichego `return`, tak żeby przycisk kontynuacji został zablokowany tak samo jak dla nieprawidłowego JSON-a.

### ⚠️ Security Concerns

_Brak nowych zagrożeń._ `accept=".csv,.json"` na `<input type="file">` (html:114) to tylko podpowiedź UI (nie zabezpieczenie) — rzeczywista walidacja rozszerzenia i tak następuje w `handleFile()` (linia 175-183) i ponownie po stronie backendu (`validateJsonFile`/`validateFile`), więc brak realnego ryzyka ominięcia walidacji przez spreparowaną nazwę pliku z innym rozszerzeniem niż faktyczna zawartość — plik trafia do `JSON.parse`/`objectMapper.readTree`, które i tak odrzucą niepoprawną zawartość niezależnie od rozszerzenia.

### 🏗️ Architecture / Pattern Violations

- **Brak regresji przy migracji `allSteps` z tablicy na `computed()`** — zweryfikowałem grep-em wszystkie miejsca użycia w całym komponencie i szablonie: `getStepNumber`/`isStepCompleted` (ts:573,577) i `@for (step of allSteps(); ...)` (html:19) poprawnie wołają `allSteps()` jako funkcję. Nie znalazłem ani jednego pozostałego odwołania do starej sygnatury tablicowej (`allSteps` bez `()`). `goBackToUpload()` (linia 391-392) zawsze resetuje `currentStep` na `'upload'` przed jakąkolwiek ponowną selekcją pliku, więc nie ma scenariusza, w którym zmiana `importFormat` (a więc i przeliczenie `allSteps()`) mogłaby zostawić `currentStep` wskazujący na krok nieobecny w nowo obliczonej tablicy (np. `'mapping'` po przełączeniu z CSV na JSON).
- Podobnie zweryfikowałem `csvHeaders`/`csvPreviewRows` → `previewHeaders`/`previewRows` — zmiana nazw jest kompletna, brak osieroconych odwołań do starych nazw w `.ts` ani w `.html`.

### 🔧 Improvements & Suggestions

- **Nowa wiadomość walidacyjna kontynuuje znany, już wcześniej udokumentowany w tym pliku wzorzec braku polskich znaków diakrytycznych** (`customer-import.component.ts:180`): `'Dozwolone sa tylko pliki CSV (.csv) lub JSON (.json).'` — rozszerza istniejący hardkodowany string (`"Dozwolone sa tylko pliki CSV (.csv)."`) o nowy fragment, zachowując ten sam brak ogonków/kresek (`sa` zamiast `są`). O ile jest to spójne z resztą pliku sprzed rolloutu Transloco (udokumentowane w poprzednim review tego komponentu, 2026-07-05), warto zwrócić uwagę na niespójność: **nowe komunikaty specyficzne dla JSON (`invalidJsonArrayError`, `jsonFormatHint`) poprawnie przechodzą przez `TranslocoService`/i18n z prawidłowymi diakrytykami** (linia 286: `this.transloco.translate('supervisor.customerImport.invalidJsonArrayError')`), podczas gdy sąsiedni, tylko lekko zmodyfikowany string na tej samej ścieżce kodu (linia 180) pozostał hardkodowany i bez diakrytyków. Skoro `transloco` jest już wstrzyknięty i używany w tej samej metodzie/pliku, warto przy okazji zmigrować też ten string na klucz i18n zamiast pogłębiać niespójność.
- **Zero pokrycia testami dla nowej, istotnie bardziej złożonej logiki komponentu** — `customer-import.component.spec.ts` nadal nie istnieje (potwierdzone, ten sam brak co w poprzednim review z 2026-07-05). Ten PR dodaje: pełną gałąź formatu JSON (`parseJsonPreview`, walidację "tablica obiektów", `stringifyPreviewCell`), nowy `computed()` (`allSteps`) zależny od stanu, i nową ścieżkę wysyłki (`submitJsonImport`). To dokładnie ten rodzaj logiki rozgałęzień i parsowania wejścia użytkownika, który najbardziej ryzykuje regresję przy kolejnych zmianach bez testów jednostkowych — np. luka z sekcji Bugs (pusta tablica) zostałaby wykryta przez prosty test `it('rejects empty JSON array')`.
- `stringifyPreviewCell` (linia ~295-298) używa `JSON.stringify(value)` dla obiektów zagnieżdżonych w podglądzie (np. wartości `customFields` będące obiektem) — rozsądne dla podglądu, ale warto rozważyć obcięcie długości (np. `substring(0, 100) + '…'`) żeby bardzo duże zagnieżdżone obiekty (np. cały `gdprConsent` z dodatkowymi polami) nie rozwalały układu tabeli podglądu przy nietypowo dużych plikach.

### ✅ Positive Observations

- **Migracja `allSteps` z pola na `computed()` jest poprawnie zaimplementowana i kompletnie zaktualizowana we wszystkich miejscach użycia** — dobry przykład czystej reaktywnej zmiany stanu zależnego od `importFormat()`, zgodnie z konwencją projektu (`signal()`/`computed()` zamiast `BehaviorSubject` dla stanu lokalnego UI).
- **`goToImportOrMapping()` (linia ~361-368) to czysty, dobrze nazwany punkt rozgałęzienia** dla jedynego przycisku, który musi robić dwie różne rzeczy w zależności od formatu (przejście do mapowania dla CSV vs. bezpośredni import dla JSON) — czytelniejsze niż rozgałęzianie logiki bezpośrednio w szablonie.
- **`customer.service.ts`/`customer.service.spec.ts`: `importJson()` jest kompletnym lustrzanym odbiciem istniejącego `import()` (CSV)** — ten sam wzorzec `FormData` + `HttpParams`, ten sam kształt odpowiedzi (`Observable<{jobId: string}>`), z dedykowanym testem sprawdzającym zarówno metodę/URL, jak i realną obecność `deduplication` w `HttpParams` oraz `FormData` jako body — dobrze ukierunkowany test, nie tylko "czy się nie wywala".
- **i18n kompletne w 4 językach dla obu nowych kluczy** (`jsonFormatHint`, `invalidJsonArrayError`) — sprawdzone w `pl.json`/`en.json`/`de.json`/`uk.json`, żaden język nie został pominięty, a istniejące klucze (`sending`, `importButton`) zostały poprawnie ponownie użyte zamiast duplikowania.
- Przycisk główny na kroku upload poprawnie łączy stan `submitting()` z blokadą podwójnego kliknięcia (`[disabled]="!selectedFile() || submitting()"`) i pokazuje spinner tylko dla ścieżki JSON, gdzie kliknięcie od razu wywołuje sieciowe zapytanie (dla CSV przejście do "mapping" nie wymaga takiego stanu, bo nie ma jeszcze requestu) — rozróżnienie jest trafne.

### Summary

**Ocena: 4/5 ⭐** — czysta, kompletnie zaktualizowana migracja `allSteps`/`previewHeaders`/`previewRows` (potwierdzone brakiem osieroconych odwołań), dobrze zi18n-izowana nowa ścieżka JSON i solidny test serwisu. Jedyna realna luka to brak blokady dla pustej tablicy `[]` w podglądzie JSON, przez co użytkownik może niechcący wysłać pusty import bez ostrzeżenia — drobna, łatwa do naprawienia poprawka przed uznaniem funkcji za w pełni zamkniętą.

## Review: campaign-import.component.{ts,html,scss}, campaign-import.component.spec.ts (nowy), campaign.service.ts, i18n {pl,en,de,uk}.json — 2026-07-12

Kontekst: rozszerzenie modalu importu kontaktów kampanii wychodzącej o alternatywny format JSON (obok CSV), analogiczne do już zaimplementowanego `customer-import.component.ts` (patrz odpowiadający wpis w CR-FRONTEND.md z 2026-07-06). Pełny plan: `staged-yawning-raven.md`.

### 🐛 Bugs / Critical Issues

_Brak nowych, krytycznych błędów._

### ⚠️ Security Concerns

_Brak nowych zagrożeń._ `accept=".csv,.json"` na `<input type="file">` (`campaign-import.component.html:111`) to tylko podpowiedź UI — realna walidacja rozszerzenia i zawartości i tak następuje w `handleFile()` (ts:192-221) i niezależnie po stronie backendu (`validateJsonFile`/`validateFile`), więc nie ma ryzyka ominięcia walidacji przez spreparowaną nazwę pliku.

### 🏗️ Architecture / Pattern Violations

_Brak naruszeń._ Zweryfikowałem grep-em całego pliku i szablonu: migracja `allSteps` z zahardkodowanej tablicy (`readonly allSteps: ImportStep[] = [...]`) na `computed<ImportStep[]>()` (ts:97-101) jest **kompletna** — `getStepNumber` (ts:518-520) i `isStepCompleted` (ts:522-524) poprawnie wołają `this.allSteps()` zamiast trzymać własne, osobne, zahardkodowane tablice kroków (co było wprost wskazane w briefie jako ryzyko częściowego refaktoru). Nie znalazłem ani jednego pozostałego odwołania do starej sygnatury tablicowej ani osobnej listy kroków gdziekolwiek indziej w komponencie czy szablonie.

Deliberate, udokumentowana decyzja implementatora żeby NIE przenosić nazw `csvHeaders`/`csvPreviewRows` na `previewHeaders`/`previewRows` (jak w `customer-import.component.ts`) — plan nie wymagał tego renamingu, więc pozostawienie nazw generycznych-ale-nieprzemianowanych jest rozsądnym ograniczeniem zakresu diffu, nie naruszeniem wzorca.

### 🔧 Improvements & Suggestions

- **Podwójne parsowanie podglądu przy każdej selekcji pliku** — `handleFile()` (ts:214-220) jawnie woła `parseJsonPreview(file)`/`parseCsvPreview(file)` po ustawieniu `importFormat`/`selectedFile`, ale `effect()` w konstruktorze (ts:141-154) reaguje na te same sygnały i woła te same metody ponownie. Efekt: dla każdej selekcji pliku `FileReader.readAsText` i parsowanie (CSV lub JSON) wykonuje się dwukrotnie. Nieszkodliwe funkcjonalnie (idempotentne, ten sam plik → ten sam wynik), ale zbędne. To pre-existing wzorzec z części CSV sprzed tego PR (ten sam efekt występował już wcześniej dla `parseCsvPreview`) — rozszerzenie na JSON tylko go powielił, nie wprowadziło nowego problemu. Warto rozważyć usunięcie jednego z dwóch wywołań (np. polegać wyłącznie na `effect()` i usunąć jawne wywołanie w `handleFile`) przy najbliższej okazji porządkowania tego pliku.
- `stringifyPreviewCell` (ts:315-319) używa `JSON.stringify(value)` dla zagnieżdżonych obiektów w podglądzie (np. `customFields`) bez ograniczenia długości — dla nietypowo dużych zagnieżdżonych struktur mogłoby to rozwalić układ tabeli podglądu. Niski priorytet, ta sama uwaga co dla `customer-import.component.ts` w poprzednim review.
- Nowy `campaign-import.component.spec.ts` (pierwszy plik testowy dla tego komponentu) ma dobre pokrycie ścieżek z planu, ale nie zawiera jawnego testu dla `parseJsonPreview` odrzucającego pustą tablicę `[]` ani tablicę z elementem `null`/nie-obiektem — komponent POPRAWNIE obsługuje oba przypadki (patrz Positive Observations), ale brak testu regresyjnego oznacza że przyszła zmiana mogłaby po cichu cofnąć tę poprawność bez wykrycia. Warto dodać `it('rejects an empty JSON array')` i `it('rejects a JSON array containing a null element')`.

### ✅ Positive Observations

- **Pusta tablica JSON (`[]`) jest poprawnie odrzucana z ostrzeżeniem** — `parseJsonPreview` (ts:286-289): `if (records.length === 0) { this.setInvalidJsonState('supervisor.campaignImport.emptyJsonArrayError'); return; }`, co czyści `selectedFile` i blokuje przycisk kontynuacji. To realna **poprawa względem wzorca referencyjnego** `customer-import.component.ts`, gdzie dokładnie ten sam przypadek brzegowy (pusta tablica) był wcześniej zgłoszony jako bug w CR-FRONTEND.md (2026-07-06) — tam plik z pustą tablicą jest cicho akceptowany i można go wysłać bez ostrzeżenia. Tutaj implementator nie skopiował ślepo referencji, tylko naprawił ten konkretny przypadek.
- **`isArrayOfObjects` (ts:276-278) jawnie wyklucza `null` i tablice zagnieżdżone** (`item !== null && !Array.isArray(item)`) — poprawnie blokuje po stronie UI wysłanie pliku z elementem `null` w tablicy, co przy okazji chroni przed błędem backendu opisanym w CR-BACKEND.md (NPE przy elemencie `null`) dla ścieżki przez UI (choć backend powinien być odporny niezależnie, dla wywołań spoza UI).
- **Kontrakt i18n zweryfikowany jako w pełni spójny między namespace'ami** — nowe klucze `invalidJsonArrayError`/`emptyJsonArrayError`/`jsonFormatHint` poprawnie żyją pod `supervisor.campaignImport.*` z treścią mówiącą o "kontaktach" ("Plik JSON musi zawierać tablicę obiektów **kontaktów**"), a NIE pod `supervisor.customerImport.*` z treścią o "klientach" — sprawdzone we wszystkich 4 plikach (`pl`/`en`/`de`/`uk`), żaden nie ma niespójności. (Brief wskazywał, że tego typu niespójność — klucze wskazujące na "klientów" zamiast "kontaktów" — została już wcześniej znaleziona i naprawiona przez użytkownika; potwierdzam, że w obecnym stanie diffu żadna podobna niespójność już nie występuje, także w hintach/aria-labelach: `dropZoneAriaLabel`/`fileInputAriaLabel` poprawnie wspominają "CSV lub JSON" bez odniesień do klientów.)
- **Reużycie generycznych kluczy z `customerImport.*`** (`sending`, `nextMapping`, `separatorLabel`, `columnSeparator`, `quoteChar`) jest zasadne — to teksty niezależne od domeny (np. "Wysyłanie...") i był to już ustalony wzorzec w tym komponencie sprzed tego PR (nie wprowadzony przez ten diff).
- Kontrakt z backendem zweryfikowany 1:1: `campaignService.importContactsJson()` (campaign.service.ts:75-87) wysyła `FormData` z `file`/`skipDuplicates` na `POST .../contacts/import/json`, dokładnie zgodnie z `@RequestPart("file")`/`@RequestParam(defaultValue="true") boolean skipDuplicates` w `CampaignImportController` — bez żadnej rozbieżności mimo niezależnej implementacji przez backend i frontend.
- Nowy `campaign-import.component.spec.ts` (9 testów, wszystkie przechodzą) — dobre pokrycie: wybór formatu CSV vs JSON, `allSteps()` zawiera/nie zawiera `'mapping'`, zła ekstensja, `goToImportOrMapping()` dla obu formatów, błąd startu importu JSON (notyfikacja + powrót do `upload`), pełny cykl polling → `report` z `vi.useFakeTimers()`.
- `[disabled]="!selectedFile() || submitting()"` na przycisku głównym poprawnie łączy blokadę braku pliku z blokadą podwójnego kliknięcia podczas trwającego żądania — trafne rozróżnienie, spójne z tym jak wcześniej oceniono analogiczny przycisk w `customer-import.component.ts`.

### Summary

**Ocena: 4.5/5 ⭐** — implementacja nie tylko poprawnie odtwarza wzorzec referencyjny (kompletna migracja `allSteps()`, poprawny kontrakt z backendem, spójne i18n bez wycieku między namespace'ami klient/kontakt), ale w jednym miejscu (pusta tablica JSON) realnie go **poprawia** względem znanego wcześniej buga w `customer-import.component.ts`. Jedyne uwagi to drobna nadmiarowość (podwójne parsowanie podglądu, odziedziczone z części CSV) i brak paru testów regresyjnych dla przypadków brzegowych, które komponent już poprawnie obsługuje.

---

## Review: Refaktor ról SUPER_ADMIN/ADMIN/SUPERVISOR/AGENT (frontend, working tree na `rule-refactor`) — 2026-07-12

Kontekst: odpowiednik backendowego refaktoru ról — `SUPER_ADMIN` (nowa persona, `/admin`), `ADMIN` (tenant-scoped, przejmuje pełne menu dawnego SUPERVISOR pod `/supervisor`), `SUPERVISOR` (zawężony — traci 5 technicznych pozycji menu, zachowuje resztę). Pełny plan: `linked-questing-sedgewick.md`. Przegląd objął wszystkie niezacommitowane pliki frontendu: `jwt-payload.model.ts`, `auth.service.ts`/`auth.service.spec.ts`, `app.routes.ts`, `supervisor.routes.ts`, `sidenav.component.ts`, `login.component.ts`/`.html`, `public-tenant.service.ts`, `top-navbar.component.ts`/`.scss`, oraz i18n (`de/en/pl/uk.json`).

### 🐛 Bugs / Critical Issues

_Brak zidentyfikowanych._ Przeszedłem cały nowy przepływ logowania SUPER_ADMIN (`onSubmitEmail` → `isSuperAdminLogin` → pominięcie dropdownu tenanta → `onSubmitCredentials` wysyła `tenantId: ''`) oraz `roleGuard`/`app.routes.ts`/`supervisor.routes.ts` linia po linii — nie znalazłem przypadku, w którym `UserRole` mogłoby być `undefined`/`null` bez obsługi (`roleGuard` poprawnie przekierowuje do `/forbidden` gdy `!userRole`), ani przypadku wyścigu między `isSuperAdminLogin` a resetem stanu (`backToEmail()` poprawnie zeruje flagę).

### ⚠️ Security Concerns

_Brak nowych zagrożeń._ Zweryfikowałem:
- `roleGuard` (`role.guard.ts`) jest zastosowany na WSZYSTKICH 5 technicznych trasach (`email`, `integrations`, `twilio`, `ai-config`, `plugins`) z `roles: ['ADMIN']` — dokładnie zgodnie z planem, żaden nie został pominięty.
- `/admin` → `roles: ['SUPER_ADMIN']`, `/supervisor` → `roles: ['ADMIN', 'SUPERVISOR']` w `app.routes.ts` — to tylko UX-owa warstwa ochrony (prawdziwa autoryzacja jest po stronie API, poprawnie zweryfikowana w CR-BACKEND.md), ale jest spójna z backendem, więc SUPERVISOR realnie nie zobaczy w UI żadnej z 5 technicznych pozycji ani nie zdoła wejść na ich URL bezpośrednio (przekierowanie do `/forbidden` zamiast wycieku UI z niedziałającymi przyciskami).
- `jwt-payload.model.ts`: `tenant_id?: string` (opcjonalne) poprawnie odzwierciedla nowy kontrakt tokenu bez tego pola dla SUPER_ADMIN — sprawdziłem wszystkich konsumentów `currentTenantId`/`currentTenantName` (`websocket.service.ts`, `logging.service.ts`, plus komponenty w obszarze agent/supervisor) i każdy z nich obsługuje `null` bezpiecznie (`?? null`/`?? undefined`, `if (tenantId)`) — brak ryzyka wycieku wyjątku w runtime dla SUPER_ADMIN, mimo że SUPER_ADMIN i tak nigdy nie trafia do tych komponentów (żyją wyłącznie pod `/agent`/`/supervisor`).

### 🏗️ Architecture / Pattern Violations

_Brak naruszeń._ Wszystkie zmienione/nowe komponenty pozostają standalone (bez NgModule), stan logowania oparty w całości o `signal()`/`computed()` (`isSuperAdminLogin`, `showTenantSelect`), brak nieobsłużonych subskrypcji (wszystkie `.subscribe()` w `login.component.ts` mają jawne `next`/`error`, żaden strumień nie jest długożyjący więc `takeUntil` nie jest tu wymagane — spójne z resztą pliku sprzed zmian). `roleGuard` (funkcyjny, `CanActivateFn`) reużyty bez modyfikacji — dobra, minimalna zmiana zamiast pisania nowego guarda dla nowej roli.

### 🔧 Improvements & Suggestions

- **`frontend/src/app/features/admin/admin.routes.ts:7`** — breadcrumb dla shellu `/admin` nadal używa klucza i18n `role.admin` (tłumaczy się jako „Admin”), mimo że ta cała sekcja jest teraz wyłącznie dla `SUPER_ADMIN` (persona „Administrator Główny” / `role.superAdmin`, już dodana w tym samym diffie i użyta poprawnie w `top-navbar.component.ts` do wyświetlenia badge'a roli). Efekt: użytkownik zalogowany jako SUPER_ADMIN zobaczy w top-navbar badge „Administrator Główny”, ale breadcrumb strony powie „Admin” — drobna, ale realna niespójność nazewnictwa w UI, myląca zwłaszcza że w nowym modelu ról nazwa „Admin” oznacza teraz zupełnie inną (tenant-scoped) personę widoczną pod `/supervisor`. Sugestia: zmienić na `data: { breadcrumb: 'role.superAdmin' }`.

### ✅ Positive Observations

- **Przepływ logowania SUPER_ADMIN zaimplementowany dokładnie zgodnie z kontraktem z planu** (`{ tenants, superAdminAccount }`), z poprawnym fallbackiem przy błędzie sieci (`catchError` → `{ tenants: [], superAdminAccount: false }`, zachowuje istniejący wzorzec „nie ujawniaj czy e-mail istnieje”) i poprawnym resetem stanu (`isSuperAdminLogin.set(false)`) przy powrocie do kroku e-mail.
- **`filterNavItemsByRole` w `sidenav.component.ts` to czysta, minimalna, dobrze przemyślana abstrakcja** — rekurencyjnie filtruje też `children`, domyślnie pokazuje pozycję gdy `roles` nie jest podane (bezpieczny default, nie trzeba było oznaczać dziesiątek istniejących pozycji menu, tylko 5 nowych technicznych). Rozszerzenie interfejsu `NavItem` o opcjonalne `roles?: UserRole[]` jest w pełni wstecznie kompatybilne.
- **Konsekwentne przemianowanie `isAdmin`→`isSuperAdmin`/`showAlertBadge`** w `sidenav.component.ts` bez pozostawienia martwych odwołań w szablonie (zweryfikowane grep-em po `sidenav.component.html`) — potwierdzone też przez przechodzący `npm run build` wg opisu w briefie.
- **i18n w pełni skonsystentne w 4 językach** (`de/en/pl/uk`) — klucz `role.superAdmin` obecny i poprawnie przetłumaczony wszędzie (`"Administrator Główny"`, `"Super Admin"`, `"Hauptadministrator"`, `"Головний адміністратор"`), żaden język nie został pominięty.
- **`onSubmitCredentials()` poprawnie wysyła `tenantId: ''` dla SUPER_ADMIN** przez istniejący mechanizm `resolvedTenantId` zamiast dodawania nowej, równoległej ścieżki — minimalna, czytelna zmiana zamiast rozrostu złożoności formularza logowania.
- Testy `auth.service.spec.ts` i `login.component` (przechodzące wg briefu, 155/155) poprawnie zaktualizowane pod nową macierz `getRoleDefaultRoute` (`SUPER_ADMIN→/admin`, `ADMIN→/supervisor`, `SUPERVISOR→/supervisor`).

### Summary

**Ocena: 4.5/5 ⭐** — czysty, minimalny, w pełni spójny z backendem i planem refaktor. Jedyna uwaga to drobna kosmetyczna niespójność etykiety (breadcrumb `/admin` wciąż mówi „Admin” zamiast „Administrator Główny”) — nieblokująca, ale warta poprawki przy najbliższej okazji.

---

## Review: social-integrations.component.{ts,html,scss}, social-integration.model.ts, social-integration.service.ts, i18n {pl,en,de,uk}.json — WhatsApp manual connect — 2026-08-29

Przejrzane pliki:
- `features/integrations/pages/social-integrations/social-integrations.component.ts` / `.html` / `.scss`
- `features/integrations/models/social-integration.model.ts` (`WhatsAppConnectRequest`)
- `features/integrations/services/social-integration.service.ts` (`connectWhatsApp()`)
- `public/i18n/{pl,en,de,uk}.json`
- Kontekst pomocniczy (niezmieniony w tym diffie, ale istotny dla oceny): `core/interceptors/error-handler.interceptor.ts`

### 🐛 Bugs / Critical Issues

- **Podwójny toast błędu przy nieudanym podłączeniu WhatsApp.** `errorHandlerInterceptor` (`error-handler.interceptor.ts:19-31`) reaguje globalnie na KAŻDY `HttpErrorResponse`, chyba że request niesie `SKIP_ERROR_TOAST` w `HttpContext` — żądanie z `connectWhatsApp()` (`social-integration.service.ts:33-35`) tego kontekstu nie ustawia. Jednocześnie `submitWhatsappConnect()` (`social-integrations.component.ts:203-231`) ma własny `catchError`, który też pokazuje toast (`integrations.social.errorConnect`). Efekt: przy błędzie (np. backendowy 502 z `WhatsAppApiException`, patrz CR-BACKEND.md) administrator zobaczy DWA komunikaty naraz — ogólny z interceptora (`common.errorServer` dla >=500) i specyficzny z komponentu. To nie jest regresja wprowadzona tym PR-em — dokładnie ten sam wzorzec (własny `catchError` + brak `SKIP_ERROR_TOAST`) istnieje już w `connectPlatform()` i `confirmDisconnect()` w tym samym pliku (kod sprzed tego diffu) — ale skoro dotyka to nowego, wyeksponowanego flow z wrażliwymi danymi, warto to poprawić tu i przy okazji w obu istniejących miejscach: dodać `{ context: new HttpContext().set(SKIP_ERROR_TOAST, true) }` do wywołań HTTP, dla których komponent i tak pokazuje własny, bardziej szczegółowy komunikat.

### ⚠️ Security Concerns

- **Pole access tokenu renderowane jako zwykłe `<textarea>` (plaintext, bez maskowania) — `social-integrations.component.html:249-261`.** Wklejony permanentny token Meta (który w praktyce daje pełne uprawnienia do wysyłki wiadomości z danego numeru WhatsApp Business) jest przez cały czas widoczny na ekranie w postaci czystego tekstu. `autocomplete="off"` i `spellcheck="false"` są dobrymi, obecnymi już zabezpieczeniami (ograniczają zapamiętywanie przez przeglądarkę/słownik), ale nie chronią przed odczytaniem tokenu z ekranu (shoulder-surfing, współdzielenie ekranu podczas screen-share, automatyczne zrzuty ekranu w narzędziach supportowych). Sugestia: rozważyć wzorzec „reveal/hide” znany z pól haseł (domyślnie zamaskowane, przycisk „pokaż” do jawnego odsłonięcia) — biorąc pod uwagę, że to jedyne miejsce w aplikacji, gdzie tak długożyjący, wysoko uprzywilejowany sekret jest wpisywany ręcznie przez człowieka. Nie blokujące, ale warte rozważenia przy tak wrażliwych danych.
- Brak walidacji formatu/długości `accessToken`/`phoneNumberId` po stronie klienta (tylko `.trim().length > 0` w `whatsappFormValid`, `social-integrations.component.ts:99-104`) — niski priorytet, odpowiada brakowi `@Size` po stronie backendu (patrz CR-BACKEND.md), ale klient mógłby odciąć oczywiście błędne (np. jednoznakowe) wartości wcześniej, dając szybszy feedback niż round-trip do serwera.

### 🏗️ Architecture / Pattern Violations

_Brak naruszeń._ Komponent pozostaje standalone (bez NgModule), cały nowy stan formularza oparty o `signal()`/`computed()` (`connectingWhatsapp`, `whatsappFormValid` itd.) zgodnie z CLAUDE.md, `OnPush` zachowany, wszystkie nowe subskrypcje HTTP mają `takeUntilDestroyed(this.destroyRef)` (`social-integrations.component.ts:223`), selektor komponentu poprawnie z prefiksem `app-`. Formularz zbudowany na natywnym `<dialog>` + prostych sygnałach zamiast Reactive Forms — spójne z resztą pliku (disconnect dialog już wcześniej używał tego samego wzorca), więc nie jest to niespójność wprowadzona tym PR-em.

### 🔧 Improvements & Suggestions

- **Brak własnego pliku testowego dla komponentu.** `social-integrations.component.spec.ts` nie istnieje ani przed, ani po tym PR — więc żadna z 205 „przechodzących” testów frontendowych nie pokrywa nowej logiki (`whatsappFormValid`, `submitWhatsappConnect`, obsługa błędu/sukcesu, reset stanu przy `openWhatsappConnectDialog`). Biorąc pod uwagę, że to pierwszy w aplikacji formularz przyjmujący ręcznie długożyjący sekret dostawcy zewnętrznego, warto dodać chociaż podstawowy zestaw testów (walidacja wymaganych pól, wywołanie `connectWhatsApp` z poprawnie przyciętymi wartościami, zachowanie przy błędzie/sukcesie).
- **Brak potwierdzenia poprawności integracji po zapisaniu.** `loadIntegrations()` wywołane po sukcesie (`social-integrations.component.ts:229`) pokaże `webhookStatus` zwrócony przez backend — a backend obecnie zawsze ustawia `'ACTIVE'` przy zapisie (`SocialIntegrationServiceImpl.saveIntegration()`, linia 76), niezależnie od tego, czy podany token/numer faktycznie działają (patrz też sugestia w CR-BACKEND.md o pre-flight weryfikacji Graph API). Skutek po stronie UI: administrator zobaczy zieloną plakietkę „Aktywny” nawet dla nieprawidłowo wklejonych danych, dopóki agent nie spróbuje faktycznie wysłać wiadomości. To głównie konsekwencja braku walidacji po stronie backendu, ale warto o tym wiedzieć przy ocenie UX całego flow.
- Dialog `#whatsappConnectDialogRef` nie ma handlera na natywne zamknięcie klawiszem Escape (`(cancel)`/`(close)`) — `closeWhatsappConnectDialog()` z guardem `if (this.connectingWhatsapp()) return;` jest wywoływany tylko z przycisku „Anuluj”, więc Escape ominie ten guard. W praktyce nie prowadzi to do trwałego zablokowania UI (subskrypcja HTTP kończy się niezależnie od stanu dialogu i ostatecznie ustawia `connectingWhatsapp` z powrotem na `false`), ale warto rozważyć `(cancel)="$event.preventDefault()"` na dialogu, żeby zachować spójność z resztą logiki zamykania podczas trwającego żądania.

### ✅ Positive Observations

- **Formularz poprawnie zapobiega podwójnej wysyłce** — przycisk submit i pola input/textarea mają `[disabled]="connectingWhatsapp()"`, a `submitWhatsappConnect()` dodatkowo sprawdza `this.connectingWhatsapp()` przed wysłaniem.
- **Link do Meta Business Suite poprawnie zabezpieczony** — `target="_blank"` z `rel="noopener noreferrer"` (`social-integrations.component.html:211-216`), standardowa i poprawna ochrona przed tabnabbingiem.
- **Dostępność formularza przemyślana**: każde pole ma powiązany `<label for=...>`, `aria-required="true"`, `[attr.aria-invalid]` dynamicznie ustawiane po nieudanej próbie submitu, komunikaty błędów z `role="alert"`, textarea tokenu ma `aria-describedby` wskazujące na podpowiedź o poufności danych. To wyraźnie lepszy poziom a11y niż wymagane minimum.
- **`WhatsAppConnectRequest` przesyłany wyłącznie w ciele żądania POST** (`social-integration.service.ts:33-35`), nigdy jako query param — token nie ma szans trafić do access logów serwera czy historii przeglądarki przez URL.
- **i18n kompletne i spójne w 4 językach** — żaden z nowych kluczy (`whatsappConnectDialogTitle`, `phoneNumberIdLabel`, `accessTokenHint` itd.) nie został pominięty w żadnym z plików `pl/en/de/uk.json`, a treść `accessTokenHint` we wszystkich językach świadomie ostrzega użytkownika przed udostępnianiem tokenu.
- **Reset stanu formularza przy każdym otwarciu dialogu** (`openWhatsappConnectDialog()`) zapobiega przypadkowemu pozostawieniu poprzednio wpisanego tokenu w polu przy ponownym otwarciu dla innej platformy/próby.

### Summary

**Ocena: 3.5/5 ⭐** — Solidna, dostępna (a11y) implementacja formularza, poprawnie zintegrowana z istniejącymi wzorcami sygnałowymi i bez nowych naruszeń architektonicznych. Największe zastrzeżenia to brak testów dla nowej, bezpieczeństwo-wrażliwej logiki oraz UX pola tokenu (plaintext textarea) i podwójny toast błędu — żadne z nich nie jest blokujące samo w sobie, ale w połączeniu z brakiem pre-flight walidacji po stronie backendu (patrz CR-BACKEND.md) obniżają pewność, że administrator dowie się o błędnie wklejonych danych we właściwym momencie.

---

## Review: FE-112 — Komunikaty RODO: zakres anonimizacji (Art. 17) i zawartość eksportu (Art. 15) — 2026-09-30

Przejrzane pliki:
- `features/supervisor/pages/customers/gdpr.model.ts` (nowy)
- `features/supervisor/pages/customers/gdpr-anonymize-modal/gdpr-anonymize-modal.component.{ts,html,scss}` + `.spec.ts` (nowy)
- `features/supervisor/pages/customers/services/gdpr.service.ts`
- `features/supervisor/pages/customers/customer-list/customer-list.component.{ts,html,spec.ts}`
- `features/supervisor/pages/customers/customer-delete-modal/*` (usunięty, 3 pliki)
- `public/i18n/{pl,en,de,uk}.json`
- Kontekst pomocniczy (niezmieniony w tym diffie, ale istotny dla oceny): `core/interceptors/error-handler.interceptor.ts`, `features/supervisor/pages/customers/customer-detail/customer-detail.component.{ts,html}`, backendowe `AnonymizePreviewResponse.java`, `GdprServiceImpl.java`, `V098__mask_audit_log_pii_on_anonymize_customer.sql`

Weryfikacja niezależna: `npm run lint` (0 błędów / 10 pre-existing warningów, żaden w plikach FE-112), `npm run build` (sukces), `npm test` (247/247, 18 plików spec), `npx prettier --check` na wszystkich zmienionych plikach FE-112 (zgodne) — potwierdza wynik zgłoszony przez wykonawcę, uruchomione niezależnie.

### 🐛 Bugs / Critical Issues

- **[`gdpr-anonymize-modal.component.ts:105-118` (nowe `loadPreview()`) + `error-handler.interceptor.ts:19-31`] Podwójny komunikat błędu przy nieudanym załadowaniu podglądu zakresu anonimizacji.** `GdprService#previewAnonymize()` (`gdpr.service.ts:23-27`) nie ustawia `HttpContext` z `SKIP_ERROR_TOAST`, więc globalny `errorHandlerInterceptor` pokaże toast (np. `common.errorServer` dla 5xx, `common.errorForbidden` dla 403) dla KAŻDEGO prawdziwego `HttpErrorResponse` z tego wywołania — RÓWNOLEGLE z inline'ową sekcją `previewState === 'error'` w tym samym modalu (`gdpr-anonymize-modal.component.html:47-54`, własny komunikat + przycisk „Spróbuj ponownie"). Efekt: przy błędzie serwera (nie przy timeout/RxJS `TimeoutError` — ten nie jest `HttpErrorResponse`, więc interceptor go nie złapie) operator zobaczy DWA nakładające się komunikaty o błędzie w tym samym momencie, dokładnie podczas próby potwierdzenia nieodwracalnej operacji RODO. To dokładnie ten sam wzorzec, który był już raz zgłoszony w tym repo (`social-integrations.component.ts`, review 2026-08-29, patrz wyżej) — tam jako pre-existing, tu jako NOWO wprowadzony przez to zgłoszenie (poprzednio nie było żadnego HTTP-wywołania w `ngOnInit` tego modala). Fix: `this.http.get(url, { context: new HttpContext().set(SKIP_ERROR_TOAST, true) })` w `previewAnonymize()` — modal i tak ma własną, bardziej szczegółową obsługę błędu.
- **To samo dotyczy `GdprService#anonymize()` (`gdpr.service.ts:18-20`), wywoływanego z `onConfirm()` (`gdpr-anonymize-modal.component.ts:134-148`) — PRE-EXISTING (subskrypcja i własny toast błędu istniały przed tym diffem, ten PR tylko uprościł martwy `if/else`), ale warto poprawić przy okazji, skoro plik i tak jest w tym PR-ze modyfikowany.** Przy błędzie POST (w tym udokumentowanym 409 z `GdprServiceImpl.java:153`/`GdprController.java:107` — „Klient ma powiązany rekord w trakcie realizacji połączenia (DIALING/PROCESSING)") operator zobaczy zarówno globalny toast interceptora, jak i lokalny `errorAnonymize`.

### ⚠️ Security Concerns

_Brak nowych naruszeń bezpieczeństwa._ Endpointy GDPR bez zmian kontraktowych (dochodzi wyłącznie `GET .../gdpr/anonymize/preview`, bez efektów ubocznych, zweryfikowane przeciw `AnonymizePreviewResponse.java`/`GdprServiceImpl` — dry-run, brak zapisu do DB/S3/audit_log). Brak wrażliwych danych logowanych do konsoli, brak nowych pól formularza przyjmujących sekrety.

### 🏗️ Architecture / Pattern Violations

- **[`frontend/public/i18n/{de,uk}.json` — klucz `supervisor.gdprAnonymize.confirmHint`, wiersze `de.json:1652`, `uk.json:1652`] PRE-EXISTING (niezmienione w tym diffie — brak w `git diff`), ale bezpośrednio na ścieżce bramkowania potwierdzenia, którą ten ticket rozbudowuje — wart zgłoszenia teraz.** Kod bramkujący (`isConfirmEnabled`, `gdpr-anonymize-modal.component.ts:86-87`) sprawdza WYŁĄCZNIE dosłowny, niełamany na lokalizacje literał `'ANONIMIZUJ'`; identycznie w szablonie etykieta pola jest zawsze twardo zakodowana jako `<strong>ANONIMIZUJ</strong>` (`gdpr-anonymize-modal.component.html:91`, nietłumaczone, poprawnie spójne z kodem we WSZYSTKICH językach). Natomiast osobny, warunkowo widoczny podpowiedź-tekst `confirmHint` (pokazywany, gdy użytkownik wpisał coś niepoprawnego) JEST tłumaczony per-język i w `de.json` mówi „Geben Sie genau ein: **ANONYMISIEREN**", a w `uk.json` — „Введіть точно: **АНОНІМІЗУЙ**" (cyrylica) — czyli w obu przypadkach instruuje operatora, żeby wpisał słowo, którego kod NIGDY nie zaakceptuje (kod porówna zawsze z łacińskim `'ANONIMIZUJ'`). Dla supervisora na locale DE/UK: etykieta nad polem poprawnie mówi „ANONIMIZUJ", ale podpowiedź pod polem (widoczna właśnie wtedy, gdy user się myli i najbardziej potrzebuje jasnej wskazówki) kłóci się z nią i wskazuje inne słowo. Nie blokuje funkcjonalnie potwierdzenia (etykieta nad polem jest zawsze poprawna), ale to realne ryzyko dezorientacji operatora dokładnie w momencie krytycznego, nieodwracalnego potwierdzenia RODO. Fix: ujednolicić `confirmHint` we wszystkich 4 językach, żeby zawsze odwoływał się do tego samego, niełamanego na lokalizacje słowa `ANONIMIZUJ` (analogicznie do `<strong>ANONIMIZUJ</strong>` w szablonie) — albo (mniej pożądane) faktycznie zlokalizować SŁOWO WERYFIKOWANE przez `isConfirmEnabled()` per-język, co wymagałoby zmiany kodu, nie tylko i18n.

### 🔧 Improvements & Suggestions

- **[`gdpr-anonymize-modal.component.ts:105-118`, `134-148`] Brak `takeUntilDestroyed(this.destroyRef)` na obu subskrypcjach HTTP (nowej `loadPreview()` i pre-existing `onConfirm()`)** — niespójne z lokalną konwencją katalogu: `customer-list.component.ts` (ten sam PR, ten sam katalog) poprawnie wstrzykuje `DestroyRef` i używa `takeUntilDestroyed` na obu swoich subskrypcjach. Wpływ ograniczony (jawny `timeout(15000)` + `catchError` w `loadPreview()` gwarantuje zakończenie strumienia niezależnie od losu komponentu; `onConfirm()` nie da się przerwać w trakcie ładowania, bo `onCancel()`/`onEscapeKey()` blokują zamknięcie przy `isLoading()===true`), ale gdy użytkownik zamknie modal WCZEŚNIEJ — w trakcie ładowania samego podglądu (to jest dozwolone, `onCancel()` nie sprawdza `previewState`) — zawieszona subskrypcja `loadPreview()` żyje do 15 s po zniszczeniu komponentu i wtedy ustawia sygnały na już zniszczonej instancji. Nieszkodliwe (brak update'u widoku, brak wyjątku), ale warto ujednolicić dla higieny kodu.
- **[`gdpr-anonymize-modal.component.html:110-114`] `previewBlockedHint` („Poczekaj na załadowanie podglądu zakresu, aby potwierdzić anonimizację") renderuje się RÓWNOCZEŚNIE z blokiem `previewState === 'error'`** (bo warunek to `previewState() !== 'loaded'`, prawdziwy zarówno dla `'loading'`, jak i `'error'`). W stanie błędu użytkownik widzi więc jednocześnie komunikat błędu + przycisk „Spróbuj ponownie" ORAZ podpowiedź każącą mu „poczekać" — czekanie nic nie da bez kliknięcia retry. Drobna niespójność treści, warto dodać `@if (previewState() === 'loading')` do tego konkretnego hinta.
- **[`gdpr-anonymize-modal.component.ts:142-147`, `onConfirm()` error handler] Brak rozróżnienia kodu HTTP błędu przy anonimizacji** — `POST .../gdpr/anonymize` ma udokumentowany, świadomie zaprojektowany 409 (`GdprServiceImpl.java:153`, rekord podmiotu „w toku" DIALING/PROCESSING, zweryfikowane w BE-129 review 2026-09-24), ale FE pokazuje identyczny, ogólny komunikat `errorAnonymize` dla każdego błędu. To ten sam nawracający wzorzec co w `project_frontend_patterns.md` (Customer externalId, 2026-07-05: „generic catchError nie rozróżnia HTTP 409") — operator nie dowie się, że powinien spróbować ponownie PO zakończeniu trwającego kontaktu, tylko dostanie generyczne „spróbuj ponownie", co przy nieodwracalnej operacji jest gorszym UX niż konieczne.
- **[`gdpr.model.ts:28-56`] `GDPR_PREVIEW_COUNT_ORDER`/`GDPR_PREVIEW_COUNT_LABEL_KEYS` nie zawierają nowego klucza `audit_log`** (BE-142/V098, dodany RÓWNOLEGLE w tej samej turze przez `db-schema-architect` — `mask_audit_log_pii`, `v_counts := jsonb_build_object(..., 'audit_log', v_n_audit_log)` w `V098__mask_audit_log_pii_on_anonymize_customer.sql:546-557,743-754`). Potwierdzone NIEBLOKujące: mechanizm fallbacku (`GDPR_PREVIEW_COUNT_LABEL_KEYS[key] ?? key`, posortowany na koniec listy) jest udokumentowany, celowy i pokryty testem na identycznym przypadku (`unknown_future_table` w `gdpr-anonymize-modal.component.spec.ts:106-129`) — UI nie się wywali, po prostu pokaże surowy klucz `audit_log` zamiast ładnej etykiety. Drobny follow-up do rozważenia: dodać `audit_log` do obu stałych + klucz i18n `previewCount.auditLog` w 4 językach.
- **[`customer.service.ts:84`] `CustomerService#deleteCustomer()` jest teraz martwym kodem z perspektywy UI** — żaden komponent go już nie wywołuje po unifikacji na `GdprService#anonymize()` (potwierdzone grepem po całym `frontend/src`). Świadoma, udokumentowana decyzja z notatki wykonawcy (endpoint `DELETE /api/customers/{id}` nadal istnieje i przekierowuje 1:1 do tej samej logiki backendowej) — nic do naprawy teraz, ale kandydat do usunięcia lub oznaczenia `@deprecated` przy najbliższej okazji dotykania tego serwisu.

### ✅ Positive Observations

- **Unifikacja ścieżki anonimizacji zweryfikowana kompletnie, nie tylko na słowo wykonawcy.** Grep po całym `frontend/src` potwierdza zero pozostałych odwołań do usuniętego `CustomerDeleteModalComponent`/`customer-delete-modal` oraz zero osieroconych `openDeleteModal`/`showDeleteModal` w kontekście klientów (istniejące trafienia tych nazw należą do zupełnie innych, niepowiązanych funkcji — kolejki, użytkownicy, callbacki, grupy agentów). `customer-detail.component.ts`/`.html` (niezmienione w tym diffie) i `customer-list.component.ts` (zmieniony) sterują teraz dokładnie tym samym `GdprAnonymizeModalComponent`.
- **i18n w pełni kompletne i spójne w 4 językach dla WSZYSTKICH nowych/usuniętych kluczy tego ticketu** — `effect5`…`effect9`, cały blok `preview*`/`previewCount.*` obecny identycznie w `pl/en/de/uk.json`; usunięty blok `customerDelete.*` zniknął ze wszystkich czterech plików jednocześnie, bez osieroconych referencji nigdzie w `frontend/src` (zweryfikowane grepem). Jedyna znaleziona i18n-niespójność (`confirmHint` w de/uk, patrz wyżej) jest pre-existing, poza zakresem zmian tego diffu.
- **Bardzo solidne pokrycie testami dokładnie tej logiki, o którą prosił reviewer.** `gdpr-anonymize-modal.component.spec.ts` (nowy, 24 testy) pokrywa cały automat stanów bramkowania: loading blokuje mimo poprawnej frazy, error blokuje, timeout (15001 ms, `vi.useFakeTimers`) traktowany jak błąd i blokuje, retry po błędzie odzyskuje stan `loaded`, zła fraza blokuje mimo `loaded`. `customer-list.component.spec.ts` idzie o krok dalej niż zwykły stub-test — renderuje PRAWDZIWY `GdprAnonymizeModalComponent` (`By.directive(...)`) i steruje nim bezpośrednio, żeby dowieść identycznych komunikatów sukcesu/błędu jak na stronie szczegółów klienta, dokładnie zgodnie z AC.
- **Martwy kod poprawnie usunięty przy okazji:** stary `onConfirm()` błąd-handler miał `if (err.status === 403) {...} else {...}` z DOKŁADNIE tą samą treścią w obu gałęziach — słusznie uproszczone do jednego bezwarunkowego handlera, zero zmiany zachowania.
- **Zgodność z regułami CLAUDE.md dla frontendu potwierdzona:** wyłącznie standalone components, stan przez `signal()`/`computed()` (żadnego nadużycia `BehaviorSubject` dla pojedynczych wywołań HTTP), selektor `app-gdpr-anonymize-modal` z poprawnym prefiksem, Prettier zgodny (zweryfikowane niezależnie przez `prettier --check` na wszystkich zmienionych plikach FE-112). Obsługa Escape poprawnie przez `host: { '(document:keydown.escape)': ... }` (Angular-natywny binding do `document`), a nie `document.addEventListener` — świadome uniknięcie wcześniej zgłaszanego w tym repo anti-patternu.

### Summary

**Ocena: 3.5/5 ⭐** — Funkcjonalnie kompletna, dobrze przetestowana implementacja bez blokerów: bramkowanie potwierdzenia jest deterministyczne i pokryte testami dla wszystkich ścieżek błędu (HTTP error, timeout, ładowanie), unifikacja modala między listą a szczegółami klienta jest w 100% domknięta (zweryfikowana grepem, nie tylko czytaniem), a i18n jest kompletne dla wszystkich nowych kluczy w 4 językach. Dwie rzeczy obniżają ocenę: nowo wprowadzony podwójny toast błędu przy nieudanym podglądzie (recurring anti-pattern z tego repo, tym razem na krytycznej ścieżce RODO) oraz odkryta przy okazji, pre-existing niespójność i18n w `confirmHint` (DE/UK), która na tej samej, krytycznej ścieżce potwierdzenia mogłaby dezorientować operatora — żadna z nich nie blokuje działania (etykieta nad polem zawsze pokazuje poprawne słowo), ale obie warte poprawki przed uznaniem tematu za w pełni zamknięty, zwłaszcza biorąc pod uwagę wrażliwość operacji (nieodwracalna anonimizacja RODO).

### Poprawki (2026-09-30, zlecający)

Oba ustalenia z sekcji „🐛 Bugs" oraz i18n-niespójność z „🏗️ Architecture" — **NAPRAWIONE**:
- `GdprService#anonymize()`/`previewAnonymize()` (`gdpr.service.ts`) — dodano `{ context: new HttpContext().set(SKIP_ERROR_TOAST, true) }` do obu wywołań (wzorzec 1:1 z `social-integration.service.ts`, już istniejący w repo) — koniec podwójnego toastu.
- `de.json`/`uk.json` `supervisor.gdprAnonymize.confirmHint` — słowo do wpisania ujednolicone na niełamany na lokalizacje literał `ANONIMIZUJ`, zgodnie z `en.json` i etykietą pola (`<strong>ANONIMIZUJ</strong>`, zawsze nietłumaczona).

Zweryfikowane niezależnie po poprawkach: `npm run lint` (0 błędów), `npm run build` (sukces), `npm test` (247/247), `npx prettier --check` na zmienionych plikach (zgodne).

Pozostałe ustalenia (minor/nit: brak `takeUntilDestroyed`, `previewBlockedHint` widoczny razem z błędem, brak rozróżnienia HTTP 409, brak etykiety `audit_log`, martwy `CustomerService#deleteCustomer`) świadomie NIE naprawione teraz — niski wpływ, udokumentowane wyżej jako kandydaci do przyszłej poprawki.

---

## Review: FE-110 — Ustawienia > Retencja danych: opisy kategorii, odblokowanie „Usuń teraz" dla `CAMPAIGN_DATA`, notka w modalu purge (`data-retention.component.{ts,html,scss,spec.ts}`, `purge-confirm-modal.component.html`, i18n pl/en/de/uk) — 2026-10-10

### 🐛 Bugs / Critical Issues

_Brak błędów blokujących. `prettier --check` (komponent + 4 pliki i18n) czysto; zbiory kluczy `dataRetention.*` identyczne w pl/en/de/uk (porównane skryptem; jedyne różnice de/uk to 4 znane, niezwiązane klucze `customerDetail.contactStatusLabels.*`, odnotowane w tickecie)._

### ⚠️ Security Concerns

_None identified._ (Odblokowanie „Usuń teraz" dla `CAMPAIGN_DATA` nie omija potwierdzenia — nadal wymagany modal z frazą; autoryzacja po stronie backendu bez zmian.)

### 🏗️ Architecture / Pattern Violations

_None identified._ Standalone component, `OnPush`/signals zgodnie z istniejącym kodem, brak nowych subskrypcji.

### 🔧 Improvements & Suggestions

- **WAŻNE (treść, pewne) · `public/i18n/*.json` `categoryDescription.CAMPAIGN_DATA`** („Rekordy kontaktów kampanii wychodzących, w tym archiwum" / odpowiedniki). Purge `CAMPAIGN_DATA` usuwa WYŁĄCZNIE `campaign_contact_archive` (`purge_campaign_contact_archive`, por. `RetentionDataCategory`: „Zarchiwizowane dane kampanii"); aktywne rekordy `campaign_contact` nie są dotykane. Sformułowanie „kontaktów kampanii…, w tym archiwum" sugeruje szerszy zakres, a decyzja o nieodwracalnym usunięciu opiera się na tym opisie. Proponowana treść: „Zarchiwizowane rekordy kontaktów zakończonych kampanii wychodzących (archiwum)" (+ en/de/uk). Ponadto, dopóki `retention.campaign-archive.enabled=false` (BE-120), archiwum jest puste — opis/hint „Brak danych" jest wtedy poprawny, ale warto, by admin wiedział, skąd dane w archiwum się biorą (opcjonalnie).
- **DROBNE (pewne) · `summaryEligibleCountHint` / `columnRowsDeletedHint`** — tekst „suma różnych typów rekordów (np. kontakty, zdarzenia i wiadomości)" jest pokazywany dla WSZYSTKICH kategorii, także `TRANSCRIPTS`/`CAMPAIGN_DATA`, gdzie liczba dotyczy jednego/dwóch typów — wprowadza w błąd. Pokazywać hint tylko dla `CONTACT_INTERACTIONS` (analogicznie do `contactInteractionsNote`) albo uczynić tekst neutralnym.
- **DROBNE (a11y, pewne) · `data-retention.component.html` (`[title]` na `<p class="dr-summary-card__count">` i `<th>`)** — wskazówki dostępne wyłącznie przez natywny `title`: niedostępne z klawiatury, na urządzeniach dotykowych i dla części czytników ekranu. Zastąpić widoczną linią pomocniczą (`<small>`/`aria-describedby`) lub użyć istniejącego komponentu tooltipu projektu, jeśli jest.
- **DROBNE (spekulacja o utrzymaniu) · `purgeModal.contactInteractionsNote`** — statyczne zdanie „Załączniki zostaną trwale usunięte również z magazynu plików (S3)" jest prawdziwe przy `retention.purge.delete-messages=true` (domyślne w `application.yml`), ale fałszywe po wycofaniu flagi ENV (`RETENTION_PURGE_DELETE_MESSAGES=false`, ścieżka legacy tylko odcina referencje). Udokumentowana opcja wycofania powoduje wtedy rozjazd UI↔zachowanie; akceptowalne, o ile wycofanie jest rzadkie — rozważyć wystawienie flagi w DTO/`/api/retention`.
- **DROBNE (test) · `data-retention.component.spec.ts`** — `expect(descriptions.length).toBe(8)` jest kruche (dowolny nowy `.dr-category-description` łamie test); lepiej sprawdzać zawartość per kategoria. Tłumaczenia testowe nie zawierają kluczy `summaryEligibleCountHint`/`history.columnRowsDeletedHint` ani realnych plików JSON — brak testu parytetu kluczy 4 języków (obecnie tylko ręczne porównanie w PR); rozważyć prosty test Vitest porównujący zbiory kluczy `dataRetention.*` w `public/i18n/*.json`. Polyfill `HTMLDialogElement` w `beforeAll` jest poprawnie warunkowy.
- **DROBNE (CSS) · `data-retention.component.scss`** — `color: var(--text-2, var(--text-1))` — fallback do ciemniejszego koloru przy braku zmiennej; zmienne `--text-1/--text-2` istnieją w pliku, więc fallback zbędny.

### ✅ Positive Observations

- Spójne, kompletne i18n w 4 językach z poprawnymi diakrytykami (pl/de/uk), brak dziedziczonego problemu z brakującymi polskimi znakami.
- Dryf po BE-119 poprawnie wychwycony i naprawiony: `UNSUPPORTED_PURGE_CATEGORIES` = tylko `RECORDINGS` (zweryfikowane w `RetentionPurgeServiceImpl#validateSupportedCategory`), komentarze zaktualizowane.
- Nowy spec testuje zachowanie (stan przycisków `[false,true,false,false]` w DOM, widoczność noty tylko dla `CONTACT_INTERACTIONS`), nie tylko wywołania.
- Notatka w modalu poprawnie ostrzega o nieodwracalności usunięcia załączników z S3 przed potwierdzeniem.

### Summary

**Ocena: 4/5 ⭐** — czysta, kompletna zmiana zgodna z konwencjami (standalone, i18n 4 języków, prettier); do poprawy głównie treść opisu `CAMPAIGN_DATA` (sugeruje szerszy zakres niż faktycznie usuwany), ogólnikowe tooltipy dla kategorii jednotypowych i dostępność tooltipów opartych wyłącznie o `title`. Kryterium WP-4 (local-demo) pozostaje otwarte (zgodnie z ticketem).

---

## Follow-up: FE-110 / GdprAnonymizeModal — wspólny wzorzec frazy potwierdzenia dla modali destrukcyjnych — 2026-10-10 (tura 27, po WP-4)

### 🔧 Improvements & Suggestions

- **DROBNE (utrzymanie, pewne) · `purge-confirm-modal.component.ts`, `gdpr-anonymize-modal.component.ts`** — po poprawkach WP-4 oba modale niezależnie implementują tę samą logikę frazy potwierdzenia (tłumaczony klucz i18n, porównanie bez rozróżniania wielkości liter z `trim`, `confirmHint` z `{{ phrase }}`, blokada przycisku do zgodności). Każdy kolejny modal destrukcyjny będzie ją kopiował (i może powtórzyć błąd sztywnej frazy w jednym języku). Wyodrębnić wspólny komponent/dyrektywę (np. `cc-confirm-phrase-input` z inputem `phraseKey` i outputem `matches`) lub czystą funkcję `matchesConfirmPhrase(input, phrase)` w `shared/`, z jednym testem parytetu kluczy `*.confirmPhrase` w 4 językach. Follow-up, NIE założony jako ticket.
- **Weryfikacja wizualna POTWIERDZONA przez użytkownika 2026-10-10 (tura 28):** tabela „Polityki retencji” (wszystkie kolumny widoczne), tłumaczone frazy potwierdzenia w modalach purge i RODO oraz opisy kategorii działają.

---

## Review: FE-113 — okres archiwizacji kontaktów kampanii (commit 81c5561e: `campaign-archive-setting.component.{ts,html,scss,spec.ts}`, `retention.service.ts/.spec.ts`, `retention.model.ts`, `data-retention.component.{ts,html,spec.ts}`, i18n pl/en/de/uk) — 2026-10-18

### Zgodność z CLAUDE.md i kontraktem
- Standalone + `OnPush` + signals/`computed` + selektor `app-campaign-archive-setting` + brak NgModules: OK. `npx prettier --check` na zmienionych katalogach/plikach: czysto. Strona lazy i chroniona `roleGuard` (`roles: ['ADMIN']`), zgodnie z `@PreAuthorize("hasRole('ADMIN')")` kontrolera BE-151.
- Kontrakt BE-151: `CampaignArchiveSettingDto` (archiveAfterDays, isDefault, defaultDays, minDays, maxDays, warning?, updatedBy?, updatedAt?) i `CampaignArchivePreviewDto` (wszystkie 9 pól) zgodne 1:1; `updatedBy/updatedAt` poprawnie opcjonalne (pomijane przy null). Endpointy, parametr `archiveAfterDays`, `SKIP_ERROR_TOAST` na wszystkich trzech wywołaniach (brak podwójnego toasta — poprzednio powtarzający się błąd, tu uniknięty).
- i18n: 17 kluczy `campaignArchive.*` w pl/en/de/uk, test parzystości kluczy; `retry` i `common.cancel` istnieją we wszystkich 4 językach; polskie diakrytyki poprawne.

### Ustalenia (numerowane: waga / opis)

1. **[MAJOR] Polska (i angielska) pluralizacja w `confirmModal.previewResult`.** "{{ campaigns }} kampanii ({{ contacts }} kontaktów)" daje "1 kampanii", "1 kontaktów", "2 kampanii" (poprawnie "2 kampanie"), "3 kontaktów" ok, "22 kampanie" vs "22 kampanii" itd.; en: "1 campaigns". To powtarzający się antywzorzec projektu (patrz pamięć: Polish pluralization). Użyć ICU/pluralizacji Transloco (`messageformat`) albo sformułowania neutralnego, np. "Liczba kampanii: {{ campaigns }}, kontaktów: {{ contacts }}" (uk już ma taką formę "кампаній: N (контактів: M)" — spójne, bezpieczne). Dotyczy też `de` ("1 Kampagnen"). **[Poprawione w commicie `67c85f8c`.]**
2. **[MAJOR] Podgląd pokazuje sumę, nie przyrost.** Komunikat "Przy najbliższym przebiegu zostanie zarchiwizowanych: N kampanii" używa `campaignsToArchive`/`contactsToArchive` (łącznie przy nowym okresie), choć DTO ma `campaignsToArchiveAtCurrentDays`, `additionalCampaigns`, `additionalContacts`, `shortensPeriod` — wszystkie nieużyte. Kampanie, które i tak zostałyby zarchiwizowane przy obecnym okresie (np. job jeszcze nie przebiegł), są wliczone w "skutek zmiany", co zawyża skutek skracania i nie odpowiada pytaniu użytkownika "co zmienia moja decyzja". Pokazać przyrost ("dodatkowo X kampanii / Y kontaktów") plus ewentualnie "z czego Z i tak czeka na archiwizację przy obecnym okresie". Przynajmniej użyć `additional*` w przypadku `nothingToArchive` (obecnie 0/0 liczone na sumie: gdy `campaignsToArchiveAtCurrentDays > 0`, a przyrost = 0, komunikat "nic nie zostanie zarchiwizowane" byłby nieprawdziwy — dziś nie wystąpi tylko dlatego, że suma ≥ obecne, ale jest to niejawne założenie). **[Poprawione w commicie `67c85f8c`.]**
3. **[MAJOR, a11y] Dialog potwierdzenia — niedomknięte wymagania.** Dobre: natywny `<dialog>` + `showModal()` (pułapka fokusu, tło inert, Escape → `cancel` → `cancelConfirm()`), `aria-labelledby`/`aria-describedby`, `autofocus` na bezpiecznym "Anuluj". Braki: (a) wynik podglądu pojawia się przez podmianę węzła w `@switch` — `role="status"` jest tylko na stanie "loading", który znika; czytnik ekranu nie ogłosi wyniku. Zrobić jeden trwały kontener `aria-live="polite"` zawierający wszystkie stany podglądu; (b) destrukcyjne potwierdzenie bez `role="alertdialog"`; (c) brak testu Escape/fokusu (jsdom jest mockowany — patrz #7); (d) kliknięcie w backdrop nic nie robi (akceptowalne), ale warto świadomie to opisać. **[Poprawione w commicie `67c85f8c` (a11y: aria-live + alertdialog; brak dowodu przeglądarkowego dla Escape/fokusu).]**
4. **[MEDIUM] Wyścig odpowiedzi podglądu.** `openConfirm()` nie anuluje poprzedniej subskrypcji. Scenariusz: Zapisz(90) → Anuluj przed odpowiedzią → zmiana na 60 → Zapisz → spóźniona odpowiedź dla 90 nadpisze `preview` dla 60 (dialog pokaże liczby z innego okresu przy tekście "do 60 dni"). Poprawka: trzymać `Subscription`/użyć `Subject` + `switchMap`, anulować w `closeConfirm()`, i/lub odrzucać odpowiedź, gdy `dto.newDays !== parsedValue()`. Komponent nigdy nie weryfikuje `newDays`/`currentDays` z podglądu względem pola — a `currentDays` z backendu mógł się zmienić (inny admin) między załadowaniem a zapisem; tekst "z {{ from }}" bierze stary `setting().archiveAfterDays`, a nie `preview.currentDays`. **[Poprawione w commicie `67c85f8c`.]**
5. **[MEDIUM] `save()` nie jest idempotentne w trakcie otwierania dialogu.** `canSave` pozostaje true po pierwszym Enter/kliknięciu, więc szybkie podwójne wciśnięcie (`keydown.enter` na polu lub dwa kliknięcia przed renderem dialogu) wywoła `openConfirm()` dwa razy (dwa żądania podglądu, #4). Dodać `if (this.confirmOpen()) return;` w `save()`. **[Poprawione w commicie `67c85f8c`.]**
6. **[MEDIUM] Obsługa błędów — luki.** (a) 400 mapowane na `errorSaveValidation` — ok, ale nie ma testu 400; (b) 403 na GET pokazuje ogólne "nie udało się załadować" z przyciskiem Ponów, który nie ma sensu przy 403 (strona jest ADMIN-only, więc ryzyko niskie — uzasadnione, ale rozważyć rozróżnienie); (c) błąd PUT po potwierdzeniu: dialog jest już zamknięty (`closeConfirm()` przed `persist()`), komunikat pojawia się inline w sekcji pod polem — OK, ale użytkownik nie jest o tym informowany z miejsca dialogu; poza ekranem przy długiej stronie (sekcja nie robi `focus`/`scrollIntoView`; `role="alert"` ogłosi, ale widoczność nie). (d) 401/sesja — obsługa globalna, bez uwag. **[Poprawione w commicie `67c85f8c`.]**
7. **[MEDIUM, testy] Jakość specu.** Dobre: 12+ przypadków, `it.each` na wartości graniczne/nieprawidłowe, 422 i 403, brak toasta błędu, pokrycie stanu loading/error podglądu, parzystość kluczy i18n, spec serwisu sprawdza `SKIP_ERROR_TOAST`. Luki: (a) `HTMLDialogElement.showModal/close` zamockowane — nie testuje Escape (`cancel`), fokusu ani przywrócenia fokusu; (b) `component.save()` wołane bezpośrednio zamiast kliknięcia/Enter (nie testuje `keydown.enter` ani disabled przycisku w trakcie `saving`); (c) test "saves boundary/valid lengthening value" ma mylącą nazwę — dla `n=30` wpisuje 31 i oczekuje `toHaveBeenCalledWith(31)` przy odpowiedzi mocka `setting(30)`; przypadek `730` jest "wydłużeniem" tylko dzięki bazie 30; nie testuje górnej granicy jako skrócenia; (d) brak testów: stale-preview (#4), podwójne `save` (#5), 400, `updatedBy`, `warning` z API ignorowane; (e) test parzystości sprawdza tylko klucze, nie obecność placeholderów (`{{ min }}`, `{{ days }}`) w tłumaczeniach — literówka w parametrze w en/de/uk przeszłaby. **[Poprawione w commicie `67c85f8c` (testy komponentu 16→36).]**
8. **[MINOR] Pole liczbowe.** `type="number"` + `inputmode="numeric"` + ręczne `$any($event.target).value`: przy `type=number` przeglądarka zwraca `''` dla niepoprawnego tekstu (sam regex działa, ale testy "abc" w jsdom nie odzwierciedlają realnego zachowania Chrome — w Chrome nie da się wpisać "abc"). `$any` omija kontrolę typów — użyć `($event.target as HTMLInputElement).value` w metodzie lub `(input)="onInput(input.value)"` ze zmienną szablonową. `"1e2"` jest poprawną liczbą dla `type=number`, a regex `^\d+$` odrzuci ją z komunikatem — akceptowalne, ale warto wiedzieć. **[Poprawione w commicie `67c85f8c`.]**
9. **[MINOR] Format daty `'dd.MM.yyyy HH:mm'` zahardkodowany** niezależnie od języka (de/en/uk/pl) — sprawdzić zgodność z resztą aplikacji (data-retention.component używa jednego formatu?) i ewentualnie wydzielić wspólny pipe/format. `updatedBy` (UUID) nieprezentowane — świadome.
10. **[MINOR] Duplikacja stałych FALLBACK_*** (30/730/180) w komponencie — drugie źródło prawdy obok `minDays/maxDays` z API; akceptowalne jako stan przed pierwszym odczytem, ale wartości używane są dopiero po `setting() !== null` (`showValidationError`, `canSave`), więc fallback jest w praktyce martwy — usunąć albo skomentować.
11. **[MINOR] Pole `inputTouched` zadeklarowane po użyciu w `computed`** (`showValidationError` odwołuje się do `inputTouched` zdefiniowanego linię niżej). Działa (lazy), ale to pułapka przy refaktorze — przenieść deklarację wyżej.
12. **[INFO]** Nieużywane pola API (`warning`, `shortensPeriod`, `additional*`) — decyzja udokumentowana w modelu (tłumaczone ostrzeżenie zamiast tekstu z BE), ale patrz #2 dla `additional*`.

### Pozytywy
- Jeden toast sukcesu, błędy inline, `SKIP_ERROR_TOAST` w serwisie z komentarzem uzasadniającym; wydłużenie zapisuje od razu, skrócenie wymaga podglądu i potwierdzenia — rozsądny UX dla operacji nieodwracalnej.
- Błąd podglądu nie blokuje świadomego potwierdzenia, ale jest widoczny; `takeUntilDestroyed` na wszystkich subskrypcjach; `saving` blokuje podwójny zapis (`persist`/`confirmShorten`).
- Walidacja zakresu spójna z BE (30-730, całkowite); `aria-invalid`/`aria-describedby`/`role="alert"` na błędach.
- Test parzystości kluczy i18n w 4 językach, semantyka etykiety "dni od zakończenia" pilnowana w każdym języku.

### Co przed zamknięciem FE-113
- Wymagane: #1 (pluralizacja — albo forma neutralna), #2 (przyrost/spójność z `additional*`), #3a (live region dla wyniku podglądu), #4 + #5 (wyścig podglądu i podwójny `save`).
- Zalecane: #6c, #7 (testy: Escape/`cancel`, Enter, stale-preview, placeholdery), #8.
- Opcjonalne: #9-#11.

### Summary
**Ocena: 3.5/5** — czysta, zgodna z konwencjami i kontraktem BE-151 implementacja z dobrym UX potwierdzenia, ale komunikat skutku jest mylący (suma zamiast przyrostu, błędna pluralizacja), dialog nie ogłasza wyniku czytnikom ekranu, a asynchroniczny podgląd ma wyścig; testy nie pokrywają realnego zachowania `<dialog>`. Nie uruchamiałem testów ani przeglądarki (review statyczny).

---

## Review: Agent surface — hardening momentu połączenia przychodzącego (hotkeys, banner, ringing effect, dialogi, a11y) — 2026-10-21

Zakres: niezacommitowane zmiany w `frontend/` (25 zmodyfikowanych + nowe: `agent-hotkeys.service`, `agent-shortcuts-cheat-sheet`, `shared/utils/focus.util.ts`, 5 specyfikacji). Review statyczny, testów nie uruchamiałem.

### 🐛 Bugs / Critical Issues
- **[agent-hotkeys.service.ts:55 + incoming-call-alert.service.ts:87-104] (MAJOR, decyzja produktowa: NIE w obecnej formie)** Ctrl+Alt+Enter odbiera i `router.navigate(['/agent/desktop'])` z KAŻDEJ strony agenta. Na `agent-customers-tab` (`.html:114-123`) `<app-adhoc-email-modal>` i `<app-manual-callback-modal>` są pod `@if`, więc nawigacja niszczy komponent wraz z niewysłanym szkicem maila (edytor contenteditable, załączniki `PendingAttachment`) bez ostrzeżenia. Nie ma żadnego `canDeactivate` w aplikacji. Dodatkowo modal `showModal()` czyni banner `inert`, więc skrót jest jedyną drogą odebrania - ale kosztem utraty pracy. Poprawka: odbiór nie wymaga nawigacji (`SoftphoneService` jest rootowy). Gdy `document.querySelector('dialog[open]')` lub strona ma dirty form: odbierz, NIE nawiguj, zostaw trwały wskaźnik "Rozmowa w toku - przejdź do pulpitu" (chip w shellu) zamiast zdejmować banner (`dismissAlert()` linia ~100). Alternatywnie nawigacja dopiero po zamknięciu modala. Dopisać test (hotkey + otwarty dialog => brak `router.navigate`).
- **[incoming-call-banner.component.ts:23-25] (MAJOR)** `isVisible = computed(() => pendingAlert() !== null && !router.url.startsWith(...))` - `router.url` nie jest sygnałem, więc computed zależy tylko od `pendingAlert`. Alert utworzony na /agent/customers i późniejsza nawigacja ręczna na pulpit (menu, kliknięcie powiadomienia systemowego `incoming-call-alert.service.ts` ~`notification.onclick`) zostawia banner `role="alert"` WIDOCZNY na pulpicie, a alert odwrotnie (utworzony na pulpicie, ASYNC) pozostaje w pamięci. Skutek po tej zmianie: podwójne ogłoszenie czytnika (banner + sr-only `role="alert"` pulpitu), dzwonek gra dalej. Dopisane testy bannera nie pokrywają nawigacji (mockują stan). Poprawka: `url = toSignal(router.events.pipe(filter(e => e instanceof NavigationEnd), map(() => router.url)), {initialValue: router.url})`.
- **[incoming-call-alert.service.ts:376-401] (MAJOR)** ASYNC alert jest ustawiany także gdy agent jest na pulpicie (banner ukryty) i NIGDY nie jest zdejmowany, dopóki zakładka nie zostanie zamknięta (effect `setupAutoDismissEffect` sprawdza tylko istnienie taba). Agent, który już obsługuje ten kontakt, dostaje później "Nowy kontakt - Przejdź do kontaktu" po wyjściu na inną stronę (lub stale computed jak wyżej to maskuje niedeterministycznie). Poprawka: nie ustawiaj ASYNC alertu gdy `router.url` zaczyna się od `/agent/desktop`, i/lub dismiss gdy ten tab staje się `isActive` na pulpicie.
- **[disposition-panel.component.html:4 `(cancel)="$event.preventDefault()"`] (MAJOR, do weryfikacji w Chrome)** W Chromium (CloseWatcher) drugie kolejne żądanie zamknięcia bez aktywacji użytkownika (Esc nie jest aktywacją) nie jest anulowalne: dwukrotny Esc zamknie `<dialog>` natywnie, mimo `preventDefault()`. Wtedy ACW można ominąć, a stan komponentu rozjeżdża się z DOM. `dialog-escape.spec.ts:152` testuje tylko syntetyczny `cancel` w jsdom, więc tego nie wykryje. Poprawka: na `(close)` ponownie wywołać `showModal()` gdy tab nadal `WRAPPING`, lub `keydown.escape` z `preventDefault()` na dialogu (keydown jest zawsze anulowalny).
- **[agent-hotkeys.service.ts:56-66 vs agent-desktop.component.ts:~233-320] (MAJOR/do potwierdzenia)** Maszyna stanów połączenia (ENDED => AFTER_CONTACT + `markAsWrapping`; ACTIVE => BUSY) żyje w efektach `AgentDesktopComponent`, a skróty działają globalnie. Ctrl+Alt+End/M na stronie spoza pulpitu zmienia sesję bez tych efektów. Efekty odpalą się po montażu pulpitu tylko jeśli sesja nadal ma stan ENDED/ACTIVE; jeśli sesja zostanie wyczyszczona do `null` wcześniej, ACW/disposition przepada. Sprawdzić czy ENDED jest trwały do czasu dispositioning; docelowo przenieść te efekty do rootowego serwisu. Analogicznie `answerRingingCall()` ustawia ACTIVE przed zamontowaniem pulpitu: agent nie jest BUSY do czasu nawigacji (okno wyścigu z dialerem).
- **[incoming-call-alert.service.ts:107-121 vs softphone.component.ts:126 `reject()`] (MINOR-MAJOR)** Niespójność: odrzucenie skrótem/bannerem zamyka tab PHONE (`closeTab`), a przycisk "Odrzuć" w softphonie (`softphone.reject()` => `rejectCall()`) tab zostawia. Ten sam użytkownik dostaje dwa różne zachowania. Wynieść zamknięcie taba do jednego miejsca (np. `SoftphoneComponent.reject()` wywołuje `alerts.rejectRingingCall()`), zamiast duplikować logikę.

### ⚠️ Security Concerns
- **[agent-shortcuts.model.ts:14 / agent-hotkeys.service.ts:59]** Ctrl+Alt+End to w sesjach RDP/Citrix/VDI (typowe dla contact center) sekwencja Ctrl+Alt+Del - skrót "rozłącz" będzie przechwycony przez klienta zdalnego pulpitu lub wywoła ekran bezpieczeństwa hosta. Na Macu brak klawisza End. Wybrać inny kod (np. Ctrl+Alt+Backspace/H) lub zrobić skróty konfigurowalne. Poza tym brak: PII/XSS (cheat sheet i alert korzystają z interpolacji, brak `innerHTML`).

### 🏗️ Architecture / Pattern Violations
- **[agent-hotkeys.service.ts:51]** Opis (i nazwa testu `agent-hotkeys.service.spec.ts:131`) mówi, że AltGr jest "jawnie ignorowany", a kod tylko NIE wiąże liter. Nie ma `event.getModifierState('AltGraph')`. Na układach, gdzie AltGr+M lub AltGr+Enter generuje znak (np. niektóre układy z AltGr+M = µ), skrót "wycisz" wygra z pisaniem. Dodać `if (event.getModifierState?.('AltGraph')) return;` i test dla `KeyM` z AltGraph. Obecny test sprawdza wyłącznie klawisze, których i tak nie ma w `switch`, więc jest tautologiczny.
- **[incoming-call-alert.service.ts:376-401 + agent-desktop.component.ts:~358-365]** Duplikacja otwierania tabów między serwisem a pulpitem to teraz dwa źródła prawdy z kolejnością zależną od kolejności subskrypcji WS. Działa (oba pomijają, gdy tab istnieje), ale komunikat o limicie jest tłumiony po stronie serwisu na podstawie `router.url`, więc na pulpicie zależy od tego, że pulpit zdąży ze swoim bannerem. Lepiej: jedyny właściciel (serwis), a pulpit tylko reaguje na `tabStore`.
- **[agent-desktop.component.ts:~360]** Nowy `notifications.info(payload.customerName)` przy istniejącym tabie wywoła toast także gdy tab już został otwarty przez ten sam event (zamierzone), ale też przy ponownym dostarczeniu tego samego CONTACT_ASSIGNED (WS reconnect) - bez deduplikacji.
- **[adhoc-email-modal.component.ts:32 + 310 `onEscapeKey` oraz `(cancel)` w html]** Ta modalka ma teraz dwa mechanizmy Esc: globalny `document:keydown.escape` (zamyka modal nawet gdy fokus jest poza nim, i zgubi szkic bez potwierdzenia) oraz nowe `(cancel)`. Zostawić jeden (`(cancel)`), usunąć host binding.
- **[shared/utils/focus.util.ts:rememberFocus + pola klas w 5 modalach]** `rememberFocus()` przy tworzeniu komponentu (pole klasy) działa tylko, gdy modal jest tworzony przez `@if` po kliknięciu (tak jest w `agent-customers-tab.component.html`). Jeśli któryś z 5 dialogów zostanie kiedyś osadzony bez `@if`, zapamięta `<body>`. Udokumentować ograniczenie w `focus.util.ts`.

### 🔧 Improvements & Suggestions
- **[agent-desktop.component.ts:264-293 ringingEffect]** Poprawne: efekt kluczowany na `computed` `sessionState`, więc nie odpala się na tikach `duration`; zapisy w `untracked`, brak pętli; `afterNextRender` z `injector` jest właściwy. Luki: (a) przejście RINGING => RINGING (nowy dzwoniący bez pośredniego `null`) nie odpali ponownie - dziś chroni tylko limit MAX_PHONE; (b) ogłoszenie korzysta z `session.customerName` z chwili RINGING (zwykle jeszcze "Nieznany"/numer przed wzbogaceniem CLI, 100-500 ms później), a bez `who` zostaje "Połączenie przychodzące: " z wiszącym dwukropkiem (`:278`); (c) `afterNextRender` fokusuje element także gdy otwarty jest modal `showModal()` (inert) - cichy no-op, ale kalendarz i tab i tak są przełączane pod modalem.
- **[incoming-call-banner.component.html:2 + incoming-call-alert.service.ts:269,421]** `role="alert"` + późniejsza zmiana `customerName` (wzbogacenie CLI) = czytnik ogłasza cały banner drugi raz (alert ma domyślnie `aria-atomic=true`). Wymóg "ogłosić raz": ogłaszać z oddzielnego, stabilnego regionu, a nazwę aktualizować poza live regionem lub `aria-live="off"` na wewnętrznym bloku.
- **[agent-hotkeys.service.ts:72-81]** Esc zamyka cheat sheet z dowolnego miejsca, także gdy fokus jest w menu statusu lub w otwartym dialogu (zamknie obie rzeczy jednym Esc). `?` otwiera nie-modalny arkusz pod otwartym `showModal()` (top layer przykryje `z-index: 8000`, a `.focus()` na panelu nie zadziała - inert). Zablokować `?` gdy `dialog[open]`.
- **[agent-desktop.component.html: przycisk skrótów]** Brak `aria-controls`/`aria-haspopup="dialog"`; przycisk istnieje tylko na pulpicie, a `?` działa wszędzie - dodać wejście w innych stronach agenta lub w nagłówku shella.
- **[agent-desktop.component.ts:onTabKeydown]** Strzałki wywołują `tabStore.setActiveTab` bezpośrednio, ale gdy aktywny jest widok kalendarza (`calendarTabActive`), zakładka nie zamyka kalendarza (to samo robi kliknięcie, więc spójne, ale "selection follows focus" jest tu pozorne). `tabindex` oparty na `activeTab()?.id` pozostawia pasek bez żadnej tabbable zakładki, gdy `activeTab()` jest `null`.
- **[onStatusMenuKeydown]** Poprawny wzorzec menu button (strzałki, Home/End, Esc zwraca fokus, Tab zamyka). Brak type-ahead (opcjonalnie). Overlay `status-menu-overlay` kliknięciem nie zwraca fokusu (`closeStatusMenu()` bez `true`) - fokus ginie, bo element menu znika.
- **[agent-shortcuts-cheat-sheet.component.scss:15]** `box-shadow: var(--shadow-lg)` na panelu, który przykrywa treść - zgodne z "cień tylko gdy coś przykrywa", ale DESIGN.md wskazuje `--shadow-pop` dla menu/popoverów; `--shadow-lg` jest dla "dużych paneli". Zmienić na `--shadow-pop`. Tokeny kolorów OK (brak hex; `--accent-fg` to token). Banner `fill="white"` w SVG to dług sprzed zmiany.
- **i18n:** komplet nowych kluczy (`incomingCall.labelAsync/goToContact/hide`, `desktop.ringingAnnouncement(+Queue)`, `shortcuts.*`) obecny w pl/en/de/uk, diakrytyki poprawne. Martwe klucze w 4 plikach: `agent.incomingCall.dismiss` (nieużywany; wcześniej banner miał na sztywno `aria-label="Zamknij"`) oraz `agent.incomingCall.goToDesktop` (nieużywany po zmianie). Usunąć oba.
- **Testy:** `incoming-call-alert.service.spec.ts` nie pokrywa: odbioru gdy nadejdzie dopiero potem połączenie Twilio, wyścigu ASYNC vs PHONE w drugą stronę (PHONE nadpisuje ASYNC - tak, ale po zakończeniu rozmowy ASYNC przepada), dedup przy pulpicie. `agent-hotkeys.service.spec.ts` nie testuje `event.repeat`, `isComposing`, ani `Ctrl+Alt+End` w stanie `ON_HOLD`. `dialog-escape.spec.ts` testuje syntetyczne `cancel`, nie realne zachowanie Chromium (patrz disposition). Brak testu nawigacji bannera (patrz `isVisible`).

### ✅ Positive Observations
- Listener hotkeys rejestrowany raz, sprzątany przez `DestroyRef.onDestroy`; serwis dostarczony na poziomie `AgentShellComponent`, nie root - dobry zasięg życia; test sprzątania istnieje.
- `preventDefault()` tylko gdy akcja faktycznie się wykonała; dopasowanie po `event.code` (niezależne od układu), `repeat`/`isComposing` odfiltrowane.
- `answerRingingCall()` poprawnie obsługuje wyścig z Twilio: `SoftphoneService.handleIncomingCall` auto-akceptuje, gdy sesja jest już ACTIVE (`softphone.service.ts:~176-186`), a `answerCall()` akceptuje zapamiętane `activeCall`. Zwracane `boolean` pozwala `preventDefault` tylko przy faktycznym skutku.
- ASYNC nie nadpisuje dzwoniącego PHONE alertu (`incoming-call-alert.service.ts:~391`); dzwonek i powiadomienie systemowe tylko dla PHONE.
- Wzorzec WAI-ARIA menu (`menuitemradio`, `aria-checked`, roving fokus, powrót fokusu) i tabs (roving tabindex, zignorowanie klawiszy z zagnieżdżonego przycisku zamknięcia) zrealizowany poprawnie; `viewChild`/`viewChildren` sygnałowe, OnPush zachowany.
- `onNativeCancel` w 4 modalach kieruje natywny Esc przez tę samą ścieżkę co "Anuluj", więc outputy i stan się nie rozjeżdżają; `rememberFocus` z `isConnected` jest bezpieczne.
- Tokeny OKLCH zamiast hex, brak cienia w stanie spoczynku bannera.

### Podsumowanie priorytetów
- **Krytyczne:** brak (nie ma utraty danych backendowych ani luki bezpieczeństwa).
- **Major:** (1) Ctrl+Alt+Enter + nawigacja niszczy szkic maila/callbacku; (2) `isVisible` nie reaguje na nawigację (stale banner, podwójne ogłoszenie); (3) ASYNC alert ustawiany na pulpicie i nigdy nie zdejmowany; (4) `preventDefault` na `cancel` w disposition nie wytrzyma drugiego Esc w Chromium; (5) efekty ACW/BUSY uwiązane do pulpitu przy globalnych skrótach; (6) Ctrl+Alt+End koliduje z Ctrl+Alt+Del w RDP/VDI.
- **Minor:** brak jawnego `AltGraph`, tautologiczny test AltGr, podwójne ogłoszenie przy wzbogaceniu nazwy, niespójne reject (tab zamykany tylko skrótem/bannerem), podwójny mechanizm Esc w adhoc-email, `--shadow-lg` vs `--shadow-pop`, martwe klucze i18n `dismiss`/`goToDesktop`, luki w testach.

### Summary
**Ocena: 3.5/5** - dobrze zbudowane a11y (menu/tabs, live region, cleanup listenerów, poprawne efekty sygnałowe) i spójne i18n w 4 językach, ale globalny skrót odbioru z nawigacją może po cichu zniszczyć niezapisaną pracę, a stan widoczności bannera i sprzątanie ASYNC alertów są niepoprawne reaktywnie.

---

## Review: FE-115 (kontrast tokenów) + FE-117 (landmarki i fokus) — 2026-10-23

Zakres: niezacommitowane zmiany na `feature/epic-32-agent-ux-a11y` (styles.scss, DESIGN.md, design.json, agent-desktop.html, breadcrumb.service, agent.routes, 3x SCSS fokusu, 2 nowe specy). Kontrasty zweryfikowane skryptem `c.py` (zgodne z deklaracjami w DESIGN.md: on-fill 4,87/5,32/5,20 jasny, 6,32–7,75 ciemny; text-muted min 4,89 jasny / 5,71 ciemny; border-control min 3,72 jasny / 3,34 ciemny).

### 🐛 Bugs / Critical Issues
- **app-shell.component.html:14-16 / app-shell.component.scss:55-61 (FE-117)** Po `hideBreadcrumbs` komponent `cc-breadcrumbs` renderuje nic, ale wrapper `.shell__breadcrumbs` zostaje (`min-height: 34px`, padding, `border-bottom`, tło). Na `/agent/desktop` zostaje więc pusty pasek z linią — kryterium "breadcrumb nie pojawia się" jest spełnione tylko dla `<nav>`, wizualnie regresja. Fix: warunkowo renderować wrapper (`@if (breadcrumbService.breadcrumbs().length)` w shellu) albo przenieść styl wrappera do `.breadcrumbs`.
- **app-shell.component.scss:14 (FE-115/117)** `.skip-link` ma `color: #ffffff` na `background: var(--accent)`. W ciemnym motywie accent to 70% L, więc biały tekst ma ok. 2,9:1 (poniżej 4,5:1) — dokładnie klasa błędu, którą naprawia FE-115. Fix: `color: var(--on-accent)`.

### ⚠️ Security Concerns
_None identified._

### 🏗️ Architecture / Pattern Violations
- **app-shell.component.html:2 (WP-2, FE-117)** Tekst skip-linku "Przejdz do tresci" jest na sztywno, bez diakrytyków i bez i18n (aplikacja ma 4 języki). Ticket dotyka tego elementu (cel `#main-content`, test skip-linku), więc to naruszenie WP-2 w zakresie FE-117. Fix: klucz transloco (np. `a11y.skipToContent`) w 4 plikach języków + "Przejdź do treści".
- **styles.scss:81-83,139-141 (FE-115)** Tokeny `--on-danger` i `--on-success` są zdefiniowane, ale nie ma żadnego konsumenta (grep: 0 użyć). Teksty na `--danger`/`--success` nadal używają `--accent-fg` (np. incoming-call-banner.component.scss:15 tekst na `--danger`). Działa dziś tylko dlatego, że wszystkie trzy `on-*` mają tę samą wartość. Zmigrować te użycia albo nie dodawać martwych tokenów (FE-116 może to zrobić — odnotować).
- **email-contact.component.scss:410-416, adhoc-email-modal.component.scss:358-364 (FE-117)** Zagnieżdżony `&:focus-visible` wstawiony w środek deklaracji; kolejne deklaracje (`font-size`, `color`, `overflow-y`…) po regule zagnieżdżonej wywołują ostrzeżenie `mixed-decls` w Dart Sass 1.97 (zmiana semantyki kolejności). Przenieść blok na koniec reguły.

### 🔧 Improvements & Suggestions
- **styles.scss:240-246 (FE-115)** `border-color: var(--border-control)` w globalnej regule `select, input, textarea` jest bezpieczna: brak Angular Material (nie ma w package.json), natywny checkbox/radio ignoruje `border-color` bez `appearance:none`, a komponenty z własnym `border: … var(--border-1/2)` (specyficzność klasy) i tak nadpisują regułę elementową. Skutek uboczny: reguła realnie działa tylko na niestylowane inputy; kryterium "obramowania kontrolek >= 3:1" nie jest więc spełnione dla większości kontrolek, które mają własny `--border-1/--border-2` (np. login, queue-assignment-panel, routing-rule-form). Dopisać w notatce zakres i/lub wskazać FE-116/FE-124 do migracji; sprawdzić w przeglądarce.
- **styles.scss:32-34 (FE-115)** Warstwa legacy `--color-text-*`: `secondary`/`muted` przepisane na oklch (spójnie z tokenami), `--color-text-primary` zostaje hex `#1e293b` — OK kontrastowo, ale mieszanie hex/oklch w jednej warstwie; drobna niespójność z Token-Only Rule, do wygaszenia w FE-116.
- **styles.scss (FE-115) warianty hover/soft** 205 użyć `color-mix(... var(--accent|danger|success) ...)` — to mieszanie w oklch z procentami (tło przezroczyste/black), więc ściemnienie accenta (58→55, danger 60→55, success 58→52) skaluje je proporcjonalnie, bez rozjazdu; `--accent-soft`/`-text` bez zmian (accent-text 45% vs accent 55% — hierarchia zachowana). Nie znalazłem miejsca, które by się psuło; zalecany przegląd wizualny hover/active (login.component.scss:239 `accent 85% black` jest teraz ciemniejszy niż wcześniej, nadal >= 4,5:1 z białym).
- **incoming-call-banner.component.scss:58-62 (FE-115)** CTA: `background: var(--accent-fg)` + `color: var(--danger)` — w jasnym motywie 5,32:1, w ciemnym graphite-na-danger-tekst: poprawne, ale semantycznie `--on-danger`. Dotyczy tylko spójności nazw.
- **breadcrumb.service.ts:40 (FE-117)** `hideBreadcrumbs` działa poprawnie także dla dzieci `desktop` (zwrot `[]` przed rekurencją i odrzucenie nagromadzonych elementów rodzica). Uwaga: Angular domyślnie dziedziczy `data` do dzieci tras bez komponentu/pathless — flaga na takim węźle ukryje breadcrumby całego poddrzewa; warto to wpisać w komentarz przy flagę. Dodać krótki komentarz/JSDoc oraz test dla trasy zagnieżdżonej (dziecko `desktop`).
- **agent-desktop.component.html:259 (FE-117)** `<section aria-label>` jest poprawną landmarką (region). Etykieta `agent.desktop.activeContactsLabel` ("aktywne kontakty") nie opisuje widoku kalendarza, który renderuje się w tej samej sekcji (`calendarTabActive()`); rozważyć etykietę dynamiczną. Sprawdzić selektory SCSS `main` po usunięciu drugiego `<main>` (grep: brak selektorów `main` poza `__main`; `.desktop__main` zachowany — OK).
- **Fokus ring (FE-117)** `2px solid var(--accent)`: jasny 4,87:1 na bg-surface, ciemny accent 70% L na ciemnych powierzchniach > 3:1 — spełnia kryterium. `outline-offset: -2px` poprawne przy `overflow-y: auto` (ring nie jest ucinany). `border-radius: 0` przy edytorach zbędnie nadpisuje globalne 4px; opcjonalnie usunąć.
- **Testy (FE-117)** `breadcrumb.service.spec.ts` — sensowny (ścieżka z flagą i bez, asercja na etykietach). `app-shell.component.spec.ts` sprawdza tylko powłokę ze stubami: kryterium ticketu ("jeden `<main>` w powłoce + pulpicie") nie jest pokryte — brak renderu `AgentDesktopComponent` (ciężki, ale test `querySelectorAll('main')` na jego HTML/szablonie mógłby być prostą asercją). Test nie dowodzi, że `section` nie zostanie z powrotem zamieniony na `main` w pulpicie. Brak testów fokusu (SCSS — akceptowalne, ale kryterium wymaga sprawdzenia w przeglądarce — brak śladu w diffie).
- **Spójność dokumentacji (FE-115)** Wartości w DESIGN.md, design.json i styles.scss są zgodne (text-2/3/muted, border-control, accent, success, danger, accent-fg). Drobiazg: `accent-fg` w DESIGN.md/design.json zmienione z `#ffffff` na `oklch(100% 0 0)` bez potrzeby (wartość identyczna); design.json nie zawiera nowych tokenów `on-accent/on-danger/on-success` (DESIGN.md je opisuje w prozie, ale nie w YAML `colors`) — dopisać dla pełnej synchronizacji (kryterium AC5). `border-2` przemianowany "Linia pomocnicza" — OK.
- **WP-7 (impeccable)** W diffie brak śladu użycia skilla impeccable (poza tym, że DESIGN.md/design.json zostały zaktualizowane w jego formacie i dodana reguła "Contrast Floor Rule"). Nie da się tego zweryfikować z kodu; potwierdzić w notatce wykonania.
- **Notatka wykonania / tabela przed-po (AC1)** Nie ma jej w przeglądanym diffie (TASKS-FRONTEND.md poza zakresem) — upewnić się, że jest dopisana.

### ✅ Positive Observations
- Wartości tokenów policzone skryptem OKLCH→WCAG i zgodne z deklaracjami; hierarchia text-1/2/3/muted zachowana (różnice w 42/47/51% L), dark-mode text-muted podniesiony z ~2,8 do >= 5,7:1.
- `--accent-fg` zachowany jako alias `--on-accent` — brak zmiany dla ~55 istniejących użyć; poprawne w obu motywach (ciemny tekst na jaśniejszym akcencie).
- Osobny `--border-control` zamiast zaostrzania `--border-1` — karty i tabele zachowują subtelność.
- `hideBreadcrumbs` jako deklaratywna flaga trasy, minimalny diff, test obejmuje ścieżkę pozytywną i regresyjną.
- Fokus z `outline-offset: -2px` poprawnie dobrany do kontenerów z `overflow`.

### Wymagane (przed merge)
1. app-shell.component.html:14 / .scss:55 — pusty pasek `.shell__breadcrumbs` na `/agent/desktop`.
2. app-shell.component.scss:14 — skip-link `#fff` na accent w ciemnym motywie (~2,9:1) → `var(--on-accent)`.
3. app-shell.component.html:2 — skip-link bez i18n/diakrytyków (WP-2).

### Zalecane
1. email-contact.component.scss:410, adhoc-email-modal.component.scss:358 — przenieść `&:focus-visible` na koniec reguły (mixed-decls).
2. Zmigrować użycia na `--on-danger`/`--on-success` lub usunąć martwe tokeny; dopisać `on-*` do YAML w DESIGN.md/design.json.
3. Zakres `--border-control` realnie ogranicza się do niestylowanych inputów — udokumentować w notatce / FE-116.
4. Rozszerzyć test landmarków o pulpit agenta i test `hideBreadcrumbs` dla trasy zagnieżdżonej.
5. Przegląd w przeglądarce (agent/supervisor/admin, oba motywy) — AC Lamp Rule i fokus — brak śladu w diffie.

### Opcjonalne
1. Dynamiczna etykieta `section` (kalendarz vs kontakty).
2. Usunąć zbędne `border-radius: 0` w fokusach edytorów; `accent-fg` bez zmiany `#fff` → `oklch(100% 0 0)`.
3. Komentarz przy `hideBreadcrumbs` o dziedziczeniu `data`.

### Summary
**Ocena: 4/5** — poprawne, zweryfikowane skryptem wartości tokenów i minimalne, trafne zmiany landmarków/fokusu, ale zostaje pusty pasek breadcrumbów, skip-link z białym tekstem na jasnym akcencie w ciemnym motywie i brak i18n skip-linku, a testy nie pokrywają pulpitu agenta.

---

## Review: FE-116 (palety → tokeny, Lamp Rule, `--border-control`, `--on-*`) + FE-118 (skala typografii) — niezacommitowane zmiany — 2026-10-23

Zakres: `git diff` (79 plików frontend + DESIGN.md + design.json) + nowy `agent-status.model.spec.ts`. Kontrast liczony `c.py` + własnym skryptem (`r1.py`) na wartościach ze `styles.scss`.

### 🐛 Bugs / Critical Issues
- **Hover ramki kontrolki jest JAŚNIEJSZY niż stan spoczynku (regresja kontrastu/migotanie).** Po migracji baza to `--border-control` (3,3–3,9:1), a hover nadal `--border-strong` (1,8–2,0:1) lub `--border-2` (1,4–1,5:1). Skrypt zamienił tylko część hoverów (na `--text-muted`), reszta została: `add-break-modal.component.scss:268,299,329,480,631`, `agent-calendar.component.scss:67`, `social-contact.component.scss:149`, `disposition-panel.component.scss:175,202`, `reschedule-callback-modal.component.scss:233,264,294,459`, `schedule-inbound-callback-modal.component.scss:310,392,458`, `manual-callback-modal.component.scss:284,366,432`, `adhoc-email-modal.component.scss:303,451,507`, `softphone.component.scss:375,654`, `email-contact.component.scss:276`, `language-switcher.component.scss:28`, `contact-detail-modal.component.scss:548`, `routing-rules.component.scss:75,335`, `disposition-list-editor.component.scss:87`, `agent-customer-card.component.ts:190,294,319`. Fix: jeden token `--border-control-hover` (np. = `--text-muted`) w `styles.scss` i grep `border-color: var(--border-(2|strong)` w plikach, w których baza to `--border-control`.
- **Pominięte kontrolki (nadal `--border-1`, AC „brak border-1/2/strong na kontrolkach” niespełnione):** `top-navbar.component.scss:403` (`.theme-switcher` — przycisk), `ai-summary-panel.component.scss:38` (toggle), `adhoc-email-modal.component.scss:677` (`.btn-attach`, dashed), `twilio-config.component.scss:300` i `ai-config.component.scss:278` (`.btn-outline`), `reports-placeholder.component.scss:150`, `contacts-report.component.scss:148` (przyciski paginacji), `routing-rules.component.scss:149` (`.day-chip` — sprawdzić czy klikalny; sąsiedni `.day-btn` w `routing-rule-form` został zmigrowany), `add-break-modal.component.scss:663` (menu — kontener, OK).
- **Fałszywe trafienie skryptu:** `top-navbar.component.scss:133` `.navbar__user-chip` to `<div>` (html:214), element informacyjny, nie kontrolka — `--border-control` robi z niego „pole”. Cofnąć na `--border-1`. Drugorzędnie: `disposition-panel.component.scss:327` `.disp-card` jest klikalnym przyciskiem-opcją, więc trafienie jest poprawne (nie karta). Pozostałe ~138 trafień z listy (inputy/selecty/textarea/`.btn-*`/`.icon-btn`/`.day-btn`/checkbox/paginacja/`.attachment-chip` będący `<button>`) są uzasadnione; nie znalazłem kart, tabel ani separatorów objętych zamianą.
- **`data-retention.component.scss:492-493`:** `background: var(--danger-text); color: #fff;` — w ciemnym motywie `--danger-text` jest jasny (82% L), biały tekst ≈ 1,5:1. Skrypt (b) go ominął (wzorzec `--danger-text`). Zmienić na `--danger` + `--on-danger` oraz `0.6875rem` → `var(--fs-2xs)`.
- **(d) listboxy — poprawka martwa:** `admin-user-form/queue-form/user-form` dostały `:focus-visible` na `.skill-dropdown__item`, ale `<li role="option">` nie ma `tabindex`, a obsługa to `(mousedown)` (html:186-196) — fokus tam nigdy nie trafi, a opcje są nieosiągalne z klawiatury. AC „listboxy mają widoczny :focus-visible” spełnione tylko formalnie. Wzorzec z FE-117 (`.autocomplete-item`): `aria-activedescendant` + klasa `--active` sterowana strzałkami w `onSkillInputKeydown`, albo przynajmniej `tabindex="-1"` + Enter/Space. Decyzja: zrobić naprawdę (S) albo usunąć martwy CSS i zostawić wpis w FE-119/FE-124.

### ⚠️ Security Concerns
- _None identified._ Uwaga higieniczna: w katalogu głównym leży nieśledzony `contact_center_pre_v101-110.dump` (1,2 MB, nie ignorowany przez `.gitignore`) — dump bazy może zawierać dane; nie dodawać do commita (`git add` selektywnie), ewentualnie dopisać do `.gitignore`.

### 🏗️ Architecture / Pattern Violations
- **Lamp Rule (DESIGN.md:178) — kalendarz:** `agent-calendar.component.scss` kategorie zdarzeń: `--campaign` → `accent-soft/accent-text` (kategoria danych = dekoracja akcentem; akcent tylko akcja/aktywność/fokus/zaznaczenie). Zamienić na `neutral` albo `violet`/`warning` zgodnie z tabelą kanałów; `--callback` na `violet` koliduje z DESIGN.md:161 (violet = e-mail/„przekazany”, „kolor rozróżniający”) — rozszerzyć opis violet w DESIGN.md albo wybrać inny ton. `--selected` ring na `--accent` jest OK (zaznaczenie).
- **Lamp Rule — pozostałe zmiany zgodne:** przycisk główny „Dodaj przerwę” na `--accent` + `--on-accent` (akcja główna) — OK; FAB kalendarza na `--accent` — OK jako główna akcja strony; cień `--shadow-lg` na FAB zgodny z Flat-at-Rest (element pływa nad treścią), ale `hover → --shadow-pop` + `filter: brightness(.92)` na `--on-accent` nie ma testu kontrastu — kosmetyka. Nagłówek modala bez koloru: neutralny `--bg-subtle`; ikona w `warning-soft` jest dekoracją semantyczną (przerwa = warning) — akceptowalne, ale najbardziej zgodne z Lamp Rule byłaby ikona w `--text-2`.
- **Hover `--text-muted` jako kolor ramki** (28 miejsc): kontrast poprawny (5,7:1 vs `bg-surface` jasny, 6,2:1 ciemny), kierunek (ciemniej niż spoczynek) sensowny, ale token tekstowy użyty jako kolor krawędzi łamie semantykę The Triad/Token-Only i zrywa spójność (część plików ma `--border-strong`). Wprowadzić dedykowany token (patrz wyżej).
- **Podwójne ramki:** nie znalazłem miejsc, gdzie `border-control` dołożył ramkę obok istniejącej (zamiana tylko wartości `border`). `agent-calendar:114+150` (kontener + `border-right` między przyciskami segmentu) jest spójne.
- **Model statusów — API:** zmiana `color/bgColor → tone` nie zostawiła konsumentów: grep w całym `frontend/src` (ts/html) — jedyny konsument to `agent-desktop.component.{ts,html}`; supervisor/monitoring nie importuje `AGENT_STATUS_CONFIG`. Mapowanie tonów w `[data-status-tone]` zadziała (selektory enkapsulowane, atrybut jest na `.status-btn` i `.status-menu__item` w tym samym szablonie). `status-btn` zachowuje etykietę + ikonę (WCAG 1.4.1).
- **Tests:** `agent-status.model.spec.ts:20-22` — test „nie używa akcentu” jest tautologią (`'accent' as AgentStatusTone` jest rzutowaniem poza typ, wynik zawsze true; typ już to gwarantuje). Zastąpić testem, że zbiór tonów ⊆ {success, danger, warning, violet, neutral} albo usunąć. Brak testu spinającego `tone` z blokiem `[data-status-tone]` w SCSS (literówka w tonie = brak koloru po cichu) — opcjonalnie prosty test czytający scss lub snapshot komponentu.

### 🔧 Improvements & Suggestions
- **Kontrast nowych par (zmierzony):** `--on-warning` na `--warning`: 6,32:1 jasny / 9,61:1 ciemny (`.ws-banner`, wymóg AC ≥ 4,5 spełniony). Triady pigułek (`X-text` na `X-soft`): success 6,65/7,94; danger 6,16/6,96; warning 5,36/7,26; violet 6,81/7,77; neutral 7,72/7,81 — wszystko ≥ AA w obu motywach; `violet-text` na `bg-surface` 7,92/9,89. Toasty (`X-text` na `X-soft`): success/danger/warning/info ≥ 6,1:1 jasny, ≥ 6,9:1 ciemny; obramowanie toastu to `color-mix(… 45%, transparent)` — dekoracyjne (komunikat niesie tekst + glif), więc 3:1 nie jest wymagane. Usunięcie `border-left: 4px` z toastów — trafne (zakaz bocznego paska). Ramka `.status-btn` na `tone-text` — wysoki kontrast.
- **Badge platform `social-contact.component.ts:168-176` + `.scss:263`:** `#25d366` + biała litera ≈ 1,98:1 (WhatsApp), `#1877f2` ≈ 4,3:1, `#c13584` ≈ 5,2:1, a wymóg dla tekstu „WA/FB/IG” to 4,5:1. Rekomendacja: porzucić kolor marki jako tło tekstu — badge na `--neutral-soft` + `--neutral-text` (tekst 7,7:1), a kolor platformy jako ikona/kropka 8 px (znak marki jest nośnikiem identyfikacji, tekst WA/FB/IG nośnikiem informacji). Wersja minimalna: dla WhatsApp tekst `oklch(15% 0.02 250)` (9,9:1). Komentarz „platform brand fills come from the model” jest nieprawdziwy — kolory są w komponencie TS, nie w modelu. Dodatkowo hex w TS omija Token-Only Rule.
- **Pozostałe literały hex w powierzchni agenta, nieobjęte AC lecz „jasne plamy”:** `transfer-queue-list.component.scss:130-137` (`#fff3e0/#e65100`, `#e8f5e9/#2e7d32` — plik był dotykany, pigułki jasne w ciemnym motywie), awatary z gradientem accent→violet i `color: #fff`: `agent-desktop.component.scss:985`, `customer-panel.component.scss:139`, `softphone.component.scss:131`, `top-navbar.component.scss:155` — w ciemnym motywie akcent jest jasny, biały tekst ≈ 2,5–3:1 → `--on-accent`. `email-contact:783`, `disposition-panel:277` itp. `border-top-color: #fff` w spinnerach na przyciskach akcentowych → `--on-accent`.
- **Skrypt vs ticket:** deklarowane „156 deklaracji”, w diffie znajduję 142 linie `--border-control` (+ `social-contact` przez zmienną SCSS `$border-control`) — rozbieżność wynika zapewne z `border-color`/zmiennych; poprosić wykonawcę o listę z `scan_b.py` do notatki wykonania.
- **`--fs-xs` (12 px) wprowadzony, nieużywany** (`styles.scss:58`), a w repo jest 115 literałów `0.75rem`. Albo zmigrować, albo nie dodawać tokenu na zapas (YAGNI) — decyzja: zostawić, ale zaznaczyć w DESIGN.md że migracja to dług FE-124.
- **FE-118 — kompletność:** grep `font-size` px/rem/em w `*.scss/*.ts/*.html` — brak wartości < 11 px (0.7rem = 11,2 px i 0.71875/0.72rem OK). Pozostało 8 literałów `0.6875rem` (`admin-metrics-page:464`, `campaign-import:107`, `admin-user-list:265`, `data-retention:493`, `user-list:280`, `edit-callback-modal:241,338`, `customer-import:96`) — wartość poprawna, ale kryterium „rozmiary z tokenów” (i Type Floor Rule w DESIGN.md: „nowy kod”) → zamienić mechanicznie na `var(--fs-2xs)`.
- **top-navbar @1366 px — nie da się potwierdzić z CSS, ryzyko realne.** `.navbar__kpi-strip` ma `flex:1; min-width:0; overflow:hidden` i `display:none` ≤ 1100 px; lewa i prawa sekcja `flex-shrink:0`, user-chip do 460 px. Szacunek szerokości kafelka (ikona 24 + gap 8 + padding/ramka 22 + treść): Obsłużone ≈ 120, SLA ≈ 130, AHT ≈ 144, Czas pracy ≈ 149 → ~560 px razem z odstępami; dostępne przy 1366 px dla agenta ≈ 1366 − 32 (padding) − ~200 (lewa) − ~560 (prawa: theme + język + chip + wyloguj) − 24 (gapy) ≈ 550 px. Margines ≈ 0, a przy przepełnieniu `overflow:hidden` po cichu ucina ostatni kafelek. Wzrost etykiet 9,5→11 px i `sub` 9,5→11 px dokłada ok. 15–25 px. Wymagane: pomiar w przeglądarce (1366×768, pl) i — jeśli ciasno — ukrycie `__sub` poniżej ~1440 px lub zmniejszenie `max-width` chipa. Kryterium „de” jest dziś bezprzedmiotowe/niezweryfikowalne: etykiety KPI są zahardkodowane po polsku (`top-navbar.component.html:63,89,119,145`, FE-119), więc zdanie w DESIGN.md:206 („żeby etykiety w `de` mieściły się przy 1366 px”) jest nieudowodnione — zmienić brzmienie albo zweryfikować po FE-119. Zmiana `letter-spacing` 0.06 → 0.04 em zrekompensuje tylko ~2 px na etykietę.
- **`font-family: monospace` → Geist Mono** (ivr-editor ×3, email-templates ×1): zgodne z Real Mono Rule z DESIGN.md i bezpieczne (generyczne `monospace` w Chrome dziedziczy kwirk 13 px, więc wizualna wielkość tekstu może się lekko zmienić); formalnie poza zakresem FE-118 („nie zmieniamy rodziny fontu”) — zaznaczyć w notatce, nie blokuje.
- **Spójność DESIGN.md/design.json ↔ styles.scss (potwierdzone braki):** (1) `on-warning` nie występuje w DESIGN.md (frontmatter YAML, linia ~22-25, ani proza :158) ani w `design.json` (0 trafień) — a jest w `styles.scss:89,148`; (2) brak mapowania tonów statusów (success/danger/warning/violet/neutral → trójki, „Po kontakcie” = violet) w DESIGN.md i design.json; opis violet (DESIGN.md:161) nie zawiera „Po kontakcie”/zdarzeń kalendarza; (3) `--fs-2xs/--fs-xs` opisane w DESIGN.md, ale `design.json` ma tylko namedRule, bez wpisu w `typography`/tokenach; (4) `design.json` nie ma `on-danger/on-success/on-warning` w `colors` (znaleziono już w poprzednim review — nadal otwarte, AC5 z FE-115); (5) DESIGN.md nie opisuje `--border-control` hover ani zasady „kontrolka vs kontener” (która zamiana skryptowa się myliła).

### ✅ Positive Observations
- Czysty model statusów: jedna deklaracja trójki `--tone/--tone-soft/--tone-text` w `[data-status-tone]` i brak inline style w szablonie (`agent-desktop.component.html:48,75`) — rozwiązuje jasną pigułkę „Available” w ciemnym motywie i „Po kontakcie” bez akcentu (violet) bez migotania.
- `--on-warning` dobrany z zapasem (6,3 / 9,6:1), `incoming-call-banner` `fill="currentColor"` + `--on-danger` na CTA/Odrzuć (symetryczna para 5,3 / 6,3:1).
- Toast: usunięty boczny pasek 4 px, kolory przez triadę, `color-mix` zamiast rgba; `add-break-modal` bez gradientu i zielonego nagłówka, spinner/hover przez `--on-accent`.
- `social-contact` — cała paleta SCSS przeniesiona na zmienne mapujące na tokeny (mały diff, bez dublowania), usunięte dekoracyjne cienie (Flat-at-Rest).
- `--fs-2xs` zastosowany w ~56 miejscach bez zmiany układu poza KPI; DESIGN.md dostał regułę The Type Floor Rule i definicje tokenów.

### Wymagane (przed commitem)
1. Hovery ramek kontrolek jaśniejsze niż spoczynek: lista w sekcji Bugs (np. `add-break-modal.component.scss:268,299,329,480,631`, `social-contact.component.scss:149`, `agent-calendar.component.scss:67`) → `--border-control-hover` (lub spójnie `--text-muted`).
2. `top-navbar.component.scss:133` (`.navbar__user-chip` to div) → przywrócić `--border-1`; `top-navbar.component.scss:403` (`.theme-switcher` przycisk) → `--border-control`.
3. Pominięte kontrolki: `ai-summary-panel:38`, `adhoc-email-modal:677`, `twilio-config:300`, `ai-config:278`, `reports-placeholder:150`, `contacts-report:148` (+ ocena `routing-rules:149`).
4. `data-retention.component.scss:492` — `--danger-text` + `#fff` (≈ 1,5:1 w ciemnym) → `--danger` + `--on-danger`.
5. `social-contact.component.ts:171-176` / `.scss:263` — badge WhatsApp 1,98:1: neutralny badge + kolor marki jako kropka lub ciemny tekst (min. dla WA).
6. Pomiar top-navbar przy 1366×768 w przeglądarce (pl) + zapis w notatce; jeśli ciasno — ukryć `__sub`/zwęzić chip. Poprawić zdanie o `de` w DESIGN.md:206 albo oznaczyć jako do weryfikacji po FE-119.
7. (d) `.skill-dropdown__item`: dodać faktyczną obsługę klawiatury albo usunąć martwy `:focus-visible` i nie odhaczać AC.
8. Agent-calendar `--campaign` na `accent-soft` (`agent-calendar.component.scss` ~l.369) — zakaz akcentu dla kategorii; zmienić na ton neutralny/rozróżniający.
9. Uzupełnić DESIGN.md + design.json: `on-warning`, `on-danger/on-success`, mapowanie tonów statusów, `violet` (Po kontakcie, kalendarz).

### Zalecane
1. `agent-calendar.component.scss:362` callback = violet koliduje z opisem violet (DESIGN.md:161) — zaktualizować opis lub zmienić ton.
2. `agent-status.model.spec.ts:20-22` — usunąć tautologiczny test; dodać test spinający tony z SCSS.
3. Pozostałe `0.6875rem` → `var(--fs-2xs)` (8 miejsc, lista wyżej).
4. `transfer-queue-list.component.scss:130-137` hex pastele + awatary `#fff` (accent→violet gradient) → tokeny / `--on-accent`; spinnery `border-top-color: #fff` → `--on-accent`.
5. Notatka wykonania: lista z `scan_b.py` (142 vs 156), screenshoty jasny/ciemny statusów i toastów (AC FE-116), strony supervisor/admin w przeglądarce.
6. Nie commitować `contact_center_pre_v101-110.dump` (rozważyć `.gitignore`).

### Opcjonalne
1. `--fs-xs` nieużywany — migrować 0.75rem w następnym tickecie lub usunąć.
2. Ikona nagłówka add-break w `--text-2` zamiast `warning-text` (czystszy Lamp Rule).
3. Mieszanie `0.7rem`/`0.72rem`/`0.71875rem` (11,2–11,5 px) — sprowadzić do tokenu przy okazji FE-124.

### WP-7 (impeccable)
Z diffu nie wynika użycie skilla `impeccable`: brak artefaktów polish/typeset poza edycją `DESIGN.md` i `.impeccable/design.json` (w formacie impeccable, ale to może być ręczna edycja). Deklaracji agentów nie da się potwierdzić — wymagać wpisu w notatce wykonania (komendy `polish`/`typeset`, wynik, ewentualny `critique`). Konkretne rozbieżności, które `polish` powinien był wyłapać: hover lżejszy niż spoczynek i zła ramka `.navbar__user-chip`.

### Summary
**Ocena: 3,5/5** — kierunek i model tonów statusów są trafne, nowe pary kontrastu przechodzą AA, ale zamiana skryptowa zostawiła jaśniejsze hovery ramek, kilka pominiętych/pomylonych elementów, martwy fokus listboxów, badge WhatsApp ≈ 2:1, akcent na kategoriach kalendarza oraz niezweryfikowany układ KPI przy 1366 px; po punktach Wymaganych 1-9 — do zatwierdzenia.

---

## Review: EPIC-32 faza 2 — FE-119, FE-120, FE-126 (niezacommitowane zmiany frontendu) — 2026-10-23

Zakres: `git diff HEAD -- frontend/` (54 pliki) + nieśledzone: `core/brand.ts`, `shared/directives/listbox-nav.directive{,.spec}.ts`, `status-elapsed/`, 9 nowych `*.spec.ts`. Pominięto (zgodnie ze zleceniem) polskie literały w `add-break-modal` i `manual-callback-modal` — rozpisywane równolegle do i18n. Uruchomiono `npm test` (ng test/Vitest): **44 pliki, 472 testy zielone**. Parytet i18n: pl/en/de/uk po 2724 klucze, 0 brakujących/nadmiarowych; wszystkie 49 nowych kluczy ma użycie w kodzie; usunięte `agent.calendar.addBreakLabel`, `agent.disposition.statusChanged`, `agent.manualCampaign.recordsLabel` nie mają referencji; martwe `agent.incomingCall.dismiss/goToDesktop` już nie istnieją w plikach i18n.

### 🐛 Bugs / Critical Issues
- **`add-break-modal.component.ts:~255` (`applyDefaults`) + `canSubmit`/`submit()` (~l.320)** — nieaktualna walidacja „start w przyszłości”. Domyślny start to najbliższy slot 5 min od teraz+2 min, a `startInFutureValidator` odpala się tylko przy zmianie wartości. `canSubmit` czyta `_formStatus` (sygnał z `statusChanges`), więc po 3-7 min bezczynności na nietkniętym formularzu status nadal jest `VALID`, `submit()` przechodzi i wysyła `startTime` z przeszłości (w najlepszym razie 4xx z backendu i ogólny komunikat). Przed wprowadzeniem domyślnych wartości agent zawsze wpisywał czas, więc walidator się odświeżał — to regresja wprowadzona przez FE-120. Poprawka: na początku `submit()` wywołać `this.form.updateValueAndValidity()` (statusChanges emituje synchronicznie, `toSignal` się zaktualizuje), a gdy formularz jest nietknięty (`pristine`) i start minął — przeliczyć domyślny slot zamiast pokazywać błąd.
- Poza tym brak błędów blokujących w logice dat: liczenie przez północ jest poprawne (końcówka liczona z `Date` w czasie lokalnym i formatowana polami lokalnymi, `dateOf(end)` przechodzi na następny dzień), DST bezpieczne (arytmetyka na ms, pola lokalne dopiero z `Date`; ewentualna dwuznaczność tylko w godzinie cofnięcia zegara), strefy z offsetem :30/:45 poprawne, bo offset jest wielokrotnością 5 min.

### ⚠️ Security Concerns
- _None identified._ (Uwaga higieniczna bez zmian: `.env.local-demo` jest w diffie jako zmodyfikowany — nie commitować go z realnymi sekretami; plik wykluczony z tego przeglądu.)

### 🏗️ Architecture / Pattern Violations
- **`shared/components/confirm-dialog/confirm-dialog.component.ts:56-60` — zmiana wspólnego komponentu bez własnego testu.** `(cancel)` → `onNativeCancel` → `onCancel()` jest poprawne i bezpieczne dla wszystkich 9 użytkowników (`disposition-list-editor` ×2, `campaign-list`, `plugins-page` ×2, `disposition-sets-page` ×2, `twilio-config`, `ai-config`, `manual-callback-modal`): sprawdziłem każdy handler `(cancelled)` — wszystkie tylko zerują sygnał (`set(null)`/`set(false)`), więc Esc teraz poprawnie sprząta stan, a wcześniej zostawiał rodzica z `showConfirmDialog=true` i zamkniętym natywnym `<dialog>` (nie dało się otworzyć ponownie). To realna poprawa, nie regresja. Domyślny fokus lądował na „Anuluj/Wróć” (pierwszy przycisk), więc nawet dla `danger` bezpieczny. Brakuje jednak `confirm-dialog.component.spec.ts` (katalog bez specu) — patrz Wymagane #2.
- **`listbox-nav.directive.ts:optionId`** — prefiks id nadaje wywołujący (`skill`, `adm-skill`, `break-type`); `queue-form` i `user-form` używają tego samego `skill`. Dziś to osobne strony, ale dwie instancje na jednej stronie dałyby zduplikowane `id` i błędne `aria-activedescendant`. Generować unikalny sufiks w dyrektywie (licznik statyczny) albo zrobić `appListboxNav` opcjonalnym.
- **Zgodność z Lamp Rule / Token-Only (DESIGN.md):** wyróżnienie aktywnej opcji = `--bg-subtle` + `outline: 2px solid var(--accent)` — akcent jako zaznaczenie/fokus, zgodnie z regułą; wyłącznie tokeny. Kontrast policzony (OKLCH→sRGB, WCAG): obrys `--accent` vs `--bg-subtle` **4,15:1 (jasny) / 6,47:1 (ciemny)**, vs `--bg-surface` 4,87 / 6,62 — kryterium ≥3:1 spełnione w obu motywach. Samo wypełnienie `--bg-subtle` ma 1,17:1 / 1,02:1 względem tła, więc cały ciężar niesie obrys — i słusznie (nie opiera się na samym kolorze). Uwaga: ten sam `--bg-subtle` ma `:hover`, więc hover i aktywna opcja różnią się tylko obrysem — OK.
- **Niespójna nazwa modyfikatora:** `skill-dropdown__item--active` (aktywna = podświetlona) vs `custom-select__option--active` (= wybrana) i `--focused` (= podświetlona) w `add-break-modal`. Ten sam stan semantyczny ma różne nazwy; przy trzech kopiach SCSS (admin-user-form/queue-form/user-form) rozważyć wspólny mixin/utility.

### 🔧 Improvements & Suggestions
- **`add-break-modal.component.html` — poprawność wzorca:** `role="combobox"` na `<button>` z `aria-activedescendant` (z dyrektywy), `aria-controls`, `aria-expanded`, opcje z `[id]`/`aria-selected` = faktycznie wybrana, `(mousedown)="$event.preventDefault()"` na `<ul>` zapobiega blurowi przed `mousedown` opcji — wzorzec „mousedown + blur” jest tu spójny. `(blur)` zamyka listę (Tab nie zostawia otwartej). Esc: `keydown` z `preventDefault`+`stopPropagation` zatrzymuje natywne `cancel` dialogu, a `onNativeCancel` dodatkowo łapie ścieżkę natywną — oba testy to potwierdzają. Brak uwag.
- **Formularze skills (`admin-user-form`, `queue-form`, `user-form`) — `(mousedown)="addSkill(skill)"` bez `preventDefault` na `<ul>`:** mysz odbiera input fokus (blur → po 150 ms zamknięcie), więc po wyborze myszą agent/admin traci fokus z pola, w przeciwieństwie do `add-break-modal`, gdzie dodano `preventDefault`+`focus()`. To zachowanie sprzed zmiany (AC „brak regresji myszy” spełnione), ale niespójne z nową dyrektywą; dodać `(mousedown)="$event.preventDefault()"` na `<ul class="skill-dropdown">`. Dzięki temu można też usunąć `setTimeout(…,150)` w `onSkillInputBlur` (wyścig przy wolnym CD).
- **Esc w formularzach skills:** `host: '(document:keydown.escape)'` nie odpali się po Esc w polu z otwartą listą, bo dyrektywa robi `stopPropagation` na elemencie (zweryfikowane w kodzie; test `queue-form` „Esc closes only the list; the form is not cancelled” to pokrywa). Zauważyć: lista otwiera się na `focus`, więc pierwszy Esc po wejściu Tabem w pole tylko ją zamyka — oczekiwane (APG).
- **`listbox-nav.directive.ts:onKeydown`** — (a) `Shift`+strzałki w polu edytowalnym też przesuwają opcję i blokują zaznaczanie tekstu — wykluczyć `event.shiftKey` dla `listboxEditable`; (b) pole `pending` może zostać „zawieszone”, jeśli rodzic zignoruje `listboxOpenRequest` (efekt się nie uruchomi) i następne otwarcie myszą aktywuje pierwszą opcję zamiast żadnej — wyczyścić `pending` w `queueMicrotask` po emisji; (c) `scrollIntoView` przez `document.getElementById` w `queueMicrotask` — działa, ale kruche przy ShadowDOM; bezpieczniej przez `ElementRef` listy.
- **`status-elapsed.component.ts`** — (a) czas liczony po stronie klienta od momentu zastosowania statusu: **po przeładowaniu strony w trakcie przerwy licznik startuje od 0:00**, a etykieta „Czas trwania przerwy” sugeruje prawdę. Adnotacja jest tylko w komentarzu kodu; ticket (R2) wymaga adnotacji — dopisać ją w notatce wykonania i rozważyć ticket BE (`statusSince` w `GET /users/me`) albo ukrycie licznika, gdy źródłem jest odczyt po starcie (np. `statusSinceKnown`); (b) `<time>` bez atrybutu `datetime` z treścią „1:05” nie jest poprawnym `<time>` — użyć `datetime="PT1M5S"` lub zwykłego `<span>`; (c) `font-size: 0.8125rem` literałem zamiast tokenu `--fs-*`; (d) interwał: `toSignal(interval(1000))` sprząta się z `DestroyRef`, komponent żyje tylko przy `BREAK` (`@if` w `agent-desktop`), brak wycieku — OK; `applyStatus` poprawnie nie resetuje zegara przy reconnect WS (`fetchAndSyncStatus`) ani przy powtórzonym `BREAK`.
- **`disposition-panel.component.ts:~218`** — jeden toast zostawiony to `successSave` („Dyspozycja zapisana. Zmieniam status na Dostępny...”), wyświetlany przed wynikiem `changeStatus`. Gdy zmiana statusu się nie powiedzie (`error:` zamyka kartę po cichu), agent dostał komunikat o czymś, co się nie stało. Wystarczy komunikat sukcesu zapisu bez obietnicy zmiany statusu, a w `error:` osobny toast ostrzegawczy (to jest odrębny, rzadki przypadek — dopuszczalny drugi toast).
- **`customer-panel.component.ts:load/retry`** — bez wyścigów: poprzednia subskrypcja jest `unsubscribe()` przed nową, `takeUntilDestroyed` obecne. Zalecane: po kliknięciu „Spróbuj ponownie” przycisk znika (stan `loading`) i fokus ucieka na `<body>` — przenieść fokus na kontener panelu (`tabindex="-1"`) po zmianie stanu; `role="alert"` jest na wewnętrznym `div` (słusznie, przycisk poza regionem), ale po nieudanym retry alert jest odtwarzany od nowa — OK, ogłosi się ponownie.
- **Pozostałe literały PL poza dwoma wyłączonymi plikami (kryterium AC „grep = 0”):** `login.component.html:59` `aria-label="Logujesz się jako …"` i `:30` `placeholder="przyklad@firma.pl"`; `customer-panel.component.ts:122-133` `getChannelLabel` (`'Telefon'`/`'Email'`/`'Chat'`/`'Social'`) używane w `aria-label` (html:164); `admin-user-form.component.html:~178,~195` `placeholder="Dodaj skill..."`, `aria-label="Dostepne skills"` (bez diakrytyków; plik był modyfikowany w tym ticketcie); `agent-desktop.component.ts:282` `console.warn` (log, do pominięcia). Komunikaty błędów zapisywane w sygnałach jako już przetłumaczony tekst (`email-contact`, `social-contact`: `this.error.set(translate(...))`) nie zmienią języka po przełączeniu — wzorzec z `ai-summary-panel` (przechowuje klucz i tłumaczy w szablonie) jest lepszy.
- **`top-navbar.component.scss` — pas KPI:** `justify-content: safe center` + `min-width:0` + `flex: 0 1 auto` + elipsa na `__label`/`__sub` — kierunek słuszny. Ryzyko: `__value` ma `white-space:nowrap` bez elipsy, a `__body{overflow:hidden}` — przy bardzo wąskiej kafelce liczba (np. `1:12:05`) zostanie ucięta bez znaku wielokropka, czyli pokaże błędną wartość. `__sub` ma `min-width:0`, więc kurczy się pierwszy (dobrze), ale dodać `flex-shrink:0` na `__value`, żeby wartość nigdy nie była skracana. Usunięcie „cel 90%” i „z 8:00” + paska czasu pracy (`Math.min(.../28800)`) i pola `Math` — zgodne z wariantem (A) decyzji D1; pasek SLA zostaje (liczony z rzeczywistego `slaPercent`).
- **`core/brand.ts`** — dobre, jedno źródło; uwaga: ticket każe „zachować `ContactCenterFrontend` jako roboczą”, a `BRAND.name = 'Contact Center'` (to wartość sprzed zmiany z szablonów) i `index.html` ma `<title>ContactCenterFrontend</title>` — dwie różne nazwy robocze. Udokumentować wybór w notatce lub ujednolicić tytuł karty z `BRAND` (np. `Title` service). `vendor: 'KMN Software'` — nowa stała w kodzie, upewnić się, że to nie jest „wymyślanie” nazwy poza PRODUCT.md.
- **Testy — jakość:** dobre testy zachowania na prawdziwych szablonach (`agent-desktop.copy.spec`, `add-break-modal.spec`, `queue-form.spec`, `top-navbar.spec`, `status-elapsed.spec` z fałszywymi timerami). Luki: (1) listbox przetestowany tylko w `queue-form`, nie w `admin-user-form` i `user-form` (AC FE-126 wymienia trzy formularze; kopie szablonów mogą się rozjechać); (2) brak testu Esc **wewnątrz** `app-confirm-dialog` w `manual-callback-modal.spec` (jest tylko `keep.click()`), czyli właśnie nowa ścieżka `cancel → onCancel → keepEditing` jest niepokryta; (3) brak testu dat domyślnych przez północ (np. `vi.setSystemTime('…T23:58')`) i testu bezczynności (>2 min) z W1; (4) `disposition-panel.spec` nie sprawdza gałęzi `error:` zmiany statusu; (5) `top-navbar.spec` nie dowodzi braku ucięcia w `de`.

### ✅ Positive Observations
- `ListboxNavDirective` jest zwięzła i dobrze przemyślana: DOM focus nigdy nie opuszcza hosta (brak roving tabindex), tryb `editable` zachowuje Space/Home/End dla pola tekstowego do czasu aktywacji opcji, `swallowSpaceKeyup` zapobiega podwójnemu „klikowi” na `<button>`, Esc zatrzymuje domyślną akcję i propagację (zgodnie z FE-114/117), jeden mechanizm dla 4 użytkowników zamiast 4 kopii — kryterium FE-126 „wspólna dyrektywa” spełnione, a `add-break-modal` korzysta z tej samej (R2 z FE-126/120 zaadresowane).
- `add-break-modal`: zmiany fokusu (pierwsze pole zamiast przycisku zamknięcia), `markAsDirty` po wyborze, `blur` zamyka listę, domyślne wartości z istniejącego kontraktu (`SHORT_BREAK`, bez wymyślania nowych typów).
- `manual-callback-modal`: `requestClose` blokuje zamknięcie w trakcie zapisu, pristine zamyka od razu, dirty pyta przez wspólny `app-confirm-dialog` z bezpiecznym domyślnym fokusem.
- `agent-status.service.applyStatus`: zegar resetuje się tylko przy realnej zmianie statusu, nie przy reconnect WS.
- Usunięcie martwego `agent-dashboard.component.ts` i FAB-a „Dodaj przerwę” bez śladów (`addBreakMode`, `openAddBreak`, scss) — `agent-desktop` zachował jeden przycisk i odświeża kalendarz przez `onBreakSavedFromHeader()`.
- i18n: pełny parytet 4 języków, spójne parametry interpolacji (`{{ date }}`, `{{ count }}`), formy neutralne płciowo w nowych tekstach; `ai-summary-panel` trzyma klucz błędu w sygnale.

### Wymagane (przed commitem)
1. `add-break-modal.component.ts` (`submit`, ~l.320) — odświeżyć walidację (`form.updateValueAndValidity()`) przed `canSubmit()`; przy nietkniętym formularzu przeliczyć domyślny slot, żeby domyślny start nie trafiał w przeszłość (patrz Bugs) + test z `vi.setSystemTime` i upływem >2 min.
2. Dodać `confirm-dialog.component.spec.ts` (Esc → `cancelled` emitowane raz i dialog zamknięty; klik „Anuluj”/backdrop bez regresji; `confirmed`) i test Esc w potwierdzeniu `manual-callback-modal` — zmiana wspólnego komponentu dotyka 9 ekranów.
3. AC FE-119 „pas KPI w `de` przy 1366 px bez ucięcia” — wymaga pomiaru w przeglądarce (1366×768, pl i de); w notatce wykonania brak dowodu. Kod ma elipsy, ale etykiety „ARBEITSZ…” byłyby widoczną regresją; dodatkowo `flex-shrink:0` na `.kpi-tile__value`, by liczba nigdy nie była ucinana.

### Zalecane
1. Testy listboxa dla `admin-user-form` i `user-form` (jak w `queue-form.spec`), test `Shift`+strzałki jeśli zostanie obsłużone.
2. `(mousedown)="$event.preventDefault()"` na `<ul class="skill-dropdown">` w trzech formularzach + usunięcie `setTimeout(150)` w `onSkillInputBlur`.
3. Unikalne id opcji w dyrektywie (statyczny licznik) zamiast prefiksu od wywołującego.
4. Licznik przerwy: adnotacja „od załadowania” / ukrycie po reloadzie albo ticket BE na `statusSince`; `<time datetime>`; token zamiast `0.8125rem`.
5. Pozostałe literały PL: `login.component.html:30,59`, `customer-panel.component.ts:122-133`, `admin-user-form.component.html` (placeholder, `aria-label`).
6. `disposition-panel`: nie obiecywać zmiany statusu w toaście sukcesu; osobny toast przy błędzie `changeStatus`.
7. `customer-panel`: przywrócić fokus po retry; `BRAND.name` vs `<title>ContactCenterFrontend` — ujednolicić albo opisać decyzję.
8. Ujednolicić nazwę modyfikatora aktywnej opcji (`--active` vs `--focused`) i wydzielić wspólny styl wyróżnienia.

### Drobne
- `listbox-nav.directive.ts` — czyszczenie `pending` po niewykonanym otwarciu; `scrollIntoView` przez `ElementRef`.
- `agent-desktop.component.ts:282` `console.warn` po polsku (log, nie UI).
- Komunikaty błędów w `email-contact`/`social-contact` jako przetłumaczony string w sygnale (nie reaguje na zmianę języka).
- `aria-selected` na opcjach list skills oznacza „aktywna” (nie „wybrana”) — dopuszczalne wg APG dla autocomplete, ale warto opisać w komentarzu dyrektywy.

### Summary
**Ocena: 4/5** — solidna, dobrze przetestowana implementacja (wspólna dyrektywa listbox z zachowanym Esc/fokusem, kontrast aktywnej opcji 4,15:1/6,47:1, pełny parytet i18n, brak wycieku interwałów, bezpieczna zmiana `confirm-dialog`), ale domyślny start przerwy przechodzi nieaktualną walidację, wspólny `confirm-dialog` nie ma własnego testu, a AC o pasie KPI w `de` przy 1366 px nie ma dowodu pomiaru.


---

## Review: FE-131 (magazyn szkiców `ContactDraftStore`) + FE-134 (przełącznik statusu w górnym pasku) — niezacommitowane zmiany frontendu — 2026-10-28

Zakres: `contact-draft.store.ts` (+spec), `contact-tab.store.ts`, `contact-tab.model.ts`, `softphone.component.ts`, `email-contact.component.{ts,html}` (+`drafts.spec`), `social-contact.component.ts` (+spec), `agent-desktop.component.{ts,html}`, `agent-shell.component.ts`, `agent-status-switcher/*`, `top-navbar.component.{ts,html}` (+`agent-status.spec`), `customer-panel.*`, i18n pl/en/de/uk. Backend pominięty.

### 🐛 Bugs / Critical Issues
- **`email-contact.component.ts:sendReply` (~l.412–426, `takeUntilDestroyed`) + `agent-desktop.component.html` (`@for … track current.id`)** — wysyłka odpowiedzi jest anulowana przy zniszczeniu komponentu. Po FE-131 przełączenie karty w trakcie `sending` jest realnym scenariuszem: żądanie HTTP zostaje przerwane po stronie klienta (serwer mógł je już przetworzyć), `replySent` nie jest emitowane, karta zostaje, a szkic jest nadal w magazynie. Po powrocie agent widzi aktywny przycisk „Wyślij” (`sending` jest lokalnym sygnałem, po remoncie `false`) i wysyła drugi raz = zdublowany e-mail do klienta. Wcześniej problem istniał, ale szkic znikał razem z komponentem; teraz jest go „przywracany” zachętą do ponownego wysłania. Poprawka: stan `sending` w magazynie (np. `markSending(contactId)`), blokada przycisku po remoncie, a wysyłkę zdjąć z `takeUntilDestroyed` (jak `acceptContact`) i po sukcesie czyścić magazyn przez `drafts.clear` niezależnie od żywotności komponentu (+ `closeTab` przez serwis, nie przez `output`, bo po zniszczeniu `replySent` nie dotrze).
- **`softphone.component.ts:~209` `currentNote = computed(() => this.drafts.phoneNote(this.tab.contactId))`** — `tab` jest zwykłym `@Input` (nie sygnałem), więc `computed` zależy tylko od sygnału magazynu. Wcześniej czytał `tabStore.tabs()` i reagował na zmianę karty. Po `updateTabContactId` (attended transfer) `rekey` działa PRZED `tabs.update`, a poprawność wyświetlanej notatki zależy od kolejności: sygnał `phoneNotes` zmienia się, `computed` przelicza się dopiero w CD, kiedy `@Input` jest już nowy — działa przypadkiem; gdy notatka nie istniała lub zmieni się wyłącznie input, wartość jest nieodświeżona. Zamienić na `input.required<ContactTab>()` albo czytać notatkę metodą w szablonie (`drafts.phoneNote(tab.contactId)`), bez `computed` na polu niereaktywnym.
- **`contact-draft.store.ts:rekey` (l.~125)** — `setPhoneNote(newContactId, note)` nadpisuje istniejącą notatkę pod `newContactId` (np. notatkę oryginalnej karty), jeśli karta konsultacji (klucz = SID) ma własną. Utrata notatki bez ostrzeżenia. Scalić (`existing + '\n' + note`) lub nie nadpisywać niepustej. Test `updateTabContactId carries the PHONE note over` nie obejmuje kolizji.
- **`contact-tab.store.ts:closeTab` (l.~97–101) + `agent-desktop.component.ts:339-344` (× na karcie)** — kliknięcie × na karcie e-mail/social z niewysłanym szkicem kasuje szkic i flagę `accepted` BEZ potwierdzenia, a kontakt zostaje `ASSIGNED/ACTIVE` w bazie (znane z FE-132). Do czasu FE-132 FE-131 wprowadza nową, cichą utratę pracy agenta (wcześniej też ginęła, ale ticket obiecuje „nie gubić szkiców”). Minimum: potwierdzenie przy `hasUnsentDraft` już w FE-131 albo jawna adnotacja o zależności FE-131→FE-132 w ticketach (merge razem).

### ⚠️ Security Concerns
- **`email-contact.component.ts:editorElement` setter (l.~55–60)** — przywracanie szkicu przez `nativeElement.innerHTML = this.replyHtml()` omija sanitizer Angulara; treść pochodzi z `innerHTML` pola `contenteditable` (wklejone HTML od klienta/z innej aplikacji) i z `preview.bodyHtml` szablonu. To ten sam wzorzec co istniejący `setEditorHtml`, ale magazyn dokłada nowy punkt wejścia i trwa dłużej. Self-XSS/stored w sesji agenta (np. wklejone `<img onerror>`): przefiltrować przez `DomSanitizer.sanitize(SecurityContext.HTML, …)` przy zapisie do magazynu lub przy odtwarzaniu.
- **`agent-shell.component.ts:ngOnDestroy` → `clearAll()`** — OK pod RODO, ale `ContactTabStore.tabs` (root) przeżywa wylogowanie i nie jest czyszczony razem z szkicami. Po ponownym logowaniu w tej samej karcie przeglądarki (inny agent) karty z poprzedniej sesji (customerName, contactId) mogą być widoczne, a `acceptedIds` jest puste, więc `acceptContact` poleci ponownie. Sprawdzić, czy `AuthService.logout` czyści `ContactTabStore`; jeśli nie — dopisać `tabStore.reset()` obok `clearAll()`.

### 🏗️ Architecture / Pattern Violations
- **`agent-status.service.ts:initStatus` (wołane teraz w `AgentShellComponent.ngOnInit`)** — `ws.onConnect(...)` zwraca funkcję wyrejestrowania, która jest ignorowana, a subskrypcja `ws.events$` nie ma `takeUntil`/`DestroyRef`. Shell jest tworzony przy każdym logowaniu w tej samej karcie, więc handlery kumulują się (N× `GET /users/me` po reconnect, N× `applyStatus`). Nie jest to nowy defekt (wcześniej desktop), ale przeniesienie właściciela do shella jest właściwym momentem naprawy: `initStatus()` powinno zwracać cleanup / przyjmować `DestroyRef`, a shell go podpiąć (jak `unregisterRecovery`). Podwójnego wywołania `initStatus` z pulpitu NIE ma (grep: jedyne wywołanie w shellu) — OK.
- **`agent-desktop.component.html:~283`** — `@for (current of [activeTab()!]; track current.id)` działa (remount przy zmianie `id`, brak remontu przy zmianie statusu karty), ale: (a) wewnątrz używane jest nadal `activeTab()!` zamiast `current` — dwa źródła, `current` nieużywany (martwa zmienna, wprowadza w błąd); (b) tablica `[activeTab()!]` alokowana przy każdym CD; (c) `!` przy `null` rzuciłoby w `track`, gdyby zewnętrzny `@if` kiedyś zniknął. Czytelniej: `@if (activeTab(); as current)` nie remontuje, więc zostawić `@for`, ale użyć `current.type/contactId/status` i skomentować, dlaczego `@for`. Dodatkowo brak testu tego mechanizmu (patrz Testy).
- **`contact-draft.store.ts`** — jedno źródło prawdy zrealizowane poprawnie, ale magazyn miesza trzy odpowiedzialności (szkice, flaga `accepted`, cykl życia). Flaga „zaakceptowano” nie jest szkicem; nazwa `ContactDraftStore.markAccepted` utrudni odszukanie (FE-136 będzie jej potrzebował do odtwarzania). Rozważyć osobny `ContactAcceptanceTracker` albo przynajmniej nazwę metody/sekcji w README komentarza. Nie blokuje.
- **`contact-tab.store.ts` ↔ `contact-draft.store.ts`** — `ContactTabStore` zależy od magazynu szkiców (efekt uboczny w `closeTab`); akceptowalne jako „jedyny punkt wyjścia”, ale `closeTab` czyści po `closing.contactId`, a nie po `originalContactId`: dla karty konsultacji szkice kluczowane oryginalnym contactId (np. e-mail/social nie, ale notatka rozmowy po bridge) nie zostaną usunięte. Sprawdzić, czy notatka nigdy nie jest zapisywana pod `originalContactId`.
- **Klasa `.navbar__status` w `top-navbar.component.html:44`** nie ma żadnej reguły w `top-navbar.component.scss` (plik niezmieniony); układ przełącznika względem pasa KPI (`justify-content: safe center`, wąskie ekrany, `de`) nie został zweryfikowany — to samo ryzyko co FE-119 (AC „bez ucięcia przy 1366 px”). Dodać regułę (`flex: 0 0 auto`, `min-width: 0` na pasie KPI) i zmierzyć w przeglądarce.

### 🔧 Improvements & Suggestions
- **`agent-status-switcher.component.ts:onMenuKeydown` `case 'Tab'` (l.~148)** — `close()` bez zwrotu fokusu usuwa fokusowany element z DOM przed domyślną akcją Tab; część przeglądarek zgubi punkt startowy nawigacji i fokus ląduje na `<body>` (WCAG 2.4.3). Bezpieczniej: przy Tab/Shift+Tab ustawić fokus na wyzwalaczu BEZ `preventDefault` (kolejny Tab przejdzie dalej od wyzwalacza), a menu zamknąć dopiero po tym.
- **`agent-status-switcher.component.ts:onMenuKeydown` `Escape`** — `stopPropagation()` jest zbędne i potencjalnie szkodliwe: `agent-hotkeys.service.ts:74` i tak respektuje `event.defaultPrevented`, a zatrzymanie propagacji zablokuje każdy inny nasłuch na dokumencie (np. przyszłe globalne Esc, `dialog-escape`). Wystarczy `preventDefault()` (zgodne z komentarzem w hotkeys). Brakuje też obsługi Esc na samym wyzwalaczu, gdy menu jest otwarte, a fokus wrócił na wyzwalacz (np. po kliknięciu myszą w wyzwalacz przy otwartym menu fokus zostaje na wyzwalaczu — `toggle()` zamyka, ale Esc nic nie robi).
- **`host: '(document:click)'`** — handler odpala się dla KAŻDEGO kliknięcia w aplikacji przez cały czas życia paska (także przy zamkniętym menu) i oznacza komponent OnPush jako brudny. Rejestrować nasłuch tylko gdy menu jest otwarte (`effect` + `renderer.listen`, sprzątanie przy zamknięciu) albo użyć `focusout` z `relatedTarget` (zamyka też po Tab myszą/dotykiem i zdarzeniach bez kliknięcia).
- **`agent-status-switcher.component.html` (menu)** — `role="menu"` ma `aria-label`, ale element nie jest `aria-controls`/`aria-owns` powiązany z wyzwalaczem (`aria-haspopup="menu"` + `aria-expanded` wystarczają dla APG, więc nie błąd). Warto dodać `id` menu i `aria-controls` na wyzwalaczu. `aria-busy` na przycisku podczas `isChanging` nie jest ogłaszane; wynik zmiany ogłasza toast (OK), ale po usunięciu etykiety `changingStatus` (`aria-label` spinnera) brak jakiegokolwiek komunikatu „zmieniam…” dla czytnika — spinner jest `aria-hidden`. Dopuszczalne, jeśli toast sukcesu/błędu jest zawsze emitowany.
- **`agent-status-switcher.component.ts:open()`** — `afterNextRender` rejestrowany przy każdym otwarciu, a `pendingFocus` jako pole współdzielone; podwójne szybkie wywołanie rejestruje dwa callbacki (drugi widzi `null` — bezpieczne, ale zbędne). Prostsze: `effect` na `menuOpen` + `items()`.
- **`top-navbar.component.ts:url`** — `toSignal(NavigationEnd)` z `initialValue: router.url` jest poprawne, ale topbar jest tworzony PO pierwszym `NavigationEnd` — `router.url` w momencie aktywacji bywa już zaktualizowany (OK przy domyślnym `urlUpdateStrategy`). Regex `/^\/agent(\/|\?|#|$)/` jest zbędnie złożony; i tak komponent jest renderowany tylko w powłokach — rozważyć dane trasy (`data: { showAgentStatus: true }`) zamiast dopasowania URL w komponencie współdzielonym.
- **`email-contact.component.ts:effect` (l.~112–123)** — efekt czyta `contactId()` i buduje obiekt nawet gdy `draftClosed || !restored`; kolejność sprawdzeń na początku oszczędziłaby alokacji, ale ważniejsze: `setEmailDraft` przy każdym naciśnięciu klawisza tworzy nowy obiekt rekordu (`{...cur}`) i przelicza `draftContactIds` (O(n)) — przy debouncingu 150–300 ms byłoby czyściej. `subjectEdited` jest zwykłym polem, nie sygnałem: efekt nie śledzi jego zmiany, tylko szczęśliwie `replySubject.set` występuje zaraz po (kolejność w `onSubjectInput`/`applyTemplate`). Zamienić na sygnał dla spójności.
- **`email-contact.component.ts:setEmailDraft`** — szkic z wybranym szablonem, ale bez treści i bez edytowanego tematu jest kasowany (`!meaningful && !subjectEdited`): wybór szablonu przepada po przełączeniu karty. Rzadkie, ale sprzeczne z AC „szablon przeżywa”.
- **Remont na każdą zmianę karty** — e-mail ładuje wiadomość, wątek i szablony (3 żądania), social wiadomości + subskrypcję WS przy każdym powrocie; przy częstym przełączaniu to zbędny ruch i miganie spinnera (scroll wątku też ginie). Do FE-133/135 rozważyć cache wątku w magazynie lub `@defer`-free „keep-alive” (ukryte karty przez `[hidden]`), ale to decyzja produktowa (limit 4 karty).
- **`customer-panel.component.ts:canManageProfiles`** — poprawnie `computed` z `currentRole()`; CTA ukryte dla AGENT. Stary warunek w drugim miejscu (SUPERVISOR||ADMIN) zastąpiony tym samym predykatem — dobrze. SUPER_ADMIN (rola występująca w specu navbara) nie jest uwzględniony: ma dostęp do `/supervisor/**`? Zweryfikować i ewentualnie dodać.
- **i18n** — usunięte `agent.desktop.changeStatus` i `changingStatus` (0 użyć — brak martwych kluczy), dodane `statusTriggerLabel` w 4 językach; `statusTriggerLabel` w `pl`/`de` ma różne słowo „Zmień/ändern” OK. Nazwa dostępna wyzwalacza zawiera widoczny tekst (WCAG 2.5.3 spełnione); widoczna etykieta „Twój status” jest `aria-hidden` — dopuszczalne, bo informacja jest w `aria-label`.

### ✅ Positive Observations
- `ContactDraftStore`: niemutowalne sygnały, `omit` zwraca tę samą referencję, gdy klucza nie ma (brak zbędnych emisji), `draftContactIds` jako `computed` gotowe dla FE-132/133, normalizacja pustego edytora (`<br>`, `<div><br></div>`, `&nbsp;`) jako „brak szkicu”, brak jakiegokolwiek dostępu do web storage + test tego (`never writes to web storage`).
- `closeTab` jako jedyny punkt wyjścia czyszczenia (przycisk ×, wysłanie, dyspozycja, koniec rozmowy, zniknięcie kontaktu) — prosty i trudny do ominięcia; `rekey` przy bridge transferu pomyślany i przetestowany.
- Świadoma decyzja o `acceptContact` bez `takeUntilDestroyed` z `unmarkAccepted` w `catchError` + test „retry po błędzie” i „dwa montaże = jedno wywołanie”; setter `@ViewChild('editor')` poprawnie obsługuje edytor tworzony później w `@if` (po załadowaniu wiadomości) i ponowne utworzenie.
- Przełącznik statusu: pełny wzorzec APG menu button (Enter/Space/↑/↓/Home/End/Esc z powrotem fokusu), `menuitemradio` + `aria-checked`, pominięcie żądania przy wyborze bieżącego statusu, `cc-status-elapsed` przeniesiony razem z pigułką, widoczność ograniczona do roli AGENT na `/agent/*` i test nawigacji w obie strony oraz „nigdy dla SUPERVISOR/ADMIN/SUPER_ADMIN”.
- `initStatus` w shellu (jedno miejsce) zamiast pulpitu; brak podwójnego wywołania; `AgentShell.clearAll()` na wylogowanie zgodne z RODO.

### Testy — jakość i luki
- Brak testu kluczowej decyzji `@for … track current.id`: żaden spec nie sprawdza, że dwie karty tego samego typu (np. 2× EMAIL) remontują komponent przy zmianie aktywnej karty (stary błąd: stan poprzedniego kontaktu w nowym). `agent-desktop.landmarks.spec` tego nie dotyka.
- Brak testu `AgentShellComponent.ngOnDestroy → clearAll` i `initStatus` wywołanego dokładnie raz (AC „bez duplikatu”).
- Brak testu wysyłki przerwanej przez przełączenie karty (patrz Bug 1) oraz testu softphone (notatka po `updateTabContactId` w komponencie, nie tylko w magazynie).
- `never writes to web storage` sprawdza tylko `Storage.prototype.setItem` i tylko magazyn; AC mówi też o IndexedDB, a komponenty (email/social) nie są objęte. Test spy powinien obejmować pełny cykl `email-contact` (wpisz → odmontuj → zamontuj).
- Testy przełącznika: brak scenariusza Tab (zamknięcie + gdzie ląduje fokus), Shift+Tab, Esc na wyzwalaczu przy otwartym menu, kliknięcia w wyzwalacz przy otwartym menu oraz `isChanging()` (spinner/aria-busy). Test „closes on an outside click” nie asercjonuje, że fokus nie został skradziony (nazwa obiecuje więcej niż sprawdza).
- Test `rekey` nie pokrywa kolizji z istniejącą notatką pod nowym kluczem ani szkiców e-mail/social.

### Wymagane (przed commitem) — blokujące
1. Naprawić wysyłkę e-maila przerywaną przy zniszczeniu komponentu (podwójna wysyłka, pkt Bugs 1): stan `sending` w magazynie i/lub wysyłka niezależna od żywotności komponentu + test.
2. `softphone.component.ts` `currentNote` — usunąć `computed` na niereaktywnym `@Input` (Bugs 2) + test notatki po `rekey`.
3. Zdecydować o ciche kasowanie szkicu przez × (Bugs 4): potwierdzenie przy `hasUnsentDraft` albo wymóg wspólnego merge z FE-132; opisać w notatce wykonania.
4. Test remontu per karta (`@for … track`) — decyzja kluczowa dla AC „szkic nie wycieka do innego kontaktu”.

### Zalecane — ważne
1. `initStatus()` — wyrejestrowanie `ws.onConnect` i `takeUntil` na `events$` (kumulacja przy ponownym logowaniu).
2. `rekey` — scalać zamiast nadpisywać notatkę; sprawdzić klucze przy `originalContactId`.
3. Sanityzacja HTML szkicu przy odtwarzaniu (`DomSanitizer`).
4. Czyszczenie `ContactTabStore` razem z `clearAll()` przy wylogowaniu.
5. Fokus przy Tab w menu statusu; usunąć `stopPropagation` z Esc; nasłuch `document:click` tylko przy otwartym menu.
6. Styl `.navbar__status` + pomiar w `de`/1366 px (AC FE-119 nadal bez dowodu).

### Drobne
- Martwa zmienna `current` w `@for` (używać jej zamiast `activeTab()!`).
- `subjectEdited` jako sygnał; szablon bez treści nie przeżywa przełączenia karty.
- `aria-controls` menu ↔ wyzwalacz; komunikat dla czytnika podczas `isChanging`.
- SUPER_ADMIN w `canManageProfiles`.
- Nazwa/odpowiedzialność `markAccepted` w `ContactDraftStore` (osobny tracker przy FE-136).

### Summary
**Ocena: 3,5/5** — dobrze przemyślany i czytelny magazyn szkiców oraz poprawny wzorzec menu button z solidnymi testami jednostkowymi, ale zniszczenie komponentu w trakcie wysyłki może zdublować e-mail, `currentNote` opiera się na niereaktywnym `@Input`, cichy × kasuje szkice, a kluczowe decyzje (`@for track`, `clearAll`, pojedynczy `initStatus`) nie mają testów.

---

## Review: FE-132 (bezpieczne zamykanie kart) + FE-133 (znaczniki stanu, nowy kontakt bez przejmowania karty) — 2026-10-29

Zakres: `agent-desktop.component.{ts,html,scss}`, `agent-desktop.tabs.spec.ts`, `agent-desktop.remount.spec.ts`, `contact-tab.store.{ts,spec.ts}`, `contact.service.ts`, `models/contact-tab-marker.{ts,spec.ts}`, `public/i18n/{pl,en,de,uk}.json`.
Znana otwarta kwestia (poza oceną): `POST /abandon` = terminalne ABANDONED, nie prawdziwy zwrot do kolejki.

### Bugs / Critical Issues

- **[MAJOR-1] `agent-desktop.component.ts` `confirmClose()` (HANGUP) ~l.505-510 + `requestClose` l.~470** — po potwierdzeniu: `hangupCall()` i NATYCHMIAST `tabStore.closeTab()`. `CallLifecycleService.endedEffect` (call-lifecycle.service.ts:59-75) reaguje na `ENDED`: ustawia status agenta `AFTER_CONTACT` i szuka karty PHONE do `markAsWrapping`. Karta już nie istnieje, więc: brak panelu dyspozycji (ACW), brak dyspozycji dla kontaktu, a agent zostaje w `AFTER_CONTACT` bez ścieżki powrotu (powrót do AVAILABLE robi zapis dyspozycji). Zachowanie odziedziczone ze starego `closeTab`, ale FE-132 jawnie mówi, że „dyspozycję zamyka się zapisem, nie ×", więc rozłączenie z × powinno prowadzić do ACW, nie usuwać karty. Test (`closing PHONE tabs`) mockuje `hangupCall` i asercjonuje `tabs.toHaveLength(0)`, czyli utrwala błąd. **Fix:** po `hangupCall()` NIE zamykać karty (lifecycle przełączy ją na WRAPPING i pokaże dyspozycję); fokus przenieść na panel dyspozycji; test z prawdziwym `CallLifecycleService` (karta -> `WRAPPING`, marker ACW).
- **[MAJOR-2] `confirmClose()` ~l.495-505 — wyścig „stan zmienił się przy otwartym dialogu"** — sprawdzane jest tylko `tabs().find(id)`. Gdy klient rozłączy się przy otwartym dialogu, karta PHONE przechodzi na `WRAPPING` (istnieje), a potwierdzenie i tak wykona `hangupCall()` (no-op/`session.set(null)`) i usunie kartę z dyspozycją niezapisaną (utrata ACW). Dla RETURN: dialog „zwróć kontakt ze szkicem" wisi dla karty, której już nie ma, a potwierdzenie cicho nic nie robi. **Fix:** w `confirmClose` re-walidować: HANGUP tylko gdy `tab.status !== 'WRAPPING'` i `session()?.contactId === tab.contactId`; dodatkowo `effect`, który zamyka `pendingClose`, gdy karta zniknie lub wejdzie w `WRAPPING`. Test: zmiana stanu przy otwartym dialogu.
- **[MAJOR-3] `onTabKeydown` l.~369 — `Delete` bez `event.repeat`** — przytrzymany/powtarzany klawisz: pierwszy keydown uruchamia zwrot (EMAIL bez szkicu = natychmiast, bez pytania), karta znika, fokus przeskakuje na sąsiednią kartę (`removeTabAndMoveFocus`), kolejny auto-repeat keydown trafia w TĘ kartę i zwraca następny kontakt. Straty: kaskadowe, nieodwracalne ABANDONED kilku kontaktów. **Fix:** `if (event.repeat) return;` przed `requestClose`; test z `repeat: true`.
- **[MAJOR-4] `removeTabAndMoveFocus` l.~536-548 + `returnToQueue` — kradzież fokusu po asynchronicznym sukcesie** — fokus ustawiany bezwarunkowo po odpowiedzi API. Agent, który po kliknięciu × przeszedł na inną kartę i pisze w edytorze, dostaje fokus skradziony do paska kart po ~100-500 ms (utrata wpisywanego znaku/zaznaczenia). Łamie też ducha FE-133 („fokus nie jest kradziony"). **Fix:** przenosić fokus tylko gdy `document.activeElement` jest na zamykanej karcie/jej ×, w `body` lub w usuwanym panelu (zapamiętać „focus was inside" w `requestClose`, nie po odpowiedzi). Test: fokus w edytorze innej karty w trakcie zwrotu.
- **[MEDIUM-5] `confirmClose` HANGUP dla `RINGING`** — × / Delete na dzwoniącej karcie PHONE pyta „Rozłączyć rozmowę?" i woła `hangupCall()`, a ta gałąź (`softphone.service.ts:295`) dla `RINGING` tylko zeruje sesję, bez `activeCall.reject()` i bez HTTP. Połączenie dzwoni dalej po stronie Twilio/backendu, a agent nie ma karty. **Fix:** dla `session.state==='RINGING'` użyć `CallLifecycleService.rejectRingingCall()` (usuwa kartę i odrzuca) albo wyłączyć × na dzwoniącej karcie; osobny tekst potwierdzenia „Odrzucić połączenie?".
- **[MEDIUM-6] `returnToQueue` error path** — każdy błąd = „karta pozostaje, spróbuj ponownie". Dla 404/409 (kontakt przepisany innemu agentowi po timeout `ContactAssignmentMonitor`, już zamknięty) ponowna próba nigdy się nie uda, a karta jest nieusuwalna (zombie; szkic też zostaje). Spec R1 mówi „4xx komunikat, nie zamykać", ale bez wyjścia awaryjnego agent jest uwięziony. **Fix:** rozróżnić status: 404/409 -> komunikat „kontakt nie jest już Twój" + zamknięcie karty (po potwierdzeniu, gdy jest szkic); inne błędy -> obecne zachowanie. Test dla 409 (dziś asercja „karta zostaje" dla 409 utrwala pułapkę).
- **[MEDIUM-7] `newContactEffect` l.~300-318 — fałszywe „Nowy kontakt" przy montażu** — `knownTabIds` startuje pusty, a `ContactTabStore` jest singletonem root. Przy każdym ponownym wejściu na `/agent/desktop` (powrót z innej trasy) wszystkie istniejące nieaktywne karty nie-PHONE są „świeże" i ogłaszany jest pierwszy z nich jako nowy kontakt. Poza tym `find` ogłasza tylko jedną kartę, gdy w jednym ticku dojdzie kilka (np. recovery), a tekst nie jest nigdy czyszczony, więc identyczny komunikat (ten sam klient, ten sam kanał) nie zostanie ogłoszony ponownie. **Fix:** zainicjować `knownTabIds` z aktualnych kart przy tworzeniu komponentu (pierwszy przebieg tylko zapisuje), ogłaszać wszystkie nowe (łączyć lub kolejkować), czyścić tekst po renderze jak w `ringingAnnouncement` (clear -> `afterNextRender` -> set). Test: remount z 2 kartami nie ogłasza; dwa kontakty naraz.
- **[LOW-8] Brak ochrony treści karty podczas zwrotu** — w trakcie `returningTabIds` panel e-mail/social jest aktywny; agent może wysłać odpowiedź, a potem `abandon` zakończy się sukcesem (ABANDONED po wysłanej odpowiedzi). **Fix:** `inert`/wyłączenie panelu karty w locie albo ignorowanie wyniku, gdy karta zniknęła i oznaczenie kontaktu jako wysłany.

### Security Concerns
_None identified._ (`SKIP_ERROR_TOAST` przez `HttpContext` używany poprawnie; brak interpolacji HTML; nazwy kontaktów renderowane przez interpolację Angulara.)

### Architecture / Pattern Violations

- **[MEDIUM-9] `agent-desktop.component.html` l.~232-318 — ryzyko `aria-required-children` (axe ≥ 4.5)** — `role="tablist"` zawiera dzieci `div[role=presentation]`, a w nich obok `role="tab"` także `<button>` × i `<span class="sr-only">`. Presentation przezroczyste dla przypisania kart, ale axe zgłasza „element has children which are not allowed: button" (tablist dopuszcza tylko `tab`). Test strukturalny sprawdza tylko brak potomka w tabie, nie reguł axe. **Fix (wybrać):** (a) przenieść × poza `tablist` (np. grupa/pasek akcji obok, powiązana `aria-label`), albo (b) wrapper `div role="presentation"` zostawić, ale × i opis dać `role="presentation"`-neutralnie przez układ DOM: tablist -> karty, a × w osobnym kontenerze pozycjonowanym CSS (absolute). Dodać test axe (`axe-core`/`vitest-axe`) na prawdziwym szablonie lub przynajmniej asercję „dzieci tablist = tylko tab/presentation z jednym tab".
- **[LOW-10] `agent-desktop.component.ts` `ngOnInit` `destroyRef.onDestroy(() => destroyed = true)`** — rejestrować w konstruktorze/polu; rozważyć `AbortSignal`/`takeUntilDestroyed` zamiast flagi. Subskrypcja `abandonContact().subscribe` bez `takeUntil` jest celowa (zwrot ma się domknąć), ale po zniszczeniu i tak woła `notifications`/`tabStore` — dopuszczalne, warto opisać komentarzem.
- **[LOW-11] `ContactService.abandonContact(id, skipErrorToast=false)`** — flaga boolean w API serwisu; lepiej osobna metoda lub obiekt opcji, bo wołający nie widzi znaczenia `true` (`abandonContact('c', true)`).

### Improvements & Suggestions

- **Focus po anulowaniu (`askToClose`, `rememberFocus`)** — przy kliknięciu myszą na × w Safari/Firefox (macOS) przyciski nie dostają fokusu, więc `activeElement` = `body` i powrót fokusu nic nie robi. Zapamiętać element z `event.currentTarget`/przekazać go do `requestClose(tab, trigger)`.
- **Fokus po zamknięciu nieaktywnej karty** — fokus ląduje na aktywnej karcie, nie na sąsiedniej zamkniętej (spec: „sąsiednia karta lub region"). Przy zamykaniu nieaktywnej karty wskazać sąsiada.
- **Zablokowane × bez informacji dla `Delete`** — `requestClose` na `WRAPPING` cicho wraca; Delete na karcie ACW nie daje żadnego komunikatu. Rozważyć ogłoszenie powodu (polite) lub focus na `aria-describedby`.
- **`closeLabel` dla PHONE** — „Rozłącz i zamknij" także dla zablokowanego ACW i dla RINGING (mylące). Rozdzielić etykiety wg stanu.
- **`title` + `aria-label` na `role=tab`** — czytnik może powtórzyć nazwę jako opis. `title` zostawić tylko dla widocznego skrótu (lub `aria-description` pominąć).
- **Tokeny** — nowe fallbacki hex w `__close`: `var(--text-3, #64748b)`, `var(--danger-text, #dc2626)` (kryterium „bez literałów hex"); usunąć fallbacki, tokeny istnieją w obu motywach. Marker używa wyłącznie par `*-soft`/`*-text` (OK). Zmierzyć kontrast (`--neutral-text` 40%L na `--neutral-soft` 94%L jasny; 80%L/28%L ciemny — wizualnie OK, brak pomiaru w testach). Disabled × `opacity:.5` — wyjątek WCAG, ale powód jest podany tekstowo (dobrze).
- **Martwy klucz i18n** `agent.desktop.closeContact` (nieużywany po zmianie) — usunąć z 4 plików.
- **Tłumaczenia metod w szablonie** (`tabAriaLabel`, `markerLabel`, `closeLabel`) — `translate()` nie jest reaktywne na zmianę języka przy OnPush; wzorzec odziedziczony po `getTabTypeLabel`, ale przy zmianie języka etykiety odświeżą się dopiero po zmianie kart. Użyć `translateSignal`/`selectTranslate` lub pipe.
- **Marker po `updateTabContactId`** — marker PHONE porównuje `session.contactId === tab.contactId`; po moście attended-transfer (`contactId` karty -> UUID) sesja może nadal mieć `CA_…`. Sprawdzić i objąć testem (`originalContactId`).
- **`MAX_ASYNC_TABS`** nie liczy `SOCIAL` (liczone tylko CHAT/EMAIL) — kontakty social omijają limit 3 (wpływ tylko na `MAX_TOTAL`). Poza zakresem (BE-156), ale warto zanotować.

### Testy — luki względem AC

- `Delete does nothing while typing in the editor`: zdarzenie wysyłane na `#reply` (stub), które nie jest potomkiem karty, więc test nie może się wywalić i nie dowodzi niczego o scoping (realna ochrona to `event.target !== currentTarget` — test na `.contact-tab__close`/zagnieżdżonym elemencie wnętrza karty brakuje). Brak testu `repeat`.
- Brak: wyścigu stanu przy otwartym dialogu (MAJOR-2), podwójnego kliknięcia × (guard `returningTabIds`, w locie z `Subject`), karty znikającej w locie, zwrotu bez zmiany fokusu na edytor (MAJOR-4), `Escape` w dialogu zamknięcia (fokus wraca na opener — test sprawdza tylko `tabs.length`, nie `activeElement`), 404, remountu z kartami (MEDIUM-7), `HANGUP` z prawdziwym lifecycle (MAJOR-1), RINGING × (MEDIUM-5), roving tabindex po zamknięciu/dodaniu, `aria-disabled` na × jako realnego blokowania `Delete`.
- Test „makes the first tab active and does not announce it" nie asercjonuje braku ogłoszenia (`live.textContent` pusty).
- Test marker `RINGING` nie sprawdza kombinacji z `DRAFT` (RINGING + szkic -> `Szkic`, zgodne z implementacją, ale niezapisane).
- `agent-desktop.remount.spec.ts`: poprawne dostosowanie (`setActiveTab`), brak uwag.
- Nie uruchamiano testów/lintu w ramach przeglądu (tylko analiza statyczna).

### Positive Observations

- `resolveTabMarker` to czysta funkcja z poprawnymi priorytetami; źródło prawdy stanu hold = `SoftphoneService.session()` per `contactId`, RINGING poprawnie NIE daje „Rozmowa", `tab.status==='HOLD'` świadomie ignorowany.
- `openTab` z jawnym `isActive` + „pierwsza karta aktywna"; wszystkie ścieżki PHONE (`incoming`, `outbound`, `contact-assigned`, recovery, konsultacja) wołają `softphoneService.incomingCall` zaraz po `openTab`, więc `ringingEffect` aktywuje kartę — brak regresji FE-114 (zweryfikowane w kodzie). `closeTab` aktywuje sąsiada zamiast pierwszej karty.
- × jako rodzeństwo `role="tab"`, `aria-disabled` zamiast `disabled` (zachowuje fokus), `aria-describedby` z powodem, cel 24x24 px, `Delete` ograniczony `event.target === currentTarget`.
- Brak usunięcia karty/szkicu przy błędzie API; `SKIP_ERROR_TOAST` zamiast podwójnego toastu; zwrot jednokrotny dzięki `returningTabIds`.
- i18n kompletne w pl/en/de/uk (te same klucze, poprawne diakrytyki, parametr `{{name}}`); testy na prawdziwym szablonie z podmienionymi tylko ciężkimi dziećmi.

### Summary
**Ocena: 3/5** — czysta implementacja znaczników i dobre odwzorowanie struktury ARIA, ale przepływ rozłączenia z × omija ACW, kilka wyścigów (zmiana stanu przy otwartym dialogu, auto-repeat Delete, kradzież fokusu po odpowiedzi API) i fałszywe ogłoszenie przy remoncie wymagają poprawek przed zamknięciem FE-132/FE-133.

### Priorytety
1. MAJOR-1/2 (hangup omija ACW, rewalidacja stanu w `confirmClose`), MAJOR-3 (`event.repeat`), MAJOR-4 (kradzież fokusu) — przed commitem.
2. MEDIUM-5 (RINGING), MEDIUM-6 (404/409 uwięziona karta), MEDIUM-7 (ogłoszenie przy montażu), MEDIUM-9 (axe `aria-required-children`).
3. Testy z listy luk, tokeny bez hex, martwy klucz i18n, LOW-8/10/11.

### Status poprawek (FE-132/FE-133, runda 2 — 2026-10-29)
- **MAJOR-1** naprawione: HANGUP wywołuje tylko `hangupCall()`, karta przechodzi na WRAPPING przez `CallLifecycleService`; test z prawdziwym lifecycle.
- **MAJOR-2** naprawione: `confirmClose` re-waliduje stan, `pendingGuardEffect` zamyka dialog gdy karta zniknie/wejdzie w ACW/zmieni się stan połączenia.
- **MAJOR-3** naprawione: `event.repeat` ignorowany dla Delete (test).
- **MAJOR-4** naprawione: fokus przenoszony tylko gdy po usunięciu karty wylądował na `<body>` (test: edytor innej karty w trakcie zwrotu).
- **MEDIUM-5** naprawione: × na dzwoniącej karcie = potwierdzenie „Odrzucić?” + `rejectRingingCall()`; PHONE bez połączenia (stale) zamykany bez dialogu.
- **MEDIUM-6** naprawione: 404/409 z `/release` zamyka kartę z komunikatem „nie jest już przypisany do Ciebie”, inne błędy zostawiają kartę i szkic (testy).
- **MEDIUM-7** naprawione: `knownTabIds` z aktualnych kart, ogłaszane wszystkie nowe, tekst czyszczony przed ustawieniem (testy remount/wiele naraz).
- **MEDIUM-9** naprawione: tablist bez dzieci DOM, `aria-owns` wskazuje tylko karty, × w osobnym elemencie obok karty; axe-core nie ma w zależnościach, więc test strukturalny zamiast axe.
- **LOW-8** naprawione: panel karty `inert` + `aria-busy` w trakcie zwrotu. **LOW-10** naprawione: `destroyed` przez `DestroyRef` w konstruktorze. **LOW-11** naprawione: `abandonContact` bez flagi, osobna `releaseContact`.
- Improvements: fokus po Escape/anulowaniu wraca na trigger (fallback Safari), fokus na sąsiedzie także po zamknięciu nieaktywnej karty, ogłoszenie powodu przy Delete na ACW, etykiety × per stan, `title` tylko na nazwie, brak fallbacków hex w `__close`, usunięty klucz `closeContact`, etykiety reaktywne na zmianę języka, dopasowanie sesji do karty także po `originalContactId` (most attended-transfer) — wszystkie naprawione.
- **Odroczone:** `MAX_ASYNC_TABS` nie liczy SOCIAL (BE-156, poza zakresem).

### Status poprawek — runda 3 (test w przeglądarce, 2026-10-29)
- **Wyścig release vs ponowne przypisanie:** `ContactTabStore` śledzi `beginRelease/noteAssignedDuringRelease/endRelease`; przypisanie tego samego `contactId` w trakcie zwrotu (alert service + recovery) jest odkładane, a po sukcesie karta jest otwierana ponownie (nieaktywna, z ogłoszeniem „Nowy kontakt”). Błąd zwrotu przy ponownym przypisaniu zostawia kartę. Testy: komponent, store, alert service.
- **Delete po ArrowRight:** fokus przenoszony synchronicznie w handlerze strzałek (test z dwoma zdarzeniami bez `await`).
- **Nazwy kart:** `normalizeContactName` (nowy `models/contact-name.ts`) stosowane w jednym miejscu (`openTab`, `updateTabCustomerInfo`) oraz w obsłudze przypisań async; ta sama nazwa w karcie, `aria-label`, `title`, ogłoszeniu i toaście.

---

## Review: FE-122 ACW (dyspozycja) jako niemodalny stan karty kontaktu — 2026-10-29

Zakres: `disposition-panel/*` (przepisany z `<dialog>` na `<section>`), `services/disposition-save.service.ts`, `components/acw-banner/*`, `agent-desktop.component.{ts,html}`, `contact-tab.store.ts`, `contact-tab.model.ts`, `contact-draft.store.ts`, i18n x4, specyfikacje. Ścieżki względem `frontend/src/app/features/agent/`.

### 🐛 Bugs / Critical Issues

- **BLOCKER-1 — `services/disposition-save.service.ts:57-66` (+ `agent-status.service.ts:128-147`): nieudana zmiana statusu na AVAILABLE zawiesza ACW na stałe.** Prawdziwe `AgentStatusService.changeStatus()` NIE zwraca błędu: `catchError(() => { toast; return EMPTY; })`, a przy `isChanging()` zwraca od razu `EMPTY`. Obserwable kończy się bez `next` i bez `error`, więc ani `next: () => this.finish(req)`, ani `error: () => … this.finish(req)` nie zostaną wywołane. Skutek: `savingIds` zawiera `contactId` do końca sesji, przycisk „Zapisz" jest `disabled` + `aria-busy` na zawsze, karta ACW nie zamyka się, × jest zablokowany w WRAPPING (FE-132), `closed$` nie emituje — agent musi odświeżyć stronę (a po odświeżeniu ACW znika, R3). Dyspozycja jest już zapisana na backendzie, więc to czysta pułapka UI. Ta sama ścieżka dla zbiegu z `isChanging` (np. PATCH `AFTER_CONTACT` jeszcze leci przy szybkim zapisie). Test `disposition-panel.component.spec.ts:276-284` ("a failed status change still closes the tab") jest nierzetelny: mockuje `changeStatus` jako `throwError`, czego prawdziwy serwis nigdy nie robi (maskuje błąd). **Fix:** w `save()` użyć `this.status.changeStatus('AVAILABLE').pipe(defaultIfEmpty(null))` / `finalize(() => this.finish(req))` (finish w `complete`/`finalize`, nie w `next`), albo `.subscribe({ complete: () => this.finish(req) })`. Usunąć martwą gałąź `error:` i toast `statusChangeError` (serwis i tak pokazuje własny `agent.status.errorChange`) lub rozróżnić sukces przez porównanie `status.current()` po zakończeniu. Poprawić test: mock `of()`/`EMPTY` (zachowanie realnego serwisu) i asercja, że karta się zamyka, `isSaving` wraca na false.

### 🐛 Major

- **MAJOR-2 — `components/acw-banner/acw-banner.component.ts:34-38` + `pages/agent-desktop/agent-desktop.component.html:405`: widok kalendarza (punkt otwarty) ukrywa formularz ACW, a baner też się chowa.** `inFront = url.startsWith('/agent/desktop') && tab.isActive` nie zna `calendarTabActive` (signal lokalny pulpitu, `agent-desktop.component.ts:228`; panel kart jest renderowany tylko `@if (!calendarTabActive())`). `ringingEffect` zamyka kalendarz tylko przy RINGING, nie przy końcu rozmowy. Scenariusz: agent ma otwarty kalendarz, rozmowa się kończy → karta WRAPPING jest `isActive`, formularz niewidoczny, baner ukryty, brak ogłoszenia — dokładnie ten przypadek, który brief chciał pokryć (AC „bez kradzieży fokusu, ale ostrzeżenie"). **Fix:** przenieść `calendarTabActive` do współdzielonego store'a (np. `ContactTabStore.calendarOpen` lub `AgentDesktopUiStore`) i w banerze: `inFront = desktop && tab.isActive && !calendarOpen()`; `complete()` ma też zamknąć kalendarz (`calendarOpen.set(false)`). Test: baner widoczny przy `calendarOpen=true` + aktywnej karcie WRAPPING.
- **MAJOR-3 — `disposition-save.service.ts:46-56`: podwójny toast przy błędzie zapisu.** `ContactService.setDisposition` nie ustawia `SKIP_ERROR_TOAST` (`core/interceptors/error-handler.interceptor.ts:25`), więc przy błędzie HTTP interceptor pokazuje toast i serwis dokłada własny `errorSave`. AC FE-122 wymaga „jeden toast" (FE-120). Test "one error toast" tego nie widzi, bo mockuje `ContactService`. **Fix:** przekazać `context: new HttpContext().set(SKIP_ERROR_TOAST, true)` w `setDisposition` (jak przy `/release` w FE-132) albo usunąć własny toast błędu i zostawić interceptor; dodać test z `HttpTestingController` + prawdziwym interceptorem. To nawrót wzorca z FE-112 (2. wystąpienie wcześniej, teraz 3.).

### ⚠️ Security Concerns

_None identified._ (Brak nowych wektorów; szkic dyspozycji tylko w pamięci, czyszczony w `closeTab`/`reset`/`rekey`.)

### 🏗️ Architecture / Pattern Violations

- **MEDIUM-4 — `disposition-panel.component.ts:122-127`: heurystyka `focusLost` kradnie fokus przy montażu.** Reguła „fokus tylko na żądanie" jest łamana przez `document.activeElement === body`: powrót przeglądarki (Alt+←/→), odświeżenie/rekonstrukcja widoku lub zniszczenie kontrolki, która miała fokus (np. zamknięty dialog, usunięta karta), kończą na `<body>` i formularz ACW zabiera fokus bez żądania agenta. Intencja (znika przycisk Zakończ w softphone) jest słuszna, ale to powinno być zdarzenie „rozmowa zakończona na tym pulpicie", nie sniffing DOM. **Fix:** `CallLifecycleService`/`markAsWrapping` ustawia `requestAcwFocus(tabId)` tylko gdy zakończenie nastąpiło na aktywnej karcie PHONE pulpitu i `activeElement` (przed zmianą) był wewnątrz softphone; usunąć `focusLost` z `ngOnInit`. Test: montaż formularza przy `activeElement === body` NIE przenosi fokusu.
- **MEDIUM-5 — `contact-tab.store.ts:169-178`: `wrappingSince` nigdy nie jest zerowany i nie jest odtwarzany.** `status === 'WRAPPING' ? (t.wrappingSince ?? Date.now())` — jeśli karta wróci do ACTIVE/HOLD i ponownie do WRAPPING (np. powtórny przypadek callbackModal/reuse karty), zegar ACW zacznie od starej wartości. Dziś `updateTabStatus` wywoływane jest tylko z `markAsWrapping`, więc brak triggera, ale API jest publiczne. **Fix:** przy przejściu na statusy ≠ WRAPPING ustawiać `wrappingSince: undefined`. R3 (odświeżenie strony): `agent-recovery.service` nie odtwarza WRAPPING (brak referencji) — zegar i karta ACW giną po F5, status backendu zostaje `AFTER_CONTACT`; udokumentować jako zależność FE-136 i sprawdzić, czy agent nie utknie w `AFTER_CONTACT` bez karty do zapisania.
- **MEDIUM-6 — `disposition-panel.component.ts:97-170`: brak cache'u dyspozycji, refetch przy każdym montażu.** `getAvailableDispositions` + `getContact` lecą przy każdym powrocie na kartę (2 requesty + skeleton + opóźniony fokus). Dodatkowo szkic może zawierać kod z listy fallback (`DISPOSITION_CODES`), a po powrocie przyjść lista z API bez tego kodu → `selectedCode` wskazuje na nieistniejącą kartę, a „Zapisz" wysyła nieistniejącą dyspozycję. **Fix:** cache listy per `contactId` w `ContactDraftStore`/`DispositionSaveService` (R2: kampania/kolejka nie zmienia się w trakcie ACW) oraz walidacja `selectedCode ∈ availableDispositions()` po załadowaniu (jeśli nie — wyczyść wybór i `submitAttempted` zostaje).
- **LOW-7 — `acw-banner.component.ts:34` `acwTabs()[0]` = „najstarsza" tylko wg kolejności kart.** Komentarz store'a mówi „oldest first", ale sortowanie to kolejność tablicy, nie `wrappingSince`. Przy 2+ kartach ACW baner pokazuje tylko jedną, o pozostałych informuje wyłącznie znacznik FE-133. **Fix:** `toSorted((a,b) => a.wrappingSince! - b.wrappingSince!)` i licznik „+N" w banerze.
- **LOW-8 — baner nie ma skrótu/pozycji w kolejności Tab.** `cc-acw-banner` jest po `<cc-app-shell/>` w DOM, więc agent klawiaturowy musi przejść całą stronę, żeby do niego dojść (AC „obsługa wyłącznie klawiaturą"). **Fix:** skrót FE-114 „przejdź do ACW" (wywołuje `complete()`), lub `aria-keyshortcuts`; ewentualnie ogłoszenie z informacją o skrócie.
- **LOW-9 — ogłoszenie banera powtarza się.** `@if (pendingTab())` w regionie `role="status"` wstawia tekst za każdym razem, gdy `pendingTab` przełącza się null↔karta (zmiana trasy desktop↔customers, przełączenie karty na pulpicie) — czytnik ekranu słyszy „Rozmowa z X zakończona…" wielokrotnie, choć nic się nie zmieniło. Zgodne z wzorcem `knownTabIds` z FE-133: ogłaszać tylko raz per `tab.id` (zapamiętać `announcedAcwIds`), tekst czyścić po zamknięciu.
- **LOW-10 — draft kluczowany `originalContactId ?? contactId` vs `rekey` po `tab.contactId`.** Panel zapisuje szkic pod `originalContactId`, `phoneNoteOf` czyta po `tab.contactId`, `updateTabContactId` rekey'uje tylko stary `contactId`. Przy moście attended-transfer w trakcie ACW szkic pod `originalContactId` nie jest przenoszony; `closeTab` czyści oba ID (OK, brak wycieku), ale utrata szkicu w tym brzegu jest możliwa. Ujednolicić klucz.
- **LOW-11 — `disposition-panel.component.html:7`/`:101` semantyka.** `role="radio"` na przyciskach bez nawigacji strzałkami/roving tabindex (obietnica ARIA radiogroup niespełniona; wcześniej istniało, ale nie jest już „zaryzykowanym modalem") oraz nagłówek zmieniony `h2→h3` bez weryfikacji, że w panelu karty istnieje poprzedzający `h2`. `aria-label` na textarea nadpisuje widoczny `<label for>` (WCAG 2.5.3 Label in Name — nazwa dostępna „Notatka do kontaktu, opcjonalna" vs widoczny tekst; sprawdzić dopasowanie).

### 🔧 Improvements & Suggestions

- `disposition-panel.component.ts:102`: `setInterval` co sekundę w strefie Zone.js wywołuje globalny tick CD (aplikacja nie jest zoneless); dla zegara ACW użyć `NgZone.runOutsideAngular` + `now.set` (sygnał i tak oznaczy komponent), albo `toSignal(timer(0,1000))` z `takeUntilDestroyed` zamiast ręcznego `OnDestroy`.
- Interplay z FE-131: wykluczenie `dispositionDraft` z `draftContactIds` jest **poprawne** — karta WRAPPING ma × zablokowany (`isCloseDisabled`), więc guard „zamknąć szkic?" nie ma kiedy zadziałać, a znacznik ACW i tak ma pierwszeństwo w `resolveTabMarker`. Jedyna ścieżka zamknięcia poza zapisem to wylogowanie (`reset`+`drafts.clearAll`) — OK.
- FE-114: `AgentHotkeysService` nie odwołuje się do dyspozycji, a jedyny skrót reagujący na pisanie (`?`) ma `isEditableElement` — brak regresji; ale brak testu, że `Esc` w polu notatek nie zamyka formularza **w prawdziwym szablonie pulpitu** (jest tylko w komponencie).
- `disposition-save.service.ts`: po zapisie jednej z dwóch kart ACW agent staje się `AVAILABLE` z drugą kartą wciąż w WRAPPING (R1 „dokańcza otwarte kontakty" — zgodne z briefem, ale warto jednoznacznie opisać w TASKS; routing zacznie przypisywać nowe kontakty w trakcie ACW drugiej karty).
- `finish()` przy wylogowaniu w trakcie zapisu: PATCH statusu pójdzie bez sesji (401); dodać guard `if (!authStore.isLoggedIn()) return` lub `takeUntil(logout$)`.
- `acw-banner.component.scss`: `position: fixed` lewy dół z-index 800 może zasłaniać treść/softphone na 1366×768 (WCAG 2.4.11 Focus Not Obscured) — zweryfikować w przeglądarce razem z FE-123.

### ✅ Positive Observations

- Zapis przeniesiony do `DispositionSaveService` (root) z `savingIds` per kontakt: przeżywa zniszczenie formularza, remontowany formularz czyta `isSaving` z serwisu (test „remounted form stays blocked"), pojedynczy punkt sprzątania przez `closeTab` (drafty + notatka PHONE), sąsiad liczony zgodnie z `closeTab`.
- `closed$` subskrybowane z `takeUntilDestroyed` w konstruktorze; zegar sprzątany w `ngOnDestroy`; wszystkie subskrypcje formularza z `takeUntilDestroyed` — brak wycieków.
- Zegar ACW pochodny od `wrappingSince` (przeżywa remount), poza regionem live; błąd walidacji `role="alert"` dopiero po próbie zapisu, z `aria-invalid` + `aria-describedby`; podpowiedź fallback zmieniona z `alert` na `status`.
- Baner: stały region `role="status"` (poprawnie, żeby wstawiony razem z tekstem nie był pomijany), brak `focus()`, test „never moves focus"; `requestAcwFocus/consumeAcwFocus` jednorazowe, reset w `reset()`.
- Usunięte obejścia modalne (`cancel`, `showModal`), brak nowych kolorów hex (tokeny), i18n kompletne w pl/en/de/uk z poprawnymi diakrytykami, usunięty martwy klucz `acwTimer`.
- Fokus w `setActiveTab` tylko przy jawnej aktywacji karty (strzałki nie wchodzą w tę ścieżkę).

### Testy — jakość
- Panel testowany na prawdziwym szablonie (dobrze), ale `AgentStatusService` jest mockowany niezgodnie z kontraktem (`throwError` zamiast `EMPTY`) — to ukrywa BLOCKER-1; `ContactService` mock omija interceptor — ukrywa MAJOR-3.
- Brak testów: baner przy otwartym kalendarzu (MAJOR-2), reentrancja `isChanging`, montaż przy `activeElement===body`, ogłoszenie banera nie powtarza się, wylogowanie w trakcie zapisu, `wrappingSince` po powrocie z WRAPPING.

### Summary
**Ocena: 3/5** — dobry rdzeń architektury (serwis zapisu poza cyklem życia formularza, zegar poza live region, baner bez kradzieży fokusu), ale realny `changeStatus` zwraca `EMPTY` na błąd, przez co nieudana zmiana statusu zawiesza ACW na stałe (a test to maskuje), kalendarz ukrywa formularz i baner jednocześnie, a błąd zapisu daje podwójny toast.

### Priorytety
1. **BLOCKER-1** (`changeStatus` → `EMPTY`: zakończenie w `complete`/`finalize`, poprawić mock w teście), **MAJOR-2** (kalendarz vs baner), **MAJOR-3** (`SKIP_ERROR_TOAST`) — przed commitem.
2. MEDIUM-4 (heurystyka `focusLost`), MEDIUM-5 (`wrappingSince`/R3), MEDIUM-6 (cache dyspozycji + walidacja kodu z szkicu).
3. LOW-7…11 i luki testowe z listy powyżej.

### Status poprawek FE-122 (runda 2)

- **BLOCKER-1 — naprawione.** `DispositionSaveService` czeka na zakończenie trwającej zmiany statusu (`isChanging`), woła `changeStatus('AVAILABLE')` i zamyka kartę w `finalize` (nie w `next`), więc karta zamyka się także gdy `changeStatus` kończy się pusto. Usunięta martwa gałąź `error:` i nieużywany klucz `agent.disposition.statusChangeError` (4 języki). Testy mockują realny kontrakt (`EMPTY`), plus test `isChanging`.
- **MAJOR-2 — naprawione.** Widok kalendarza jest współdzielony (`ContactTabStore.calendarOpen`, czyszczony w `reset`). Baner pokazuje się także przy otwartym kalendarzu, a jego przycisk zamyka kalendarz. Decyzja: gdy rozmowa kończy się do ACW przy otwartym kalendarzu, kalendarz zostaje otwarty i polegamy na banerze (bez kradzieży fokusu). Testy dodane.
- **MAJOR-3 — naprawione.** `ContactService.setDisposition` używa `SKIP_ERROR_TOAST`; serwis pokazuje jeden toast. Test z `HttpTestingController` i prawdziwym interceptorem.
- **MEDIUM-4 — naprawione.** Usunięta heurystyka `focusLost` z panelu. `CallLifecycleService` żąda fokusu tylko gdy rozmowa kończy się na aktywnej karcie PHONE na pulpicie (kalendarz zamknięty), a fokus jest w softphone lub zgubiony (nie w polu edycji). Testy: montaż z fokusem na `<body>` nie kradnie fokusu.
- **MEDIUM-5 — naprawione.** `wrappingSince` ustawiane przy wejściu w WRAPPING i zerowane przy wyjściu. R3 (odtwarzanie ACW po F5) zostaje w FE-136, bez zmian.
- **MEDIUM-6 — naprawione.** Cache dostępnych dyspozycji per contactId w `ContactDraftStore` (czyszczony w `clear`/`clearAll`; lista awaryjna nie jest cache'owana). Zapamiętany kod spoza załadowanej listy jest odrzucany.
- **LOW-7 — naprawione** (sortowanie po `wrappingSince`, "+N"). **LOW-8 — naprawione** (baner wcześnie w DOM powłoki, `<section>` z `aria-label`, pozycja wizualna bez zmian; skrót FE-114 nie dodany, odroczony). **LOW-9 — naprawione** (ogłoszenie raz per tab.id). **LOW-10 — naprawione** (`acwDraftKey`, test `rekey`). **LOW-11 — naprawione** (roving tabindex + strzałki/Home/End, `<label for>` bez `aria-label`; hierarchia: `h2` nazwy kontaktu w panelu karty, formularz `h3`).
- Ulepszenia: zegar poza strefą (`runOutsideAngular`); wylogowanie w trakcie zapisu nie zmienia statusu (guard `isAuthenticated`) i zwalnia formularz. Odroczone: sprawdzenie w przeglądarce, czy stały baner nie zasłania treści przy 1366x768 (FE-123).

---

## Review: FE-123 — układ 1366×768 (skala breakpointów, tokeny chrome, kolumny `clamp()`, rail sidenava agenta, `AcwReminderService` + `cc-acw-banner` floating/toolbar) — 2026-10-29

Zakres: niezacommitowane zmiany na `feature/epic-32-agent-ux-a11y` (23 pliki zmienione + `_breakpoints.scss`, `sidenav.component.spec.ts`, `acw-reminder.service.ts`). Testy `acw-banner`, `sidenav`, `app-shell` uruchomione lokalnie: 3 pliki, 28 testów zielonych. Nie uruchamiałem przeglądarki, więc kryteria pomiarowe (chrome ≤ 120/591, brak scrolla poziomego) oceniam tylko z kodu.

### 🐛 Bugs / Critical Issues

- **MAJOR-1 — `acw-banner.component.scss:25-28` + `sidenav.component.scss:344-353`: pływający baner zasłania stopkę sidenava (przełącznik zwijania i wskaźnik połączenia).** Baner jest `position: fixed; inset-inline-start: 1.5rem; inset-block-end: .375rem; z-index: 800`, szerokość do 28rem. Rezerwa `--shell-reserve-bottom` jest dodawana tylko do `padding-bottom` `.shell__content`, która zaczyna się za sidenavem (232 px lub rail 72 px). Baner dalej wisi nad lewym dolnym rogiem, czyli nad `.sidenav__footer` (toggle + `connection`) i nad dolnymi pozycjami menu na niskim oknie. Przy 1366 px baner (24…472 px) przykrywa całą szerokość raila i część treści. Skutek: przy aktywnym ACW agent nie kliknie przełącznika zwijania (WCAG 2.4.11 Focus Not Obscured, przy fokusie na toggle element jest całkowicie przykryty), a komentarz w SCSS („baner siedzi POD treścią") nie jest prawdziwy dla lewej kolumny. Sam FE-122 zostawił ten punkt do weryfikacji w FE-123. **Fix:** odsunąć baner od sidenava (`inset-inline-start: calc(var(--shell-sidenav-w, 0px) + 1.5rem)`, token ustawiany przez `app-shell` z `sidenavCollapsed`/breakpointu) albo wyrenderować baner w obrębie `.shell__main` (`position: absolute` względem kolumny treści) i wtedy rezerwa jest spójna z miejscem. Dodać test/E2E: przy `has-acw-banner` przełącznik sidenava jest klikalny.
- **MAJOR-2 — `sidenav.component.ts:302,475-488`: domyślny rail dla agenta praktycznie nie zadziała na żadnej przeglądarce, która już uruchomiła aplikację.** Dotychczasowy efekt zapisywał `cc_sidenav_collapsed` przy każdym utworzeniu sidenava (także wartość `false`, której użytkownik nigdy nie wybrał). Każdy agent, który wcześniej otworzył pulpit, ma w `localStorage` zapisane `'false'`, a nowa logika uznaje zapisaną wartość za wybór użytkownika (`stored !== null`) i rail się nie włącza. Rail pojawi się tylko na świeżych profilach, więc ręczna weryfikacja 1366×768 u developera/testera pokaże rozwinięty sidenav, a AC „160 px dla treści" będzie spełnione tylko częściowo. **Fix:** nowy klucz (`cc_sidenav_collapsed_v2`) zapisywany wyłącznie w `toggleCollapsed()`, stary klucz ignorowany (opcjonalnie jednorazowo usunięty), plus test „stary klucz `false` nie blokuje domyślnego raila".

### ⚠️ Security Concerns

_None identified._

### 🏗️ Architecture / Pattern Violations

- **MEDIUM-3 — `top-navbar.component.scss` (`__brand-text` `down(lg)`, KPI `down(lg)`): zmiany wspólnego navbara zmieniają zachowanie supervisora i admina (R2).** Tekst marki znika teraz dla WSZYSTKICH ról przy ≤ 1024 px (wcześniej ≤ 640), a pasek KPI pojawia się już od 1025 px (wcześniej od 1101). Zakres 1025–1100 px: KPI + hamburger + marka z tekstem + status + chip użytkownika + Wyloguj w jednym wierszu; nic w diffie nie dowodzi, że mieści się bez przewijania (poprzedni próg 1100 istniał właśnie po to, by tego uniknąć). Kontener `kpi` ma tiery, ale ich granice zależą od tłumaczeń (de/uk). **Fix:** zmierzyć 1025/1100/1280 px w pl i de dla AGENT i SUPERVISOR; jeśli ciasno, zostawić KPI od `1101` (osobny krok skali albo `bp.down(xl)`), a ukrywanie marki ograniczyć do tego, co faktycznie potrzebne wokół 945–1024 px.
- **MEDIUM-4 — `app-shell.component.ts:36`, `sidenav.component.ts:269` vs `_breakpoints.scss`: wartości TS powielają skalę bez żadnego strażnika.** `DESKTOP_BREAKPOINT = 1280` i `AGENT_RAIL_MAX_WIDTH = 1440` są zgodne z `xl`/`xxl` dzisiaj, ale rozjazd nastąpi przy pierwszej zmianie skali i nic go nie złapie (testy używają literałów 1334/1366/1440). **Fix:** test spec czytający `_breakpoints.scss` (import `?raw`, regex na `xl:`/`xxl:`) i porównujący z stałymi; albo jedno źródło w TS (`breakpoints.ts`) walidowane tym samym testem.
- **MEDIUM-5 — AC „breakpointy z jednej skali (grep)": poza agentem i powłoką zostają ok. 50 literałów `@media (max-width: Npx)` w 11 różnych wartościach** (480/520/540/560/600/620/640/768/900/1024…; supervisor, admin, tenants, auth), w tym wspólne komponenty używane na pulpicie agenta: `contact-detail-modal` (600), `confirm-dialog` (480), `disposition-list-editor` (480). Zakres tiketu mówi „agent + powłoka", ale AC mówi o grepie bez zawężenia. **Fix:** albo przepisać wspólne komponenty (3 pliki, trywialne), albo zawęzić AC w TASKS do powierzchni agenta i dopisać follow-up dla reszty.
- **LOW-6 — `agent-desktop.component.scss:~885-975`: deklaracje po `@include` z blokiem `@media` (mixed-decls).** W `&__avatar` mixin stoi przed `border-radius`, `background`, `display`…; Dart Sass traktuje deklaracje po zagnieżdżonej regule jako „mixed declarations" (ostrzeżenie deprecacji, a w przyszłości zmiana kolejności kaskady). **Fix:** przenieść `@include bp.compact-height` na koniec bloku (jak w pozostałych selektorach).

### 🔧 Improvements & Suggestions

- **Chrome na 768 px bez paska przeglądarki / F11:** `compact-height` to `max-height: 720px`. Okno 1366×768 w trybie pełnoekranowym (viewport 768) NIE jest kompaktowe i ma chrome 156 px. AC mówi o 591 px (okno z paskami), więc to zgodne, ale warto to jawnie opisać w DESIGN.md albo podnieść próg do 768, jeśli stanowiska działają w kiosku.
- **Budżet chrome = dokładnie 120 px (48+36+36), bez marginesu:** nie liczy się `shell__breadcrumbs` (min-height 34 px; sprawdzić, czy renderuje się na `/agent/desktop`), nagłówka kontaktu (~37 px), nagłówka e-maila (~46 px) ani panelu AI summary (~44 px). Dopóki nie ma pomiaru (screenshot/E2E 1366×768, `getBoundingClientRect` obszaru treści ≥ 450 px), AC 1 jest niezweryfikowane. Pomiar zalecam dopisać do FE-123 jako dowód (pl i de).
- **`styles.scss:70` `--desktop-main-min: 30rem` nie jest używany nigdzie** (martwy token, a opis zakresu „sensowne minimum dla treści (e-mail, czat)" spełnia tylko e-mail przez `min-width: 18rem`/`17.5rem`). Użyć jako `min-width` `.desktop__main` albo usunąć. Czat nie ma żadnego minimum.
- **`sidenav.component.ts:302` + `app-shell.component.ts:32`:** `sidenavCollapsed` w powłoce startuje jako `false`, a prawdziwa wartość dociera dopiero z efektu sidenava. Przy domyślnym railu agenta pierwsza klatka ma szeroki sidenav i animowany (`transition: margin-left .25s`) skok do 72 px przy każdym wejściu. **Fix:** inicjalizować `sidenavCollapsed` z tego samego źródła (np. wyeksportowana funkcja `defaultCollapsed(role)` lub `output` emitowany synchronicznie w `ngOnInit`) i wyłączyć transition do pierwszego renderu.
- **Dostępność skróconych treści:** `title` na `.contact-panel__info`, `.email-header__from/__to` działa tylko dla myszy. Przy nagłówku jednowierszowym z `ellipsis` użytkownik klawiatury/ekranu dotykowego nie dostanie pełnego adresu (WCAG 1.4.10/1.3.1 spełnione przez DOM, ale praktyczny dostęp nie). Pełny adres jest w wątku poniżej, więc to niski priorytet; rozważyć przełącznik „pokaż nagłówek" albo zostawić zawijanie dla `to` (adresy są dłuższe niż etykiety).
- **`acw-banner.component.html`:** na `<section>` jest jednocześnie `aria-label` i `title` (toolbar) o prawie tej samej treści; czytniki powtórzą nazwę jako opis. Wystarczy `title` na nazwie klienta w chipie albo `aria-describedby` zamiast `title`.
- **`acw-reminder.service.ts:17`:** `onDesktop` = `startsWith('/agent/desktop')` dopasuje także `/agent/desktop-xyz` (obecnie brak takich tras). Dopasować segmentowo (`url.split(/[?#]/)[0]` równe lub zaczynające się od `/agent/desktop/`). Singleton `providedIn: 'root'` z `toSignal` zostaje po wylogowaniu, ale stan pochodzi z `ContactTabStore` (czyszczony w `reset`), więc brak wycieku.
- **Zoom 200% (R3):** przy 1366 px CSS wychodzi ~683×~325 px; sidenav jest szufladą, kolumna klienta ukryta (`down(md)`), ale chrome 120 px to wtedy ok. 37% wysokości. Nie ma testu ani opisu weryfikacji; dopisać do raportu z pomiaru (WCAG 1.4.4/1.4.10).
- Tokeny chrome w DESIGN.md i `design.json` dopisane (skala, `has-acw-banner`) — dobrze; dopisać ostrzeżenie o MAJOR-2 (klucz localStorage) i wartość 1440 inkluzywną (`<=`) zarówno w CSS, jak i `innerWidth`.

### ✅ Positive Observations

- **Regresje FE-122 (punkt 1) — brak.** Region `role="status"` jest renderowany tylko w instancji `floating` (agent shell), stały i niezależny od miejsca rysowania karty; `announceEffect` czyta `pendingTab` (nie `visible`), więc przejście trasa↔pulpit nie ogłasza ponownie, a chip w toolbarze nie dubluje (test „toolbar instance has no live region" + test „first/live/changes" z MutationObserver).
- Widoczność przy otwartym kalendarzu zachowana: `pendingTab` uwzględnia `calendarOpen`, a chip jest w `<header>` pulpitu, który renderuje się także przy kalendarzu. `complete()` w serwisie ma identyczną sekwencję jak wcześniej (zamknięcie kalendarza, `setActiveTab`, `requestAcwFocus`, nawigacja) — fokus z przycisku tylko na jawną akcję, bez `focus()` przy pojawieniu się.
- Sprzątanie klasy `has-acw-banner`: `effect` przełącza klasę tylko z instancji `floating`, `DestroyRef.onDestroy` ją zdejmuje (testy: destroy, przejście na pulpit). Wylogowanie niszczy powłokę agenta, więc klasa znika; instancja toolbar nie dotyka klasy.
- Regresje FE-121/131…134 (punkt 2) — brak: w `agent-desktop.component.html` doszły tylko `<cc-acw-banner placement="toolbar" />` w nagłówku (poza `tablist`) i `title`/warunek na identyfikatorze w nagłówku kontaktu; `aria-owns`, × poza `role=tab` i handler skrótów FE-114 nietknięte. Cel × w pasku kart 24×24 (WCAG 2.5.8), przyciski toolbar `min-height: 1.75rem`, chip `1.5rem`.
- App-shell: zamykanie szuflady dzieje się tylko przy przekroczeniu `xl` (`wasDesktop`), więc ręcznie otwarta szuflada na wąskim oknie nie jest ruszana; `host: '(window:resize)'` (Angular sprząta listener), dostęp przez `document.defaultView?.innerWidth ?? 0` (SSR-safe). Sidenav używa `typeof window` i `try/catch` na `localStorage`; zapis przeniesiony z efektu do `toggleCollapsed()` — poprawna decyzja, test „nie zamraża wartości pochodnej".
- Rola agenta jest znana synchronicznie (`currentRole` = computed z tokenu), więc „rola wyliczana przed znaną rolą" nie wystąpi przy normalnym wejściu; supervisor/admin zachowują domyślnie rozwinięty sidenav (test R2).
- Skala w jednym partialu z walidacją nazwy (`@error`), mixiny `down/below/up/compact-height`; w całej powierzchni agenta, powłoce, sidenavie, navbarze i `ai-summary-panel` nie ma literału `px` w `@media` (zweryfikowane grepem); kolumny przez `clamp()` w tokenach, `min-width` w e-mailu; tablet-width navbar bez przepełnienia (`flex-shrink`/`min-width: 0` na chipie użytkownika).

### Testy — jakość
- Dobre: parytet placementów ACW, sprzątanie klasy, przejście przez `xl` w `app-shell`, tabela ról/szerokości w sidenavie.
- Brakuje: (a) testu starego klucza `cc_sidenav_collapsed='false'` (MAJOR-2), (b) testu spójności TS↔SCSS (MEDIUM-4), (c) żadnego automatycznego pomiaru układu (1366×768 pl/de, 1024, 1025–1100, zoom 200%), (d) kolizji banera z toggle sidenava (MAJOR-1).

### Summary
**Ocena: 3.5/5** — czysty, dobrze przetestowany podział ACW (jedno ogłoszenie, sprzątanie klasy, brak regresji FE-121/122) i spójna skala breakpointów, ale pływający baner nadal zasłania stopkę sidenava, domyślny rail agenta nie zadziała dla przeglądarek ze starym zapisem w `localStorage`, a kryteria pomiarowe (chrome ≤ 120/591, brak scrolla 1025–1100 i w `de`) nie mają żadnego dowodu.

### Priorytety
**Wymagane (przed commitem):**
1. MAJOR-1: baner nie może zasłaniać stopki sidenava (offset o szerokość sidenava albo render w kolumnie treści).
2. MAJOR-2: nowy klucz `localStorage` dla zapisu tylko z `toggleCollapsed()` + test starego klucza.
3. MEDIUM-3: pomiar/weryfikacja navbara 1025–1100 px (pl, de; AGENT i SUPERVISOR) i decyzja, czy zmiana ma dotyczyć innych ról.
4. Dowód pomiarowy AC 1 i 2 (screenshot/E2E 1366×768 pl+de, 1024) wpisany do FE-123.

**Zalecane:** MEDIUM-4 (test TS↔SCSS), MEDIUM-5 (3 wspólne komponenty albo zawężenie AC), usunięcie/użycie `--desktop-main-min`, inicjalizacja `sidenavCollapsed` bez skoku, dopasowanie segmentowe `onDesktop`, opis kiosku 768 px, zoom 200%.

**Drobne:** LOW-6 (mixed-decls w `&__avatar`), zdublowany komentarz „Low viewport:" w `agent-desktop.component.scss` (nagłówek kontaktu), import `AcwBannerComponent` stoi nad importami `@angular/core` w `agent-desktop.component.ts`, `title`+`aria-label` na chipie, `bp.below` zamiast ręcznego `- 1px` w `sidenav.component.scss`.

### Status ustaleń FE-123 (aktualizacja 2026-10-29, runda 3 — po poprawkach)

Ocena z rundy 1 pozostaje 3,5/5; poniżej stan ustaleń po rundach 2 i 3 (`/verify`: FE lint 0 błędów, format OK, 64 pliki / 745 testów; BE `mvn verify` 2839 testów, 0 błędów, 1 pominięty).

- **MAJOR-1 (baner zasłaniał stopkę sidenava): NAPRAWIONE** — pływający `cc-acw-banner` jest odsunięty o `--shell-sidenav-w`.
- **MAJOR-2 (stary klucz `localStorage` zamrażał domyślny rail): NAPRAWIONE** — nowy klucz `cc_sidenav_collapsed_v2` zapisywany tylko w `toggleCollapsed()`; stary klucz `cc_sidenav_collapsed` ignorowany i usuwany; test starego klucza dodany.
- **MEDIUM-3 (navbar 1025–1100 px, zmiana wspólna dla ról): ZWERYFIKOWANE POMIAREM** w przeglądarce — brak przepełnienia.
- **MEDIUM-4 (TS↔SCSS bez strażnika): NAPRAWIONE** — `breakpoints.ts` + test parytetu z `_breakpoints.scss`.
- **MEDIUM-5 (literały `px` w `@media` poza agentem): CZĘŚCIOWO** — 3 wspólne komponenty (`contact-detail-modal`, `confirm-dialog`, `disposition-list-editor`) przeniesione na skalę; pozostałe 27 plików (admin/auth/supervisor/tenants) → nowy ticket FE-137.
- **LOW-6 i pozostałe zalecenia/drobne (pomiar chrome, `--desktop-main-min`, inicjalizacja `sidenavCollapsed`, dopasowanie segmentowe `onDesktop`, `title`+`aria-label`, kolejność importów): NAPRAWIONE** w rundzie 3.
- **Dowód pomiarowy (AC 1 i 2): DOSTARCZONY** — 1366×768 (viewport 1334×549): chrome 156 → 120 px, treść kontaktu 558 → 782 px, wątek e-maila 229 → 303 px; brak poziomego scrolla przy 1024/1027/1100/1366/1888 px w pl/de/en; 1920×1080 bez zmian.
- **Otwarte (poza zakresem):** chip/baner ACW niezweryfikowany na żywo (Twilio), widoki supervisor/admin niezweryfikowane (MFA); formularz odpowiedzi e-mail przy 1024 px wymaga przewijania.

---

## Review: FE-124 (budżet `agent-desktop.scss`, fallbacki, ruch, modale) — 2026-10-29

Zakres: niezacommitowane zmiany w `agent-desktop.component.{html,ts,scss}`, nowy `components/queue-panel/*`, `styles.scss`, `add-break-modal`, `manual-callback-modal`, `adhoc-email-modal`, `email-thread-message`, `login.component.scss`. Pominięto `.env.local-demo`, `.claude/settings.local.json`, dump.

### 🐛 Bugs / Critical Issues

- **MAJOR-1 `email-thread-message.component.scss:190` — `var(--shadow-xs)` bez fallbacku, a token nie istnieje.** `--shadow-xs` nie jest zdefiniowany w `styles.scss` (grep: 0 trafień), więc po zdjęciu fallbacku `box-shadow` na hoverze chipa załącznika jest nieprawidłowy w czasie obliczania i spada do `none`. Przed zmianą było `var(--shadow-xs, var(--shadow-sm))`. To dokładnie ryzyko R2 z FE-124, które wymagało sprawdzenia tokenów w obu motywach. **Fix:** zdefiniować `--shadow-xs` w `:root` i w obu motywach albo użyć `--shadow-sm`. Wg DESIGN.md (Flat-at-Rest) najlepiej usunąć cień i zmienić tło/obramowanie na hoverze. `email-contact.component.scss:307` nadal ma `var(--shadow-xs, var(--shadow-sm))`, więc ten sam token jest w dwóch miejscach traktowany niespójnie.

### ⚠️ Security Concerns
_None identified._

### 🏗️ Architecture / Pattern Violations

- **MEDIUM-2 `agent-desktop.component.scss:787-801` — blok `prefers-reduced-motion` nie robi tego, co deklaruje komentarz.**
  - Globalny `styles.scss:316` ma `*{animation-duration:.01ms!important; animation-iteration-count:1!important}`. Lokalny `.status-btn__spinner{animation-duration:2.4s}` nie ma `!important`, więc przegrywa. Spinner (stan zajętości) znika w ~0,01 ms i jest jednorazowy, wbrew komentarzowi „keeps turning slowly".
  - Przed zmianą lokalny `*` miał to samo ograniczenie, ale nie obiecywał wolnego obrotu.
  - **Fix:** `animation-duration: 2.4s !important; animation-iteration-count: infinite !important;` albo zamiast obrotu statyczny wskaźnik, np. opacity (`animation: none` plus tekst/ikona). Warto też zdecydować, czy blok lokalny w ogóle jest potrzebny: samo `animation: none` dla kropek (`opacity:1`), `.ws-banner__dots span`, `.header-info__ws-indicator--connected` i `.no-contacts` globalny blok i tak ogranicza do jednej klatki, więc lokalny blok jest w większości redundantny, tak samo jak 6 innych lokalnych bloków w komponentach agenta.
  - Pokrycie (pkt 4): inne animacje na powierzchni agenta (`panel-in`, `dialog-enter`, `msg-enter`, `pulse-dot`, `slide-up`, `blink`) obejmuje blok globalny, więc nic się nie wymyka. Wyjątkiem są ewentualne animacje JS/WAAPI (nie znaleziono).
- **MEDIUM-3 Kontrast tekstu na płaskim nagłówku `--accent` (pkt 5): `adhoc-email-modal.component.scss` `__subtitle` i `manual-callback-modal.component.scss` `__subtitle` (`color-mix(in oklch, var(--accent-fg) 75%, transparent)`).**
  - Policzone dla tokenów z `styles.scss`: motyw jasny, biel 75% na `accent` oklch(55% .13 255) daje ok. **3,5:1**, co jest poniżej 4,5:1 dla tekstu 13 px (WCAG 1.4.3). Przed zmianą tło było ciemniejszym gradientem (tekst przechodził). Motyw ciemny: ok. 4,9:1 (OK).
  - Przycisk zamknięcia: ikona 85% to ok. 4,0:1 (≥ 3:1 dla komponentu UI, OK), jego tło 12–22% to tylko subtelna zmiana (1,25–1,5:1), więc granicę przycisku wyznacza ikona.
  - **Fix:** `__subtitle` w pełnym `var(--accent-fg)` (4,87:1 jasny, 7,35:1 ciemny) albo 90%+ i sprawdzone ≥ 4,5:1; `opacity` zamiast mieszania nie pomoże. Ikona zamknięcia: 100% w stanie spoczynku.
- **MEDIUM-4 Wyjście poza zakres / DESIGN.md.**
  - **Hover `btn-save`** (`manual-callback-modal:437-442`, `adhoc-email-modal:~512`) zachował dwuwarstwowy kolorowy cień akcentu i `translateY(-1px)`; spoczynek jest płaski, ale hover to „glow" i ruch dekoracyjny (Flat-at-Rest, „Do: hover = zmiana tła lub krycia"). Zrobić `background: color-mix(in oklch, var(--accent) 88%, black)` (albo istniejący token hover) bez cienia i `transform`.
  - **Pole fokusu:** zdjęcie `box-shadow: 0 0 0 3px accent-soft` i `outline: none` na rzecz `outline: 2px solid accent; outline-offset:0` w `add-break`, `manual-callback`, `adhoc-email` (6 miejsc) to zmiana wizualna spoza listy (ticket prosił o `:focus-visible` tylko w adhoc-email). Zgodne z DESIGN.md (globalny obrys 2 px), ale `outline-offset:0` plus `border-color: accent` daje pogrubioną obwódkę 3,5 px bez odstępu. Zalecane `outline-offset: 2px` i jedna wspólna reguła (mixin/globalna) zamiast 6 kopii.
  - `.editor-content` w adhoc-email nadal `outline:none` z pierścieniem `box-shadow` na `:focus-within` rodzica — działa, ale to inny wzorzec niż pola obok. Ujednolicić.
  - **Spoza listy w zakresie diffu:** `email-thread-message` `rgba(0,0,0,.06)` → `color-mix(var(--text-1) 6%)` zmienia kolor linii w ciemnym motywie (z czarnej na jasną); to poprawka, ale nieopisana. Nagłówek `add-break-modal` już był płaski (bez zmian).
  - `queue-item` przeniesiony 1:1 z `box-shadow: var(--shadow-sm)` w spoczynku i `--shadow-md` na hoverze: to dryf, który DESIGN.md (l. 261, 267) każe usuwać „przy następnej pracy nad ekranem". Skoro komponent powstaje teraz, zostawić samo `border-1`, hover zmienia tylko tło. `cursor: default` plus hover wygląda na klikalne, a element nie ma akcji.

### 🔧 Improvements & Suggestions

- **MINOR-5 `queue-panel.component.html:39` — `aria-label` na `<div>` bez roli.** `aria-label` na elemencie bez roli (`div`) jest ignorowany lub niespójnie czytany (tak samo był w oryginale, nie regresja). Lepiej `<time>`/`<span>` z `<span class="sr-only">`: „Czas oczekiwania 1:05" i `aria-hidden` na wizualnym czasie, albo `role="timer"`. Ticker co sekundę przy `aria-live` byłby uciążliwy, więc nie dodawać live region (dobrze, że go nie ma). `getWaitingTime()` jest wołany 2× na pozycję na każdy tick; wynik zapisać w `computed` albo `@let wait = getWaitingTime(item.waitingSince);`.
- **MINOR-6 `queue-panel.component.html:7` — `aria-label` badge to `items().length + ' ' + 'oczekujących'` bez separacji języków.** Przy `de`/`uk` zdanie jest poprawne (liczba plus słowo), ale konkatenacja zamiast klucza z parametrem (`queueWaitingCount` z `{{count}}`) blokuje odmianę (pl: 1 oczekujący / 2 oczekujących). Jednak identycznie było przed wydzieleniem, więc to ulepszenie, nie regresja. `<ul role="list">` jest redundantne (nie ma `list-style: none` w Safari, dlatego rola celowa, zostawić).
- **MINOR-7 `queue-panel.component.ts:28` — ticker `now` odpala się nawet przy pustej kolejce.** Interwał co 1 s przez cały czas życia pulpitu (tak samo jak przed zmianą). Dla pustej kolejki to zbędne budzenie OnPush (zmiana sygnału tylko odświeża szablon, jeśli jest odczytywany; przy pustej liście `now()` nie jest czytany w szablonie, więc koszt to tylko timer). Opcjonalnie `computed`/`effect` tworzący interwał tylko, gdy `items().length > 0`.
- **MINOR-8 `queue-panel.component.scss:60` `font-size: 0.8125rem` ×2** (`__empty-text`, `__name`) to literał spoza skali tokenów; przy tickecie o ujednoliceniu rozmiarów warto użyć `--fs-xs`/`--fs-sm` (sprawdzić wartości z FE-118). Ten sam przypadek: `'Geist Mono', monospace` na sztywno zamiast tokenu fontu mono, jeśli istnieje.
- **MINOR-9 `queue-panel.component.spec.ts` — tylko 2 przypadki.** Dodać: (a) sprzątanie interwału po `fixture.destroy()` (`vi.getTimerCount()` = 0), (b) ikona dla każdego typu (`TYPE_ICONS` jest eksportowany logicznie przez `[attr.d]`), (c) odczyt `aria-label` badge i czasu, (d) dodanie/usunięcie elementu (`track item.id`), (e) test w kontekście `AgentDesktopComponent` (`<app-queue-panel>` w `<aside>`), bo 5 specyfikacji pulpitu dostarcza `QueueStateService` mockiem, który teraz jest wstrzykiwany przez komponent potomny (działa, ale te mocki stały się szumem, tam gdzie nie ma asercji na kolejkę).
- **Budżet (pkt 6):** margines ~1–50 B jest zbyt cienki: każda kolejna zmiana, np. dopisanie reguły fokusu w FE-125, przekroczy 12 kB. Źródło ma 17,4 kB (po minifikacji poniżej 12 kB). Największe, w pełni wydzielalne bloki: **„Contact Tabs" (l. 341–562, ~220 linii)** i **„Contact Panel" (l. 563–723, ~160 linii)**. Rekomendacja w kolejności opłacalności: (1) `app-contact-header` (nagłówek kontaktu z badge typu `contact-panel__*` i wspólnym blokiem „Type badge tones", l. 817+) z `input()` na `ContactTab`, czysto prezentacyjny, bez ryzyka dla `role=tab`/`aria-owns` z FE-121; (2) badge typu jako mały `cc-contact-type-badge`, używany także przez pasek kart (usuwa 4 powtórzone reguły tonu); (3) pasek kart (`tablist`) zostawić w rodzicu, bo ma delikatne zależności ARIA i skróty FE-114. Uzyskasz 3–5 kB zapasu bez dotykania zachowania.
- **Dowód budżetu:** w tickecie AC 1 wymaga „bez ostrzeżenia w `npm run build`"; dołączyć do FE-124 liczby przed/po z `npm run build` (18,11 → <12 kB) i fakt, że `maximumWarning` w `angular.json` to 12 kB dla `anyComponentStyle`, a nie dla pliku źródłowego.

### Weryfikacja punktów zadania

1. **Wydzielenie `queue-panel`: zachowanie bez zmian.** Ticker `interval(1000)` z `takeUntilDestroyed(inject(DestroyRef))` w konstruktorze (kontekst wstrzykiwania poprawny), zero wycieku; `OnPush` poprawny, bo `now` i `items` to sygnały odczytywane w szablonie; dane wejściowe nadal z `QueueStateService.queueItems` (singleton). Brak kliknięć i akcji w oryginale, więc nic nie zgubiono. `TYPE_ICONS: Record<QueueItem['type'], string>` jest bardziej bezpieczny niż `switch` (kompilator wymusi nowy typ). Jedyna zmiana: ticker żyje teraz razem z komponentem (taki sam cykl życia). `display: contents` na hoście zachowuje flex sidebara (`.queue-panel{flex:1}` działa jako dziecko `aside`); `app-queue-panel` jest czysto prezentacyjnym wrapperem, a brak `display: contents` problemów a11y dla elementu bez roli. Wszystkie 4 języki mają klucze `queueTitle/queueWaiting/queueWaitTime/queueEmpty`.
2. **Usunięte reguły CSS są martwe:** `limit-banner`, `desktop__tab-nav*`, `phone-placeholder`, `email-placeholder` — brak trafień w `*.html`/`*.ts`/`*.scss` całego `src`. Dynamiczne `[class.contact-tab-item--phone|chat|email|social]` (html:197-200) nadal obsługuje przeniesiony blok „Type badge tones" (l. 818-836). `chat-placeholder` (używany w html:343 i w `copy.spec`) został zachowany. Brak regresji.
3. **Tokeny po zdjęciu fallbacków:** `bg-input`, `accent-fg`, `accent-soft`, `bg-subtle`, `bg-elevated`, `border-control(-hover)`, `border-2`, `shadow-sm/md`, `violet`, `violet-text`, `danger(-text)`, `text-muted`, `text-3`, `radius-md` są zdefiniowane w obu motywach (2 trafienia); `fs-2xs`, `ease-standard`, `duration-fast/normal` w `:root` (1, niezależne od motywu); `ease-out-expo/quart` dodane. **Jedyny brak: `--shadow-xs` (MAJOR-1).** Grep fallbacków, hex, `rgba`, `$ease-spring` w 7 dotkniętych plikach = 0.
4. **`prefers-reduced-motion`:** patrz MEDIUM-2; poza spinnerem pokryte.
5. **Kontrast:** patrz MEDIUM-3.
6. **Budżet:** patrz Improvements.
7. **Wyjście poza zakres:** patrz MEDIUM-4. `--ease-spring` zostaje aliasem; `--ease-out-expo` ma tę samą krzywą (bez odbicia, zgodnie z tickiem). Baner błędu w `login` ma pełną ramkę 40% `danger`: spełnia „bez side-tab", kontrast obramowania nie jest wymogiem (tekst `danger` na tle `danger-soft` nietknięty).

### ✅ Positive Observations

- Czyste wydzielenie: zero zmian zachowania, `Record<…>` zamiast `switch`, `DestroyRef`/`takeUntilDestroyed` w kontekście wstrzykiwania, sensowny spec z fake timers (ticker `1:05 → 1:10`).
- Z rodzica usunięto wszystkie powiązane symbole (`interval`, `QueueItem`, `QueueStateService`, `trackByQueueId`), brak martwych importów.
- Usunięcie `::-webkit-scrollbar` i fallbacków hex bez tokenów brakujących (poza `--shadow-xs`); `color-mix(in oklch)` zamiast `rgba` działa poprawnie w obu motywach (tekst `--text-1` jako baza cienkich linii).
- Zdjęcie `transition: width` panelu klienta i `will-change` — zgodne z celem ruchu „stan, nie dekoracja".
- Bounce usunięty ze wszystkich modali, aliasy tokenów dla kompatybilności wstecznej.

### Summary
**Ocena: 3.5/5** — solidny, bezpieczny refaktor wydzielenia kolejki i czyszczenie dead CSS, ale po zdjęciu fallbacków zostaje niezdefiniowany `--shadow-xs`, komentarz o spinnerze w `reduced-motion` jest nieprawdziwy, subtytuł na płaskim nagłówku jasnego motywu ma ok. 3,5:1, a margines budżetu to praktycznie zero.

### Priorytety
**Wymagane (przed commitem):** MAJOR-1 (`--shadow-xs`), MEDIUM-3 (kontrast subtitle ≥ 4,5:1), MEDIUM-2 (spinner w reduced-motion albo poprawić komentarz), MEDIUM-4 (hover `btn-save` bez cienia/`translateY`).
**Zalecane:** wydzielić `app-contact-header` (+ badge typu) dla 3–5 kB zapasu budżetu, uzupełnić spec kolejki (sprzątanie interwału, aria), usunąć cienie z `queue-item`, wspólna reguła fokusu zamiast 6 kopii.
**Drobne:** `aria-label` na `div`, podwójne wywołanie `getWaitingTime`, literały `0.8125rem` i fontu mono, interwał przy pustej kolejce.

### Status ustaleń FE-124 (aktualizacja 2026-10-30, runda 3 — po poprawkach)

Ocena senior-code-reviewer: 3,5/5. Wszystkie ustalenia naprawione w rundzie 2:
- **MAJOR-1** `--shadow-xs` (niezdefiniowany token w `email-thread-message`) — naprawione (usunięty, hover chipa tłem).
- **MEDIUM-2** spinner przy `prefers-reduced-motion` — naprawione (statyczny; komentarz zgodny ze stanem faktycznym).
- **MEDIUM-3** kontrast subtytułu nagłówka modali — naprawione (pełny `--accent-fg`: 4,87:1 jasny / 7,35:1 ciemny).
- **MEDIUM-4** hover/fokus/cienie (`btn-save`, `queue-item`) — naprawione (płaski przycisk, hover tylko tłem, bez cieni i `translateY`; fokus przez mixin `field-focus`).
- **Wszystkie MINOR** — naprawione (m.in. `aria-label`/`sr-only` w `queue-panel`, odmiana licznika przez `Intl.PluralRules`, ticker tylko przy niepustej kolejce, tokeny `--fs-sm`/`--font-mono`, rozszerzony spec kolejki).
- **Budżet:** `agent-desktop.component.scss` 18,11 → 9,00 kB po kompilacji (zapas 3,0 kB) dzięki wydzieleniu `app-contact-header`, `app-ws-banner`, `app-no-contacts`; metoda: ostrzeżenie `anyComponentStyle` w `ng build`.

**Runda 3 (znalezisko z przeglądarki):** podwójny pierścień fokusu w edytorach e-maila (`adhoc-email-modal`, `email-reply` w `email-contact`) — naprawione: jeden pierścień na wrapperze przez `:has(...:focus-visible)` (`editor-content` outline none, wrapper outline 2 px, offset 2 px; zweryfikowane w przeglądarce dla `adhoc`).

**Otwarte (poza zakresem FE-124):** `--color-primary` bez definicji w `customer-panel.component.scss:417` i nieużywany klucz `agent.desktop.queueWaiting` → FE-125; komponenty ponad 12 kB i bundle initial 567 kB > 500 kB → FE-138; niezweryfikowane na żywo: lista kolejki z pozycjami (WS, 9 testów), `prefers-reduced-motion`, edytor odpowiedzi na pulpicie po rundzie 3.

---

## Review: FE-125 (część A) — 2026-10-30

Zakres: `customer-panel.component.scss`, `agent-customers-tab.component.scss` (`--color-primary` → `--accent`), usunięty klucz `agent.desktop.queueWaiting` (pl/en/de/uk), `queue-panel` (`normalizeContactName` + test), `agent-desktop.component.ts` (`announce()` czyści region live po `ANNOUNCEMENT_CLEAR_MS` = 2000) + test. Uruchomiono `agent-desktop.tabs.spec.ts` i `queue-panel*.spec.ts`: 60/60 zielone.

### 🐛 Bugs / Critical Issues
_None identified._ (brak blockerów)

### MAJOR
_None identified._

### MEDIUM
- **MEDIUM-1 `agent-desktop.component.ts:405-410`** Callback `afterNextRender` ustawia `announcementTimer = setTimeout(...)` bez wcześniejszego `clearTimeout`. `clearTimeout` jest tylko na początku `announce()`. Dwa wywołania `announce()` przed renderem (np. `newContactEffect` + `requestClose`) kolejkują dwa callbacki: oba odpalają się w tym samym renderze, drugi nadpisuje uchwyt pierwszego timera, który nigdy nie zostanie anulowany. Skutki: (a) osierocony timer po `ngOnDestroy` (destroy czyści tylko ostatni uchwyt, a callback timera dotyka sygnału zniszczonego komponentu), (b) wcześniejsze wyczyszczenie tekstu drugiego ogłoszenia. Naprawa: `clearTimeout(this.announcementTimer)` tuż przed `setTimeout` w callbacku (i `if (this.destroyed) return;` na jego początku).
- **MEDIUM-2 `agent-desktop.component.ts:~59` (`ANNOUNCEMENT_CLEAR_MS = 2000`)** 2 s to dolna granica. Ogłoszenie w `role="status"` jest kolejkowane przez czytnik w momencie wstawienia tekstu, więc usunięcie węzła zwykle nie ucina mowy (NVDA/JAWS z Chrome/Firefox), ale zachowanie nie jest zagwarantowane. Przy wolnej mowie i długim tekście (`newContactAnnouncement` łączy kilka kontaktów przez join) oraz VoiceOver/Safari ryzyko ucięcia lub zgubienia jest realne, a testu na czytnikach nie ma. Zalecenie: 5000–10000 ms (koszt zerowy, `sr-only`), albo opis w komentarzu, że 2 s nie weryfikowano na czytnikach. Wartość to jedna stała, więc zmiana jest trywialna.

### MINOR
- **MINOR-1 `agent-desktop.tabs.spec.ts:725-754`** Test sprawdza tylko ścieżkę główną. Brak przypadków: (a) drugi `announce()` w oknie 2 s resetuje timer (nowy tekst nie jest czyszczony po resztce starego okna), (b) `destroy` anuluje timer (`vi.getTimerCount() === 0` po `fixture.destroy()`), (c) dwa ogłoszenia w jednym ticku (MEDIUM-1). Asercje `toContain` zamiast `toBe` osłabiają test. Fake timers są poprawnie izolowane (`useFakeTimers` po pierwszym `settle()`, `afterEach(useRealTimers)`); ryzyko: padnięta asercja przed `afterEach` nadal przywraca zegar, więc inne testy w pliku nie ucierpią.
- **MINOR-2 `queue-panel.component.spec.ts`** Nowy test pokrywa `Name <mail>` i `<mail>`, ale nie `''`, białych znaków ani `null`. `normalizeContactName` obsługuje `null`/`undefined`/`''` (zwraca `''`), więc kod jest bezpieczny, ale brakuje testu regresji. Przy pustej nazwie w kolejce zostaje pusty `<span class="queue-item__name">`; rozważ fallback do `customerIdentifier` (`displayName(item.customerName) || item.customerIdentifier`) i wtedy ukrycie zduplikowanej linii identyfikatora.
- **MINOR-3 `queue-panel.component.ts:72`** `protected readonly displayName = normalizeContactName;` stoi po metodach, między nimi a `trackByQueueId`; przenieść do bloku pól. Wywołanie funkcji w szablonie jest tanie (regex, OnPush), pipe nie jest potrzebny.
- **MINOR-4** Pozostałe `--color-primary` (z fallbackami hex) są w `supervisor/.../disposition-sets-page.component.scss` (l. 103-423). Token nadal niezdefiniowany, więc w całości działa na fallbackach. Poza zakresem FE-125A; do osobnego zadania. Zmiana w `customer-panel` (l. 417) i `agent-customers-tab` (l. 569) jest poprawna: `--accent` jest zdefiniowany w obu motywach (`styles.scss:137`, `:197`), a usunięcie fallbacku `#1976d2` ujednolica kolor linku.

### ⚠️ Security Concerns
_None identified._

### 🏗️ Architecture / Pattern Violations
_None identified._

### Weryfikacja punktów zadania
1. **Timer / sprzątanie / SSR:** `destroyRef.onDestroy` czyści timer (dobrze), `clearTimeout(undefined)` jest bezpieczne. SSR bezpieczne: `afterNextRender` nie wykonuje się na serwerze, więc `setTimeout` nigdy tam nie powstaje. Wyjątek: osierocony timer z MEDIUM-1.
2. **Czyszczenie po 2 s:** patrz MEDIUM-2.
3. **clear → set w jednym ticku przy OnPush:** działa. `set('')` jest zapisem sygnału czytanym w szablonie (zaznacza widok), a `afterNextRender` odpala się po renderze z pustym tekstem, więc DOM faktycznie zmienia się dwa razy (potwierdza test z ponownym ogłoszeniem tego samego tekstu). Czyszczący timer też zapisuje sygnał, więc OnPush odświeży widok bez ręcznego `markForCheck`.
4. **`normalizeContactName`:** null/undefined/`''` → `''`; regex bezpieczny (leniwy `.*?`, kotwica `$`, brak katastrofalnego backtrackingu). Patrz MINOR-2 (pusty span).
5. **Klucz i18n:** `agent.desktop.queueWaiting` nie jest używany nigdzie. Jedyne dynamiczne użycie to `` `agent.desktop.queueWaitingCount_${category}` `` (`queue-panel.component.ts:47`), który ma osobny prefiks z `_`, więc usunięcie jest bezpieczne. `supervisor.dashboard.queueWaiting` to inny, żywy klucz (nietknięty).
6. **JSON:** wszystkie 4 pliki parsują się poprawnie, w każdym usunięto dokładnie jedną linię (brak wiszącego przecinka, bo kolejny klucz istnieje).
7. **Testy:** zielone (60/60), fake timers nie wpływają na pozostałe testy pliku (patrz MINOR-1).

### ✅ Positive Observations
- Sprzątanie timera przez `DestroyRef` zamiast `ngOnDestroy`, spójnie z resztą komponentu; stała nazwana i opisana komentarzem.
- Mechanizm clear → set zachowany i teraz przetestowany dla powtórzonego tekstu.
- Zamiana `--color-primary` na istniejący token `--accent` bez fallbacku hex, zgodnie z kierunkiem FE-124.
- Usunięcie klucza we wszystkich 4 językach naraz, zweryfikowane brakiem konkatenacji.

### Summary
**Ocena: 4/5** — drobna, bezpieczna zmiana bez blockerów; do poprawy: osierocony timer przy dwóch ogłoszeniach w jednym ticku (MEDIUM-1), zbyt krótkie okno 2 s bez weryfikacji na czytnikach (MEDIUM-2) i braki w testach (reset timera, destroy, wartości puste).

### Priorytety
**Zalecane przed commitem:** MEDIUM-1 (`clearTimeout` w callbacku + guard `destroyed`), MEDIUM-2 (zwiększyć stałą lub odnotować brak weryfikacji na czytnikach).
**Drobne:** MINOR-1..3; MINOR-4 do osobnego zadania.

### Status ustaleń FE-125 (aktualizacja 2026-10-30, runda 2 — po poprawkach)

Ocena senior-code-reviewer (część A): 4/5. Ustalenia z rundy 1 naprawione w rundzie 2:
- **MEDIUM-1** osierocony timer regionu live — naprawione (`clearTimeout` w callbacku, brak osieroconych timerów po `destroy`).
- **MEDIUM-2** okno czyszczenia 2 s → 7 s (`ANNOUNCEMENT_CLEAR_MS`) — naprawione; wartość niezweryfikowana na czytnikach ekranu (dług weryfikacji).
- **Wszystkie MINOR (1–3)** — naprawione (rozszerzone testy timera i `normalizeContactName`, fallback do identyfikatora dla pustej nazwy w kolejce, porządek pól).
- **MINOR-4** `--color-primary` (i 7 innych niezdefiniowanych tokenów `--color-*`) w supervisor `disposition-sets-page.component.scss` — poza zakresem FE-125 → **FE-141**.
- **Otwarte (poza zakresem):** gradienty → FE-139; fallbacki hex → FE-140; drobne P3 → FE-142; weryfikacja na żywo: czytnik ekranu (`tablist` z `aria-owns`), prawdziwe połączenie przychodzące/ACW (Twilio), emulacja `prefers-reduced-motion`, znaczniki PHONE/„Szkic”.
- `/verify`: FE lint 0 błędów (10 wcześniejszych ostrzeżeń), format OK, 68 plików / 770 testów; BE `mvn verify` 2839 testów, 0 błędów, 1 pominięty.

---

## Review: FE-129 — 2026-10-30

Zakres: `frontend/src/app/shared/utils/duration-format.util.ts` (+spec), `top-navbar.component.{ts,html,spec.ts}`, `frontend/public/i18n/{pl,en,de,uk}.json`. Zmiany niezacommitowane.

### 🐛 Bugs / Critical Issues
- **MAJOR-1 `top-navbar.component.ts:79-100`** Reaktywność `workTimeSr`. `TranslocoService.setActiveLang()` robi `lang.next()` synchronicznie, a plik tłumaczeń ładuje się osobno i asynchronicznie (HTTP loader, ładowanie wyzwalają pipe/dyrektywa). `langChanges$` emituje więc PRZED załadowaniem tłumaczeń. `computed` przelicza się wtedy z `translate()`, które dla niezaładowanego języka zwraca klucz (`agent.desktop.kpiWorkTimeHours_other` itd.). Wynik jest zapamiętany, a nic go potem nie unieważnia, bo `translationLoadSuccess` nie jest zależnością. Czytnik dostaje klucz, dopóki nie zmieni się `workTimeSeconds` (KPI odświeża się rzadko). Dotyczy pierwszego przełączenia na język jeszcze niezaładowany (pl domyślny, więc en/de/uk). Test tego nie wykrywa, bo `TranslocoTestingModule` ładuje wszystkie języki synchronicznie. Ten sam błąd jest w `queue-panel.component.ts:39-48` (`badgeLabel`), z którego skopiowano wzorzec, więc to nie jest „sprawdzony wzorzec”, tylko ta sama usterka w drugim miejscu. Rekomendacja, w kolejności preferencji:
  1. `translateSignal` (transloco 8.3, `jsverse-transloco.mjs:1594`) dla trzech kluczy. Reaguje na język i na załadowanie. Klucze zależne od `rules.select(n)` przekazać jako signal, bo `translateSignal` przyjmuje dynamiczny klucz/parametry.
  2. Proste: pipe `transloco` w szablonie dla `sr-only`, np. `{{ key() | transloco: {count: n()} }}` dla godzin i minut (pipe sam czeka na load). Wymaga 2 spanów lub złożenia w szablonie, a klucz `kpiWorkTimeSr` przestaje być potrzebny.
  3. Najmniej elegancko: dodać do `computed` zależność od `toSignal(events$.pipe(filter(e => e.type === 'translationLoadSuccess')))`. Działa, ale to obejście.
  Rekomenduję 1 i naprawę `queue-panel` tym samym sposobem (osobny wpis, nie w tym tickecie). Dodać test z loaderem asynchronicznym (`TranslocoTestingModule` z `preloadLangs: false` lub własny loader z opóźnieniem): przełączenie języka, `await` na load, asercja tekstu.

### ⚠️ Security Concerns
_None identified._

### 🏗️ Architecture / Pattern Violations
- **MEDIUM-1 `public/i18n/en.json`, `de.json` (po 8 martwych kluczy)** Formy `_few` i `_many` dla en/de nie są nigdy wybierane (`Intl.PluralRules('en'|'de')` zwraca tylko `one`/`other`; sprawdzone uruchomieniem). To 4 martwe klucze godzin i minut w każdym z dwóch plików plus pary pl/uk `_other` (niedostępne dla liczb całkowitych). Utrzymują spójność kształtu, ale fałszywie sugerują, że są używane, i ktoś „poprawi” np. `de_few`. Rekomendacja: zostawić tylko kategorie realnie osiągalne w danym języku (en/de: `one`, `other`; pl/uk: `one`, `few`, `many`) albo zamienić na ICU MessageFormat, jeśli projekt go ma. Jeśli zostają, dodać do spec lub komentarza w kodzie informację, że to celowe. Minimum: test spójności, że każdy osiągalny klucz istnieje we wszystkich 4 plikach.

### 🔧 Improvements & Suggestions
- **MEDIUM-2 `top-navbar.component.ts:86-99`, `kpiWorkTimeSr`** Poniżej godziny czytnik powie „0 godzin 45 minut”. Poprawne gramatycznie, ale to szum: wizualnie `0:45`, a głos dodaje zerowy składnik. Rekomendacja: pominąć zerowe godziny (`45 minut`), a dla 0/0 podać „0 minut”. Wtedy `kpiWorkTimeSr` jako szablon łączący dwa człony zaczyna mieć sens (spójny separator), ale trzeba wtedy obsłużyć brak jednego z członów. Zerowe minuty przy pełnej godzinie (`4 godziny 0 minut`) również można pominąć („4 godziny”); do decyzji produktowej, odnotować w tickecie. Formy mnogie zweryfikowane: pl/uk 0 many, 1 one, 2 few, 5 many, 12 many, 22 few, 45 many; uk 21 one (nie `many` jak w pl), 101 one; en/de 1 one, reszta other. Wszystkie wpisane teksty są zgodne.
- **MEDIUM-3 `top-navbar.component.html:159-164`** Stan „—” (brak KPI): `aria-hidden` jest poprawnie zdejmowany i brak `sr-only`, ale czytnik odczyta gołe „—” (zwykle „pauza”/nic). Rekomendacja: w stanie bez danych dodać `sr-only` z krótkim tekstem „brak danych” (nowy klucz w 4 językach) albo użyć `aria-label` na kafelku. Dotyczy też trzech pozostałych kafelków, więc można zrobić w osobnym tickecie. Konstrukcja `aria-hidden` na wartości plus sibling `.sr-only` jest poprawna i nie dubluje odczytu. Klasa globalna `.sr-only` (`styles.scss:319`) wystarcza, a `position: absolute` nie ucieka poza kafelek (1×1 px).
- **MINOR-1 `top-navbar.component.html:134`, AHT** AHT zostaje w `m:ss` bez jednostki dla czytnika (odczyt „3:05” jest dwuznaczny, a sąsiedni kafelek to teraz `h:mm`). Dwa różne formaty bez jednostek obok siebie to ryzyko pomyłki także wzrokowej (`3:45` jako czas pracy vs `3:45` jako AHT). Poza zakresem ticketu (decyzja właściciela), ale warto dopisać do FE-139..142 lub nowego ticketu z `sr-only` dla AHT.
- **MINOR-2 `duration-format.util.ts:2,15`** Powielona logika „bezpiecznej sekundy” (`Number.isFinite && > 0 ? floor : 0`) w dwóch funkcjach. Wydzielić `toSafeSeconds()`. `Infinity` jest cicho zamieniane na 0 (akceptowalne, ale warto komentarz).
- **MINOR-3 `top-navbar.component.ts:76-77`** `protected formatMinSec = formatMinSec;` to zwykłe pole instancji, nie `readonly`. Dodać `readonly`. Alternatywa: `computed` na sformatowane wartości KPI zamiast wywołań metod w szablonie przy każdym CD (OnPush ogranicza koszt, więc tylko kosmetyka).
- **MINOR-4 `top-navbar.component.ts:83`** Komentarz JSDoc ma ponad 100 znaków (Prettier `printWidth: 100` nie łamie komentarzy, ale `npm run lint` bywa na to wrażliwy). Do skrócenia/złamania.

### Testy (kryterium 4)
- **MEDIUM-4 `top-navbar.component.spec.ts:78-93`** Test SR pokrywa tylko jedną wartość (13556 s = 3 h 45 min), więc w każdym języku sprawdza się tylko jedna kategoria mnogości godzin (few/other) i minut (many/other). Brakuje: 1 godzina i 1 minuta (`one`), 2 (`few`), 0 godzin, 22 minuty (`few` w pl/uk), 12 minut (`many`), 21 minut w uk (`one`). Rekomendacja: `it.each` po macierzy (język, sekundy, tekst).
- **MEDIUM-5 `top-navbar.component.spec.ts:86,92`** Mutacja współdzielonego `KPI` z resetem na końcu testu, bez `try/finally`. Jeśli asercja padnie, wartość 13556 zostaje w kolejnych testach i powoduje kaskadę fałszywych błędów. Użyć `beforeEach`/`afterEach` albo osobnego obiektu KPI per test.
- **MINOR-5** Brak testów: przełączenie języka w trakcie życia komponentu (patrz MAJOR-1), stan `kpi() === null` (brak `sr-only`, brak `aria-hidden`, tekst „—”), brak `aria-hidden` na AHT/pozostałych.
- **MINOR-6 `duration-format.util.spec.ts`** Dobre przypadki brzegowe dla `formatHoursMinutes` (0, 3599, 3600, 13556, ujemne, NaN) i kryterium z ticketu 225:56 → 3:45 jest pokryte. Brakuje: `formatMinSec` dla ujemnych/NaN (kod to obsługuje, test nie), ułamków (`3599.9` → `0:59`), `Infinity`, `splitHoursMinutes` dla 0/ujemnych/NaN oraz 86400+ (doba, np. `24:00`). Pierwsza `it.each` ma komentarz „225:56 as m:ss” bez asercji na to samo wejście w `formatMinSec`, ale ta wartość jest w drugiej tabeli, więc OK.
- Kryterium z ticketu „pas KPI mieści się w `de` przy 1366 px (pomiar w przeglądarce)”: nie sprawdzone w tym review (brak pomiaru w diffie). `h:mm` jest krótsze niż poprzednie `225:56`, więc regresji szerokości nie oczekuję, ale DESIGN.md:209 nadal mówi, że `de/en/uk` wymagają pomiaru, więc kryterium zostaje otwarte do pomiaru.

### Zgodność z DESIGN.md i CLAUDE.md
- Standalone, `computed`/`toSignal`, brak NgModule, prefiks klas zgodny. Brak nowych kolorów/tokenów, brak zmian CSS, brak fallbacków hex. Zgodne.
- i18n w 4 językach z polskimi diakrytykami: poprawne (godzina/godziny/godzin, minuta/minuty/minut). Ukraiński i niemiecki poprawne.
- Komentarze w kodzie po angielsku, zgodnie z CLAUDE.md.

### ✅ Positive Observations
- Floor zamiast zaokrąglenia, jawnie przetestowany (13556 → 3:45, nie 3:46), czyli dokładnie to, co było przyczyną błędu `225:56`.
- Logika formatowania wyniesiona do czystego, testowalnego utila z obsługą ujemnych i NaN, zamiast metody komponentu.
- Formy mnogie przez `Intl.PluralRules`, a nie ręczne `% 10`; pl/uk (one/few/many) poprawne dla wszystkich sprawdzonych wartości.
- Wzorzec `aria-hidden` na wartości wizualnej plus sibling `.sr-only` bez dublowania odczytu, i zdejmowanie `aria-hidden` w stanie „—”.
- Test sprawdza wszystkie 4 języki oraz wartość wizualną i atrybut `aria-hidden` jednocześnie.

### Summary
**Ocena: 3.5/5** — format `h:mm` i formy mnogie są poprawne i dobrze wydzielone, ale `workTimeSr` nie reaguje na załadowanie tłumaczeń (czytnik może dostać klucz po zmianie języka), a testy nie obejmują kategorii mnogości ani asynchronicznego ładowania.

### Priorytety
**Przed commitem:** MAJOR-1 (`translateSignal`/pipe + test z asynchronicznym loaderem), MEDIUM-5 (izolacja `KPI` w testach), MEDIUM-4 (macierz języków i wartości).
**Zalecane:** MEDIUM-2 (pominąć zerowe godziny), MEDIUM-1 (martwe klucze), MEDIUM-3 (stan „—”).
**Osobne zadanie:** ta sama usterka w `queue-panel.component.ts:39-48`; AHT bez jednostki (MINOR-1); pomiar `de` przy 1366 px.

### Runda 2 — 2026-10-30

Weryfikacja: `ng test` dla `top-navbar/*.spec.ts` + `duration-format*.spec.ts`: 4 pliki, 129 testów, wszystkie zielone. Ocena: **4.5/5**. Brak znalezisk blokujących commit.

| Znalezisko | Status | Uwagi |
|---|---|---|
| MAJOR-1 (`translate()` w `computed`) | NAPRAWIONE | Tekst rozwiązuje pipe `transloco`, w `computed` zostają tylko klucz i liczba. Dodany test z loaderem opóźnionym o 40 ms (przełączenie pl→en przed załadowaniem en): brak klucza w DOM, po załadowaniu "3 hours 45 minutes". |
| MEDIUM-1 (martwe klucze plural) | ZAAKCEPTOWANE | Klucze zostają, bo `kpi-layout.spec` typuje en/de/uk kształtem `pl.json`. Uzasadnienie w komentarzu `i18n note` w komponencie i test osiągalnych kategorii (pl/uk: one/few/many, en/de: one/other). |
| MEDIUM-2 (zerowe składniki) | NAPRAWIONE | "45 minut", "1 godzina", 0 s = "0 minut". Zweryfikowane macierzą 0/1/2/5/12/21/22 w 4 językach (uk 21 = one, pl 21 = many). |
| MEDIUM-3 (stan bez KPI) | NAPRAWIONE | Wspólny klucz `kpiNoData` w 4 kafelkach, wartość "—" ma `aria-hidden`, test na wszystkich 4 kafelkach w 4 językach. |
| MEDIUM-4 (macierz testów) | NAPRAWIONE | Pełna macierz plural + testy AHT i kombinacji h+min. |
| MEDIUM-5 (mutowanie `KPI`) | NAPRAWIONE | `kpiWith()` tworzy kopię, `KPI` otypowany `AgentKpiResponse`. |
| MINOR-1 (AHT bez jednostek dla czytnika) | NAPRAWIONE | `kpiAhtSr`, "3 min 5 s", w 4 językach. |
| MINOR-2/3 (`toSafeSeconds`, `readonly`) | NAPRAWIONE | |

Punkty szczególne:
- **Białe znaki w `sr-only` przy `@for`/`$last`:** poprawne. Angular zwija białe znaki w węźle tekstowym do jednej spacji, więc separator zapewnia sam układ szablonu, a `{{ $last ? '' : ' ' }}` jest redundantny, ale nieszkodliwy. Testy (po normalizacji `\s+`) potwierdzają "3 godziny 45 minut" w 4 językach. Brak podwójnych spacji istotnych dla czytnika.
- **Kategoria plural z `langChanges$` vs plik ładowany przez pipe:** niespójność jest możliwa wyłącznie przejściowo. `lang()` zmienia się synchronicznie przy `setActiveLang`, a pipe pokazuje nowy tekst dopiero po załadowaniu pliku. W oknie ładowania klucz wybrany regułami nowego języka może zostać rozwiązany w słowniku starego (np. pl→en dla 5 godzin: klucz `_other`, pl "5 godziny"). Okno trwa kilkadziesiąt ms, klucze istnieją we wszystkich językach, więc czytnik nigdy nie dostaje surowego klucza. Ryzyko niskie.
- **`aria-hidden` Handled i SLA:** poprawne. Wartość dostaje `aria-hidden="true"` tylko bez KPI (zamiast "—" czytnik mówi "brak danych"), z KPI wartość jest odczytywana, a `.sr-only` nie jest renderowany (test to sprawdza). Etykieta i podpis pozostają dostępne. Pasek SLA jest dekoracyjny, pusty `div`, bez wpływu na odczyt.

### Nowe uwagi (nieblokujące)
- **NIT-1 `top-navbar.component.html` (Handled, ok. l. 75-80)**: bez KPI kolejność odczytu to "Obsłużone, brak danych, dziś", bo `.sr-only` stoi między wartością a podpisem `kpiHandledSub`. Czytelniejsze: "Obsłużone, dziś, brak danych" albo pominąć podpis przy braku danych. Kosmetyka.
- **NIT-2 spec, test async-loadera**: sprawdza tylko brak `agent.desktop` w tekście w oknie ładowania. Dodanie asercji, że tekst nie jest pusty, domknęłoby przypadek "pipe zwraca pusty string do czasu załadowania". Opcjonalne.
- **NIT-3 `top-navbar.component.ts`**: `new Intl.PluralRules(this.lang())` tworzone przy każdym przeliczeniu `computed`. Koszt pomijalny (przeliczenie tylko przy zmianie KPI/języka), ewentualnie `computed` na reguły.
- **Wciąż otwarte, poza zakresem FE-129:** ta sama usterka reaktywności w `queue-panel.component.ts:39-48` (`badgeLabel`, `translate()` w `computed`) oraz pomiar `de` przy 1366 px.

**Status (2026-10-30, tura 60):** FE-129 ✅ zamknięty w `TASKS-FRONTEND.md` (oceny bez zmian: runda 1 3,5/5, runda 2 4,5/5). Usterka w `queue-panel` → nowy FE-143 (backlog, razem z NIT-1…NIT-3). Pomiar `de` przy 1366 px wykonany w przeglądarce: pas 381 px, etykiety i 4. kafelek sr-only wg container query z FE-134 (zamierzone, nic się nie ucina); przy 1920 px w `de` 4 kafelki, najgorszy przypadek `12:59` bez ucięcia.

---

## Review: FE-127 + FE-128 — 2026-10-30

Zakres: `admin-user-form.component.{ts,html,spec.ts}`, `queue-form.component.{ts,html,spec.ts}`, `softphone.service.ts` + nowy `softphone.service.spec.ts`, `frontend/public/i18n/{pl,en,de,uk}.json`. Zmiany niezacommitowane. Uruchomiono `ng test` dla 3 specs (18/18 zielone) i `prettier --check` (czysto). Parytet kluczy skryptem po spłaszczonych kluczach: 2780/2780/2780/2780, 0 brakujących, 0 nadmiarowych; wszystkie klucze ponownie użyte (`role.*`, `admin.userForm.errors.*`, `supervisor.userForm.errors.*`, `common.loading_data`) istnieją w 4 językach.

### 🐛 Bugs / Critical Issues
- **MAJOR-1 `queue-form.component.ts:213,219,233,239`** Kryterium akceptacji FE-128 #1 ("brak literałów PL w trzech plikach, grep poza komentarzami i `console.*`") NIE jest spełnione. W `queue-form.component.ts` zostały 4 literały PL bez diakrytyków w powiadomieniach: `` `Kolejka "${raw.name}" zostala zaktualizowana.` ``, `'Nie udalo sie zaktualizowac kolejki. Sprobuj ponownie.'`, `` `Kolejka "${created.name}" zostala utworzona.` ``, `'Nie udalo sie utworzyc kolejki. Sprobuj ponownie.'`. To dokładnie ten sam plik, który ticket wymienia w pkt (b), a użytkownik w en/de/uk dostaje toast po polsku. Autor przepisał tylko gettery walidacji. Rekomendacja: 4 nowe klucze `supervisor.queueForm.{successCreate,successEdit,errorCreate,errorEdit}` (z `{{name}}` w sukcesach) w 4 językach, `transloco.translate()` w handlerach `next`/`error` (moment zdarzenia, więc `translate()` jest tu poprawne; wzór: `admin-user-form.component.ts:304-339`), plus test (wywołanie `onSubmit` z mockiem `QueueService`, asercja `notifications.success/error` z tekstem z `pl`). Alternatywnie jawnie zawęzić AC w tickecie do walidacji, ale wtedy PRODUCT.md ("żaden tekst nie na sztywno") pozostaje naruszone.

### ⚠️ Security Concerns
_None identified._ Surowy `error.message` Twilio nie trafia już do UI/stanu (tylko `console.error`), co jest na plus.

### 🏗️ Architecture / Pattern Violations
- **MEDIUM-1 `admin-user-form.component.html:224` (`role="status"` na stałej podpowiedzi)** `#adm-skill-status` jest regionem live (`role="status"` = polite) i jednocześnie zawiera stałą podpowiedź. `loadSkills()` ustawia `skillsLoading=true` w `ngOnInit`, więc pierwszy render zawiera "Wczytywanie danych...", a po odpowiedzi tekst zamienia się na podpowiedź `skillsHint`. Efekt: czytnik ekranu ogłasza przy każdym otwarciu formularza zarówno zmianę stanu ładowania, jak i stałą instrukcję "Naciśnij Enter lub wybierz z listy..." (hałas, nic z tego nie jest zdarzeniem wymagającym uwagi). Dodatkowo ten sam element jest `aria-describedby` pola, więc opis pola zmienia się dynamicznie. Rekomendacja: zostawić `id="adm-skill-status"` + `aria-describedby` na zwykłym `<span class="form-hint">` dla podpowiedzi, a region live dać wyłącznie komunikatom stanu `skillsLoadFailed`/`skillsEmpty` (osobny element zawsze obecny w DOM, pusty, z treścią wstrzykiwaną tylko w tych dwóch stanach; `role="status"` działa tylko, gdy element istnieje przed zmianą treści). Stan `loading` raczej nie ogłaszać wcale (krótki, bez wartości). Test: asercja, że w stanie domyślnym podpowiedź nie leży w regionie live.
- **MINOR-1 `softphone.service.ts:60-62`** Dwa kolejne bloki JSDoc: stary `/** Last error from Twilio Device initialization (for diagnostics) */` został, dodano drugi tuż pod nim. Kompilator użyje tylko drugiego, pierwszy jest martwym, mylącym komentarzem. Usunąć stary. Przy okazji: `twilioDeviceError = signal<string | null>` przechowuje klucz, ale typ nie wymusza tego na konsumencie. Rozważyć unię `'agent.softphone.errors.tokenFetch' | 'agent.softphone.errors.device' | null` (typ `TwilioErrorKey`), żeby przyszły konsument UI nie wyrenderował klucza bez `| transloco`. Dziś nikt w UI sygnału nie czyta (grep: tylko serwis i spec), więc nie ma regresji, a klucze `agent.softphone.errors.*` są na razie "przyszłościowe" (nie martwe w sensie kodu, ale bez konsumenta w szablonie) — warto to wpisać w ticket konsumenta.

### 🔧 Improvements & Suggestions
- **MEDIUM-2 testy `admin-user-form.component.spec.ts:~125-208`** Brak testu stanu `skillsLoading`: żaden test nie sprawdza `skillsLoading() === true` w trakcie żądania (np. `Subject` zamiast `of`) ani tekstu `common.loading_data` w `#adm-skill-status`. Brak też testu: (a) że po `destroy` odpowiedź nie ustawia sygnałów (`takeUntilDestroyed`), (b) atrybutów `role="status"` / `aria-describedby="adm-skill-status"` na polu (AC a11y z opisu zadania), (c) trybu edycji (`user` ustawiony: `selectedSkills` z użytkownika nie powinny znikać z podpowiedzi, a `filteredSkills` ma je ukrywać). Test "a load error leaves the form usable" jest dobry (dociera do `createUser`), ale w trybie tworzenia; w edycji `updateUser` niepokryty.
- **MEDIUM-3 `softphone.service.spec.ts`** Pokryte tylko 2 gałęzie z 3 zmienionych: `tokenFetch` (500) i 404. Gałęzie `device.on('error')` (l. 130-134) i `catch` inicjalizacji (l. 140-145), które też zmieniły wartość sygnału na klucz `agent.softphone.errors.device`, nie mają testu (wymaga mocka `@twilio/voice-sdk` `Device`). Skoro ticket to zmiana kontraktu sygnału (tekst → klucz), brak pokrycia 2 z 3 miejsc zapisu to luka. Dodać test z `vi.mock` Device emitującego `error` i drugi z konstruktorem rzucającym.
- **MINOR-2 `pl.json` `supervisor.userForm.skillsHint` vs nowe `admin.userForm.skills*`** Terminologia w jednym elemencie statusu jest niespójna: istniejąca podpowiedź pl mówi "skill" ("...aby dodać skill"), a nowe `skillsLoadError`/`skillsEmpty` używają "umiejętności". W `pl.json` dominuje "skill" (21 wystąpień vs 4 "umiejętno"). Ujednolicić na jedną formę w obrębie formularza (rekomendacja: "umiejętności", zgodnie z etykietą pola i tickietem; wtedy poprawić też `skillsHint`, ale to zmiana istniejącego klucza, więc osobna decyzja).
- **MINOR-3 `uk.json:1188`** `supervisor.queueForm.errors.nameRequired`: "Назва обовʼязкова." używa U+02BC (ʼ), a reszta pliku (109 miejsc) ASCII `'` (np. "обов'язковий"). To jedyne wystąpienie ʼ w pliku, niespójne w wyszukiwaniu i diffach. Zamienić na `'`.
- **MINOR-4 `admin-user-form.component.ts` (gettery błędów)** Walidator `maxlength` dla imienia/nazwiska/email używa kluczy z `supervisor.userForm.errors.*`, a "required" z `admin.userForm.errors.*`. Mieszanie dwóch przestrzeni nazw w jednym getterze działa (klucze istnieją), ale utrudnia późniejsze zmiany. Opcjonalnie: sprzątanie w osobnym tickecie, żeby admin miał własny komplet. Nie blokuje; wspomniane w opisie jako świadome ponowne użycie.
- **MINOR-5 spec `queue-form`** Test używa `email.setErrors({ maxlength: ... })` po `setValue`, co jest sztuczne, ale zakomentowane i uzasadnione (walidator `email` Angulara sam odrzuca >254). Akceptowalne; nic do zmiany.

### ✅ Positive Observations
- Wzorzec reaktywności zgodny z lekcją z FE-129: gettery zwracają klucze, tłumaczy pipe `transloco` w szablonie (pipe czeka na załadowanie języka), brak `translate()` w polach i `computed`. `roleOptions` jako `computed` z `labelKey` bez tłumaczenia w środku, tłumaczenie w `<option>`. Grep `translate(` w obu komponentach: tylko handlery zdarzeń (poprawne miejsce).
- Konsumenci zmienionych API zaktualizowani: gettery (`nameError`, `firstNameError` itd.) i `roleOptions` używane wyłącznie we własnych szablonach/specach; pozostałe komponenty o tych samych nazwach getterów (login, tenant modale, campaign-form, user-form, email-settings) mają własne implementacje i nie są dotknięte. `twilioDeviceError` nie ma konsumentów poza serwisem i nowym spec.
- FE-127: `loadSkills()` z `catchError` + `takeUntilDestroyed(this.destroyRef)` w tej kolejności (błąd nie kończy strumienia i nie blokuje zapisu), zgodnie z `user-form`; `filteredSkills` filtruje po wpisanym tekście bez rozróżniania wielkości liter i ukrywa wybrane; `listbox-nav` (FE-126) dostaje dane z serwisu (test renderuje opcje po `ArrowDown`). Pierwszy `describe` poprawnie przeszedł z mutowania `availableSkills.set(...)` na mock serwisu (testuje realną ścieżkę).
- Tłumaczenia: forma bezosobowa/rozkazująca we wszystkich 4 językach, bez form rodzajowych ("Nie udało się" / "konnte nicht" / "Не вдалося"); de konsekwentnie "Mandant"/"Fähigkeiten"; brak martwych kluczy `admin.userForm.skills*`, `supervisor.queueForm.errors.*`, `agent.softphone.errors.*` poza uwagą o braku konsumenta dla `softphone.errors`.
- Brak nowych hex ani CSS (zmiany tylko w TS/HTML/JSON), standalone + OnPush zachowane, selektory bez zmian.

### Summary
⭐⭐⭐½ (3,5/5): technicznie czysta i dobrze przetestowana migracja getterów na klucze z poprawną reaktywnością, ale FE-128 nie domyka własnego kryterium #1 (4 literały PL w toastach `queue-form.component.ts`), a `role="status"` na stałej podpowiedzi to regres a11y.

**Blokuje commit/zamknięcie ticketu:** MAJOR-1 (literały w toastach `queue-form`). Przed zamknięciem FE-127 zalecane MEDIUM-1 (region live) i MEDIUM-2 (test stanu ładowania); MEDIUM-3 i MINOR-y nieblokujące (mogą pójść do backlogu).

### Runda 2 — FE-127 + FE-128 — 2026-10-30

**Ocena: 4,5/5. Nic nie blokuje commita.**

Weryfikacja wlasna: `npx ng test --watch=false --include=` dla 3 specow (queue-form, admin-user-form, softphone.service) = 3 pliki / 30 testow zielone. ESLint na zmienionych plikach: 0 bledow, 5 ostrzezen `no-console` w `softphone.service.ts`. Prettier: OK (pliki + `public/i18n`). Parytet kluczy pl/en/de/uk = 2784 w kazdym, roznica symetryczna pusta (skrypt Python). Wszystkie uzyte klucze istnieja we wszystkich 4 jezykach.

| Znalezisko | Status | Dowod |
|---|---|---|
| MAJOR-1 toasty queue-form | ZAMKNIETE | 4 klucze `supervisor.queueForm.{successCreate,successEdit,errorCreate,errorEdit}` w 4 jezykach, `transloco.translate()` w handlerach, interpolacja `{{name}}`. Grep PL (diakrytyki + ASCII-PL) w `queue-form.component.{ts,html}` i `admin-user-form.component.{ts,html}`: 0 trafien. |
| MEDIUM-1 region live | ZAMKNIETE | Osobny `.sr-only` `role="status"` zawsze w DOM, komunikuje tylko stan failed/empty (nie loading). `#adm-skill-status` jest zwyklym spanem pod `aria-describedby`. |
| MEDIUM-2 testy admin-user-form | ZAMKNIETE | Nowe testy przechodza. |
| MEDIUM-3 testy softphone | ZAMKNIETE | 5 testow przechodzi. Szew `protected createTwilioDevice(token)` + `vi.spyOn` jest akceptowalny: minimalny, 1 metoda, bez zmiany zachowania produkcyjnego, uzasadnienie (wspolne chunki z prawdziwym SDK) wiarygodne. Test rzutuje na `unknown`-owy interfejs `DeviceFactory`, wiec zmiana nazwy metody zlapie TypeScript tylko czesciowo (spyOn rzuci w runtime). |
| MINOR-1 JSDoc / typ | ZAMKNIETE | `TwilioDeviceErrorKey`, sygnal typowany. |
| MINOR-2 "skilli" w pl | ZAMKNIETE | `supervisor.userForm.skillsHint` pl = "...dodac skill." spojne z reszta UI. |
| MINOR-3 U+02BC | ZAMKNIETE | Skrypt: 0 wystapien U+02BC w 4 plikach i18n (uk uzywa `'`). |
| MINOR-4 | POMINIETE (backlog) | Zgodnie z decyzja autora. |

Uwaga do twierdzenia autora "w 3 plikach ticketu nie ma literalow ASCII-PL": prawda dla UI. W `softphone.service.ts` zostaly polskie komunikaty wylacznie w `console.warn/error/log` (linie 111, 117, 154, 211, 784), czyli diagnostyka dla dewelopera, nie tekst widoczny dla uzytkownika. Nie jest to regresja.

#### Nowe znaleziska
- **NEW-1 (MINOR, backlog):** `softphone.service.ts:69` `twilioDeviceError` nie ma zadnego konsumenta poza serwisem (grep po `src`, bez specow). Klucze i18n `agent.softphone.errors.*` istnieja i sa przetlumaczone, ale uzytkownik agent nigdy nie zobaczy bledu telefonii. Przed zmiana tez nie widzial (surowy string), wiec to nie regresja. Do rozwaznia osobny ticket: wyswietlic w pasku softphone przez pipe `transloco` z `role="alert"`.
- **NEW-2 (NIT):** `softphone.service.ts:133,138,196` i `:110,784` `console.log` powoduja ostrzezenia lintera `no-console` (0 bledow). Nie dotyczy tej zmiany, ale warto posprzatac przy okazji.

Podsumowanie: wszystkie poprzednie znaleziska domkniete i potwierdzone uruchomieniem, bez nowych problemow blokujacych. Pozostaja dwa drobne tematy do backlogu.

**Status (2026-10-30, tura 61):** FE-127 i FE-128 ✅ zamknięte w `TASKS-FRONTEND.md`, EPIC-32 zamknięty (oceny bez zmian: runda 1 3,5/5, runda 2 4,5/5). MINOR-4 → FE-144, NEW-1 → FE-145 (backlog).

## Review: FE-141 + FE-143 + FE-145 — 2026-10-30

Zakres: `disposition-sets-page.component.scss`, `queue-panel.component.{ts,html,spec.ts}`, `top-navbar.component.{ts,html,spec.ts}`, `softphone.service.{ts,spec.ts}`, `softphone.component.{ts,html,scss}`, nowy `telephony-error/*`, `frontend/public/i18n/{pl,en,de,uk}.json`. Zmiany niezacommitowane. Kontrasty policzone z wartości oklch z `styles.scss` (oklch -> OKLab -> liniowe sRGB -> WCAG), skrypt w scratchpadzie. Wyników `npm test`/lint/format/parytetu i18n nie powtarzałem (zgłoszone przez autora). Grep: 0 `--*` w pliku FE-141 bez definicji w `styles.scss`, 0 hexów/rgba.

### Oceny
| Ticket | Ocena |
|---|---|
| FE-141 | 4/5 |
| FE-143 | 4,5/5 |
| FE-145 | 2,5/5 |

### 🐛 Bugs / Critical Issues
- **MAJOR-1 (FE-145) `softphone.component.html:1` + `agent-desktop.component.html:151,287`** Komunikat o błędzie telefonii jest w ogóle niewidoczny wtedy, kiedy jest potrzebny. `<app-telephony-error />` siedzi w `app-softphone`, a ten jest montowany tylko dla aktywnej zakładki PHONE w stanie innym niż WRAPPING (`@case ('PHONE')` -> `@else`). Agent AVAILABLE bez otwartej zakładki (czyli dokładnie ten, który "nie wie, dlaczego nie odbiera połączeń" - opis FE-145) widzi pusty stan `tabs().length === 0` i żadnego błędu. Błąd pojawi się dopiero, gdy już jest zakładka połączenia, a wtedy retry jest zablokowany (`callInProgress`). W stanie WRAPPING też znika. Ponadto przy N zakładkach PHONE (po jednej montowanej naraz, ale przełączanie remontuje) region `role="alert"` jest tworzony od nowa. Kryterium #1 ("błąd widoczny dla agenta") spełnione tylko w wąskim przypadku. Rekomendacja: przenieść `<app-telephony-error />` do stałego miejsca widocznego przez cały czas na trasach agenta (`agent-shell` pod paskiem lub nad obszarem desktopu, albo `agent-desktop` poza `@if (activeTab())`), i usunąć z `softphone`. Wtedy też znika potrzeba zmiany hosta softphone na flex (patrz MEDIUM-5). Test integracyjny: shell/desktop z `twilioDeviceError` ustawionym i `tabs() = []` ma pokazać alert.
- **MAJOR-2 (FE-145) `softphone.service.ts:122` + `telephony-error.component.html:3`** Przycisk "Spróbuj ponownie" znika w momencie kliknięcia. `retryTwilioDevice()` -> `initializeTwilioDevice()` -> `doInitializeTwilioDevice()` wykonuje synchronicznie `this.twilioDeviceError.set(null)` (pierwsza instrukcja, przed pierwszym `await`), a szablon renderuje cały blok pod `@if (error(); as key)`. Skutki: (a) stan `retrying` (etykieta "Ponawianie…", `[disabled]`, `aria-busy`) nigdy nie jest widoczny w realnym użyciu - to martwy kod; (b) element z fokusem jest usuwany z DOM, więc fokus klawiatury wraca na `body` (użytkownik klawiatury/czytnika traci pozycję, WCAG 2.4.3); (c) przy porażce blok jest tworzony od nowa, przycisk bez fokusu, a region `role="alert"` ogłasza cały komunikat od zera (miganie + podwójne ogłoszenie). Testy tego nie łapią, bo `telephony-error.component.spec.ts:23-26` mockuje `retryTwilioDevice` i nie czyści `error`. Rekomendacja: nie zerować `twilioDeviceError` na starcie, tylko po sukcesie (po `register()`/zdarzeniu `registered`), albo trzymać blok widoczny przy `retrying()` (`@if (error() || retrying())` z ostatnim kluczem w osobnym sygnale). Dodać test integracyjny na prawdziwym serwisie (retry -> komunikat i przycisk nadal w DOM, `disabled`, po błędzie ten sam przycisk).

### ⚠️ Security Concerns
_None identified._ Do UI nie trafia surowy `error.message`, klucze i18n przez pipe.

### 🏗️ Architecture / Pattern Violations
- **MEDIUM-1 (FE-145) `softphone.service.ts:101-109,856-862`** Koalescencja nie jest czyszczona przy `resetForLogout()`. Gdy wylogowanie/ponowne logowanie zdarzy się w trakcie lotu (pobranie tokenu + `register()`), `initInFlight` nadal wskazuje obietnicę poprzedniego agenta, więc `initializeTwilioDevice()` nowego agenta (efekt w shellu) dostaje tę samą obietnicę i nigdy nie pobiera własnego tokenu; ponadto `this.twilioDevice` jest przypisywany dopiero po `await device.register()` (l. 173), więc `destroyTwilioDevice()` z `resetForLogout()`/`ngOnDestroy()` nie widzi tego Device i jest on rejestrowany pod tożsamością wylogowanego (wyciek, pre-existing, ale koalescencja przedłuża skutek). Okno wąskie (rząd 100-500 ms), ale to dokładnie scenariusz, dla którego `resetForLogout()` istnieje. Rekomendacja: generation counter (`const gen = ++this.initGen` w `initializeTwilioDevice`, `resetForLogout()` robi `this.initGen++` i `initInFlight = null`; po każdym `await` w `doInit...` `if (gen !== this.initGen) { device?.destroy(); return; }`), plus test (logout w trakcie lotu -> brak zarejestrowanego Device, kolejny init robi nowy `GET voice-token`).
- **MEDIUM-2 (FE-145) `softphone.service.ts:116-119` vs `:147-148`** Straż rozmowy w `retryTwilioDevice()` sprawdza tylko `this.activeCall`, a komentarz przy `disconnectAll()` (l. ~389) mówi, że noga wychodząca nie jest przechowywana w `activeCall`. Dla połączenia wychodzącego serwis nie blokuje retry (blokuje tylko UI przez `session`). Dodatkowo okno: init startuje bez rozmowy, w trakcie pobierania tokenu zaczyna się rozmowa, a `destroyTwilioDevice()` (l. 148) zrywa ją po powrocie tokenu (to zachowanie sprzed zmiany, ale retry+kolejka go uwidacznia). Rekomendacja: straż w serwisie oparta o to samo źródło co UI (`this.session()` != null && state != ENDED || `activeCall`), a także ponowne sprawdzenie tuż przed `destroyTwilioDevice()`.
- **MEDIUM-3 (FE-145) `softphone.service.ts:156-171` + `agent-shell.component.ts:71-79`** Niespójny właściciel stanu błędu. (a) `device.on('error')` ustawia klucz `device` i `ready=false`, ale `registered` nie czyści błędu, więc po samoczynnym odzyskaniu Device (np. po `updateToken`) baner "Telefonia niedostępna" zostaje do ręcznego retry (a `registered` jest już zdarzeniem sukcesu). (b) Efekt shella śledzi `twilioDeviceReady`; gdy Device zgłosi błąd po rejestracji (`ready` true -> false), efekt natychmiast woła `initializeTwilioDevice()` (status AVAILABLE, brak init w toku), a ta zeruje błąd w tej samej turze - baner mignie lub w ogóle nie zostanie wyrenderowany, a przy trwałej awarii powstaje pętla re-init bez backoffu (pre-existing, ale teraz kłóci się z nowym UI: dwa mechanizmy ponawiania). Do tego `Device` emituje `error` także dla błędów pojedynczych połączeń (nie tylko rejestracji), które teraz pokażą "Telefonia niedostępna". Rekomendacja: czyścić błąd na `registered`; w efekcie shella nie ponawiać automatycznie, gdy `twilioDeviceError() != null` (zostawić ręczny retry) albo dodać backoff; rozważyć filtr kodów błędów Device.
- **MEDIUM-4 (FE-141) `disposition-sets-page.component.scss:421,440`** `color-mix(in oklch, <kolor> 85%, black)` na hover. DESIGN.md nie definiuje tokenu hover dla przycisków wypełnionych; reguła (l. 289) to "hover obniża krycie do 0.88". W repo są inne `color-mix(... black)` (`adhoc-email-modal.scss:504`, `manual-callback-modal.scss:432` = 88%, `login.scss:239` = 85%, `edit-callback-modal.scss:66` = 80%), więc wzorzec jest już rozproszony, ale to nie to samo co DESIGN.md i nie ma jednej wartości. Kontrast `--on-accent` / `--on-danger` na hover (policzone): jasny: primary 6,93, danger 7,47 (rośnie, OK); ciemny: primary 4,88 (OK), **danger 4,24 < 4,5** (tekst 0,875rem/500, nie jest "large", więc AA nie spełnione); stan spoczynku w ciemnym: 7,35/6,32. Ściemnianie przycisku w ciemnym motywie jest też odwrotne do intencji (inne hovery w ciemnym jaśnieją, por. `border-control-hover`). Rekomendacja: przyjąć regułę DESIGN.md (`opacity: 0.88` na `:hover:not(:disabled)`, jak w pozostałych przyciskach systemu) albo dodać token `--accent-hover`/`--danger-hover` po decyzji właściciela (ticket FE-141 sam mówi "nie wymyślać tokenu"). Minimalnie: `.btn-danger` hover tylko w jasnym, albo 92% w ciemnym.
- **MEDIUM-5 (FE-145) `softphone.component.scss:5-20`** Host softphone zmieniony na `display:flex; flex-direction:column`, a `.softphone` z `height:100%` na `flex:1 1 auto; min-height:0`. Przejrzałem scss/html: stany bez błędu wyglądają identycznie (`.softphone` wypełnia host, `align-items:center` zostaje w obrębie karty), ale (a) `min-height:0` bez `overflow` pozwala treści (aktywna rozmowa + transfer) wylać się poza tło karty zamiast się przewinąć, gdy baner zabierze wysokość - wcześniej `height:100%` miało ten sam problem, lecz teraz baner go pogłębia; (b) brak odstępu między banerem a kartą (`:host` w `telephony-error.scss` bez `margin-bottom`), baner przylega do karty; (c) gdy `session()` jest `null` host zawiera tylko baner. Jeśli MAJOR-1 zostanie naprawiony (przeniesienie baneru), zmianę hosta softphone należy cofnąć (bez tej zmiany `height:100%` działało). Gdyby baner został w softphone: dodać `gap: 0.75rem` na hoście i `overflow:auto` na `.softphone`.

### 🔧 Improvements & Suggestions
- **MINOR-1 (FE-145) `telephony-error.component.html:3,11`** `role="alert"` z treścią wstawianą dynamicznie do elementu stale obecnego w DOM: tak, zostanie ogłoszony (region istnieje przed zmianą, a `alert` ma `aria-live=assertive` + `atomic`). Zastrzeżenia: (a) `atomic` powoduje, że każda zmiana wewnątrz (np. zmiana etykiety przycisku na "Ponawianie…") odczyta cały blok ponownie; (b) interaktywny przycisk wewnątrz `alert` jest ogłaszany razem z komunikatem, lepiej wydzielić `role="alert"` tylko na tytuł+tekst, a przycisk poza regionem; (c) `aria-busy` na `<button>` nie jest wspierane jako stan ładowania przycisku przez czytniki, a `[disabled]` wyjmuje przycisk z kolejki Tab (fokus ginie, patrz MAJOR-2). Rekomendacja: `aria-disabled="true"` + blokada w `retry()`, zamiast `disabled`; stan "Ponawianie…" jako osobny `role="status"`. Do weryfikacji NVDA/VoiceOver (nie dało się zweryfikować w testach jsdom).
- **MINOR-2 (FE-145) `telephony-error.component.scss:28`** Cel kliknięcia: `min-height:2rem` = 32 px >= 24 px (WCAG 2.5.8) - OK; szerokość wynika z paddingu 0,75rem + tekstu, nie mniej niż ~90 px. Kontrast `--danger-text` na `--danger-soft` (tekst i przycisk z `currentColor`): jasny 6,16, ciemny 6,96 - OK (>= 4,5). Obrys przycisku `currentColor` względem `danger-soft` >= 3:1. Hover `color-mix(var(--danger) 12%, transparent)` to tło lekko ciemniejsze od `danger-soft`, kontrast tekstu nadal > 4,5 (szacunkowo ~5,5-6). Stan `:disabled { opacity: .7 }` obniża kontrast tekstu (wyłączone kontrolki wyłączone z WCAG, ale "Ponawianie…" jest informacją, patrz MAJOR-2).
- **MINOR-3 (FE-145) i18n 4 języków** Treść poprawna i spójna: `title` ("Telefonia niedostępna" / "Telephony unavailable" / "Telefonie nicht verfügbar" / "Телефонія недоступна"), komunikaty neutralne płciowo ("Spróbuj ponownie; jeśli błąd wraca, odśwież stronę"), `retrying` z wielokropkiem w 4 językach. Drobiazgi: pl "jeśli błąd wraca" jest mniej naturalne niż "jeśli błąd się powtarza" (uk używa "повторюється", de "bei wiederholtem Fehler"), do ujednolicenia; de "Wird erneut versucht…" jako etykieta przycisku jest nieco długie, rozważyć "Versuche erneut…". Test parytetu w `telephony-error.component.spec.ts:112-118` sprawdza tylko niepustość, nie treść.
- **MINOR-4 (FE-145) `softphone.service.spec.ts:100-147`** Brak testów: (a) odpięcia listenerów starego Device (`removeAllListeners` na ścieżce `destroyTwilioDevice`; `fakeDevice` ma stub, ale nie ma asercji), (b) porażki tokenu przy retry (`initInFlight` czyszczone, `twilioDeviceInitializing` wraca na false - to najważniejsza gałąź "czy obietnica jest czyszczona przy błędzie", dziś pokryta tylko sukcesem), (c) `retryTwilioDevice()` jako no-op w rozmowie (brak testu), (d) kolejny `initializeTwilioDevice()` po zakończeniu lotu robi nowy `GET`. Kod jest poprawny w (b): `doInitialize...` nie odrzuca obietnicy (wszystkie ścieżki łapią), a `.finally` zawsze zeruje `initInFlight` i sygnał - sprawdzone w kodzie, tylko brak testu.
- **MINOR-5 (FE-143) `queue-panel.component.html:5`** `badgeLabel()` wywołane dwa razy w jednym wyrażeniu i linia > 100 znaków; użyć `@let label = badgeLabel();` i złamać atrybut. Cecha poprawności: pipe `transloco` z parametrami reaguje na zmianę klucza i języka, `pluralRules` jako `computed` zależy od `lang()` (toSignal z `langChanges$`) - reaktywność poprawna, a w oknie ładowania pipe zwraca ostatnią wartość (stary tekst), nie klucz.
- **MINOR-6 (FE-143) `queue-panel.component.spec.ts:~208-222`** Asercja "niepusty" w oknie ładowania jest deterministyczna: między `setActiveLang('en')` a `label()` nie ma `await`, a pipe zwraca poprzednią wartość (polski tekst), więc `not.toBe('')` przechodzi, ale tylko dzięki temu, że pipe zachowuje stary tekst - nie jest to asercja o poprawnym tekście i nie złapie regresji "zła kategoria plural dla starego tekstu". Niestabilność czasowa: `settle()` czeka 120 ms rzeczywistego czasu przy opóźnieniu loadera 40 ms (kolejność timerów gwarantowana, ale na bardzo obciążonym CI to pozostaje test oparty o zegar). Rekomendacja: `await firstValueFrom(transloco.load('en'))` zamiast `setTimeout(120)` (deterministyczne) lub `vi.useFakeTimers()`. Ten sam wzorzec w `top-navbar.component.spec.ts:~290`.
- **MINOR-7 (FE-143) `top-navbar.component.spec.ts:298-299`** NIT-2 zrealizowany pozornie: `not.toBe('')` i `not.toContain('agent.desktop')` tuż przed `toBe('3 hours 45 minutes')` są zbędne (wynikają z równości). Okno ładowania (l. 292-293) ma tylko `not.toContain('agent.desktop')`, bez asercji niepustości - tam byłaby wartość. Przenieść `not.toBe('')` do okna ładowania.
- **MINOR-8 (FE-143) `top-navbar.component.ts:88-95`** Nowy `pluralRules` został wstawiony między JSDoc `workTimeParts` ("Zero parts are dropped ...") a jego komentarz `// i18n note`, więc JSDoc opisuje teraz `pluralRules`/jest osierocony. Przenieść `pluralRules` nad JSDoc. Brak testu na kolejność sr-only (NIT-1: `top-navbar.component.html:78-81`): zmiana poprawna (podpis, potem "brak danych"), ale bez asercji kolejności `querySelectorAll('.kpi-tile')[3]` dzieci.
- **MINOR-9 (FE-141) `disposition-sets-page.component.scss`** Semantyka mapowania: poprawna (`color-text-tertiary` -> `--text-3`, `color-primary`/`-dark`/`-subtle` -> `--accent`/hover/`--accent-soft`, `color-surface-hover`/`code-bg` -> `--bg-subtle`, `color-surface-raised` -> `--bg-elevated`, `color-danger-dark` -> hover danger). Uwagi: (a) `__code` na `--bg-subtle` wewnątrz wiersza, który na hover też ma `--bg-subtle` (l. ~173-180, ~196), więc plakietka kodu traci tło na hover; użyć `--bg-surface`/`--neutral-soft` dla chipa; (b) `.form-error`, `.required`, `.form-group--error` używają `--danger` jako koloru tekstu (5,17-5,32 jasny, 5,28-5,69 ciemny: przechodzi), ale DESIGN.md/pozostałe pliki używają `--danger-text` (7,19 jasny, 8,75 ciemny) dla tekstu; zalecana zmiana; (c) karta `ds-set-card` (`role="button"`) ma obrys `--border-1` (1,31:1 / 1,21:1) - obrys kontenera interaktywnego bez drugiego wyróżnika poza hoverem, DESIGN.md dopuszcza dla kart, ale kontrolka `role=button` wg WCAG 1.4.11 wymaga 3:1 albo innego wskaźnika (tło `--bg-surface` na `--bg-subtle` panelu daje ok. 1,1:1); do decyzji projektowej, nie blokuje.

### Kontrasty (FE-141, policzone; jasny / ciemny)
`--text-1/--bg-surface` 16,44 / 13,98; `--text-2/--bg-surface` 8,45 / 9,48; `--text-3/--bg-surface` 6,82 / 7,68; `--text-3/--bg-subtle` (hover wiersza) 5,80 / 7,51; `--text-3/--bg-elevated` 6,62 / 7,13; `--text-2/--accent-soft` (aktywna karta) 7,10 / 7,32; `--text-3/--accent-soft` 5,72 / 5,93; `--on-accent/--accent` 4,87 / 7,35; `--on-danger/--danger` 5,32 / 6,32; `--accent-text/--bg-surface` (ghost) 7,52 / 10,10; `--accent-text/--accent-soft` (ghost hover) 6,32 / 7,80; plakietki: `success-text/soft` 6,65 / 7,94, `danger-text/soft` 6,16 / 6,96, `warning-text/soft` 5,36 / 7,26, `neutral-text/soft` 7,72 / 7,81; `--border-control/--bg-surface` (inputy, UI >= 3:1) 3,94 / 3,50; `--border-control/--bg-elevated` (pole w formularzu na elevated) 3,83 / 3,25; `--accent/--bg-surface` (fokus/obrys) 4,87 / 6,62. Wszystko >= 4,5 (tekst) i >= 3 (UI), z wyjątkiem hover `.btn-danger` w ciemnym (4,24, MEDIUM-4).
Rozszerzenie zakresu (cały plik na tokeny): bezpieczne wizualnie. Usunięte fallbacki hex pokrywały się z tokenami w jasnym motywie, a w ciemnym poprawiają wcześniej niewidoczne (jasne hexy na ciemnym tle) powierzchnie; brak niezdefiniowanych tokenów; poza MEDIUM-4 i MINOR-9 nie znalazłem regresji. Ryzyko: kryterium "kontrast >= 4,5/3" tickietu jest spełnione, ale ticket nie zlecał zmiany hovera ani `--border-control` (to realna zmiana wyglądu pól: obrys ciemniejszy niż dotąd). Zmiana poza zakresem, ale zgodna z DESIGN.md, więc akceptowalna.

### ✅ Positive Observations
- `initializeTwilioDevice()` koalescencja jest zbudowana poprawnie: `.finally` zeruje `initInFlight` i sygnał na każdej ścieżce (w tym tokenFetch i 404), a `doInitializeTwilioDevice` nigdy nie odrzuca - brak wiszącej obietnicy; tożsamość zwracanej obietnicy jest stabilna.
- Wyciek `Device` po nieudanym `register()` naprawiony poprawnie i z `try/catch` wokół sprzątania (`:179-184`), test pokrywa wywołanie `destroy`.
- Typowany klucz błędu (`TwilioDeviceErrorKey`) jest używany przez komponent przez pipe; `role="alert"` stale obecny w DOM to właściwy wzorzec (lekcja z FE-127/128).
- `queue-panel`: wzorzec FE-129 zastosowany wiernie (klucz+liczba w `computed`, tekst w pipe), `pluralRules` poprawnie zależne od `lang()`, test z opóźnionym loaderem i zmianą kategorii plural (pl "few" -> en "other", count 2) realnie sprawdza zmianę klucza.
- FE-141: kompletna zamiana, 0 hexów, tokeny istnieją, shimmer na tokenach.

### Summary
FE-141 4/5, FE-143 4,5/5, FE-145 2,5/5. FE-143 i FE-141 są prawie gotowe; FE-145 ma dobry rdzeń serwisu (koalescencja, sprzątanie Device), ale komunikat jest zamontowany w miejscu, w którym agent bez otwartej rozmowy go nie zobaczy (MAJOR-1), a przycisk ponowienia znika w momencie kliknięcia, bo serwis zeruje błąd synchronicznie (MAJOR-2).

**Blokuje commit:** FE-145: MAJOR-1 (miejsce montowania) i MAJOR-2 (zerowanie błędu na starcie retry, utrata fokusu, martwy stan "retrying"). Rekomendowane przed commitem FE-145: MEDIUM-1 (generation counter przy logout) i MEDIUM-3 (czyszczenie błędu na `registered`, brak auto-reinit przy aktywnym błędzie). FE-141: MEDIUM-4 (hover danger w ciemnym 4,24:1 < 4,5) do poprawy lub jawnej decyzji, nie blokuje. FE-143: bez blokerów (MINOR-y do backlogu). Do zamknięcia FE-145 pozostaje też dług weryfikacji na żywo z Twilio (AC #4) i czytnika ekranu (NVDA/VoiceOver) - nie wykonane w tym review.

### Runda 2 — FE-141 + FE-143 + FE-145 — 2026-10-30

Zakres: ponowna analiza niezacommitowanego diffu (`softphone.service.ts`, `agent-shell.component.ts`, `agent-desktop.component.{html,ts}`, `telephony-error/*`, `queue-panel`, `top-navbar`, `disposition-sets-page.component.scss`, i18n pl/de). Dodatkowo uruchomiony tymczasowy test (plik usunięty, drzewo bez zmian) potwierdzający NEW-1. Dane z przeglądarki od koordynatora (host `app-telephony-error` pusty bez błędu, 0 px, brak scrolla) uwzględnione.

#### Oceny (runda 2)
| Ticket | Runda 1 | Runda 2 |
|---|---|---|
| FE-141 | 4/5 | 4,5/5 |
| FE-143 | 4,5/5 | 5/5 |
| FE-145 | 2,5/5 | 3,5/5 |

#### Status znalezisk z rundy 1
- FE-141: MEDIUM-4 (hover) ZAMKNIĘTE (`opacity: .88` na `:hover:not(:disabled)`); MINOR-9a (chip), 9b (`--danger-text`), 9c (`--border-control` na karcie) ZAMKNIĘTE w kodzie. Nie przeliczałem kontrastu `--border-2` na `--bg-elevated` (chip to element dekoracyjny, tekst bez zmian).
- FE-143: MINOR-5 (`@let badge`), MINOR-6 (deterministyczny `firstValueFrom`), MINOR-7, MINOR-8 (JSDoc, test NIT-1) ZAMKNIĘTE. `track $index`: pipe zachowuje ostatnią wartość przy zmianie klucza, więc okno ładowania nie pokazuje surowego klucza; to tekst tylko dla czytnika (`sr-only`), bez animacji, więc brak wpływu na animacje i dostępność. Skutek uboczny: nowo utworzona instancja (zmiana liczby części, np. 2 -> 1) zwraca na chwilę pusty tekst, akceptowalne.
- FE-145: MAJOR-1 (miejsce montowania) ZAMKNIĘTE (pod `</header>`, poza `@if (activeTab())`, softphone cofnięty, MEDIUM-5 zniknął). MAJOR-2 (zerowanie błędu na starcie) ZAMKNIĘTE (błąd czyszczony tylko na `registered`/404, blok zostaje przy retry, przycisk z `aria-disabled` zachowuje fokus). MEDIUM-1 CZĘŚCIOWO (patrz NEW-1). MEDIUM-2 ZAMKNIĘTE (`isCallInProgress()` uwzględnia `session`, ponowna kontrola po tokenie przed `destroy`). MEDIUM-3 ZAMKNIĘTE (czyszczenie na `registered`, brak auto-reinit przy błędzie, filtr `call`). MINOR-1 CZĘŚCIOWO (patrz NEW-2). MINOR-3, MINOR-4 ZAMKNIĘTE (treść pl/de poprawiona, testy dodane).

#### Nowe problemy
- **NEW-1 (MAJOR, FE-145) `softphone.service.ts` handlery `registered`/`error`/`unregistered` (~l. 172-190) bez straży generacji.** Potwierdzone wykonaniem (tymczasowy test): `initializeTwilioDevice()` -> token -> `register()` w toku -> `resetForLogout()` -> stary Device emituje `registered` -> `twilioDeviceReady() === true`; potem gałąź `stale()` robi `removeAllListeners()` + `destroy()`, ale nie cofa `ready`. Nowy agent ma `ready=true` bez Device, więc efekt shella (`!ready`) nigdy nie woła init: agent bez telefonii i bez komunikatu, aż do przeładowania. To dokładnie scenariusz generation countera; istniejący test "resetForLogout during register()" nie emituje `registered`, więc go nie łapie. Pre-existing w sensie istnienia wyścigu, ale poprawka FE-145 deklaruje jego zamknięcie. Fix (2 linie): w każdym handlerze `if (stale()) return;` (lub `if (this.twilioDevice !== device && stale())`), a w gałęzi stale po `register()` dodatkowo nie dotykać sygnałów; dodać test z `handlers['registered']()` po `resetForLogout()`. Analogicznie `error` starego Device może ustawić `twilioDeviceError` nowej sesji.
- **NEW-2 (MINOR, FE-145) a11y `telephony-error.component.html:1-4`.** Dane z przeglądarki potwierdzają, że `role="alert"` jest wstawiany do DOM razem z treścią (host pusty bez błędu). Specyfikacja ARIA i NVDA/JAWS zwykle ogłaszają taki wstawiony alert, ale jest to mniej niezawodne niż zmiana treści w regionie obecnym wcześniej (VoiceOver/Safari, część kombinacji JAWS bywa pomijana); to cofa lekcję z FE-127/128. Nie zweryfikowano z czytnikiem. Rekomendacja: stały `<div role="alert">` w hoście (zawsze w DOM, pusty), a `@if (error(); as key)` wewnątrz niego (tytuł+tekst); przycisk poza regionem. Dodatkowo: gdy ponowienie się nie powiedzie, błąd się nie zmienia (może zmienić klucz tokenFetch/device), więc czytnik nie dostaje informacji o porażce; etykieta "Ponawianie…" jest poza alertem, a `aria-busy` na `<button>` nie jest ogłaszane. Dodać osobny `role="status"` z komunikatem "Ponawianie…" / "Nadal niedostępne".
- **NEW-3 (MINOR, FE-145) `telephony-error.component.ts:25`.** `callInProgress` to `computed` oparty o `isCallInProgress()`, które czyta też niereaktywne pole `activeCall`; computed odświeża się tylko po zmianie `session()`. Dziś `session` jest ustawiany równolegle z `activeCall`, więc brak realnego błędu, ale to ukryta zależność. Rozważyć sygnał `callInProgress` w serwisie. Dodatkowo sesja, która utknie w stanie innym niż `ENDED`, blokuje retry bez wyjścia (tylko przeładowanie).
- **NEW-4 (INFO) brak auto-reinit przy błędzie.** Weryfikacja scenariuszy: (a) `Device` sam się wyrejestruje (`unregistered` bez `error`): `hasError` false, więc efekt shella nadal ponawia automatycznie, agent NIE zostaje bez telefonii; (b) błąd rejestracji + chwilowy zanik sieci: Device SDK sam się rejestruje po powrocie sieci, `registered` czyści błąd; jeśli Device jest martwy, wymagany jest ręczny klik (świadoma decyzja, baner jest widoczny); (c) zmiana statusu AVAILABLE -> inny -> AVAILABLE przy widocznym błędzie też nie ponawia. Opcjonalnie: 1-2 automatyczne próby z backoffem przed pokazaniem banera.
- **Filtr `call` w `error`:** nie ukrywa błędu rejestracji. W Twilio Voice SDK błędy rejestracji/sygnalizacji (token, WebSocket) emitowane są bez argumentu `call`; argument dostają błędy powiązane z konkretnym połączeniem. Przy błędzie z `call`, który kończy się `unregistered`, auto-reinit działa jak w (a); gdy trwa rozmowa, init jest pomijany przez straż, a efekt uruchomi się ponownie po zmianie statusu po rozmowie. Ryzyko niskie (nie zweryfikowano na żywo z Twilio).

#### Niestabilny `social-integrations.component.spec.ts` (tylko opis)
Plik (388 linii) nie zawiera `setTimeout`/`setInterval`/timerów/fake timers, a komponent też ich nie używa; jedyny `await` to `TestBed.configureTestingModule(...).compileComponents()` (l. 65). Brak oczywistej przyczyny po stronie timerów. Najbardziej prawdopodobne: równoległość w pełnym przebiegu (obciążenie CPU podczas kompilacji szablonów w jsdom i domyślny limit 5 s na test/hook), co tłumaczy zielony przebieg osobno i przy powtórce. To hipoteza, bez dowodu; ewentualnie zwiększyć `testTimeout` lub sprawdzić współdzielony stan między plikami.

#### Podsumowanie rundy 2
FE-141 4,5/5, FE-143 5/5, FE-145 3,5/5. Rdzeń FE-145 jest poprawiony (miejsce, retry, straż rozmowy, brak pętli), ale generacja nie chroni przed zdarzeniami starego Device (NEW-1, potwierdzone testem).

**Blokuje commit:** FE-141 i FE-143: nic. FE-145: brak twardego blokera, ale NEW-1 należy naprawić przed commitem (2 linie + test), bo to cel poprawki; NEW-2 (stały kontener alertu) rekomendowany, można do osobnego ticketu po weryfikacji czytnikiem.

**Status (runda 3, FE-145 — 2026-10-30):** runda 3 zaadresowała NEW-1 (straż generacji w handlerach `Device`) i NEW-2 (stały pusty `role="alert"`, osobny `role="status"`, przycisk poza alertem) z rundy 2. Bez ponownego code review — zlecający przejrzał diff i testy. NEW-2 niezweryfikowane czytnikiem ekranu; stan błędu niezweryfikowany na żywo z Twilio. Oceny bez zmian (FE-141 4,5/5, FE-143 5/5, FE-145 3,5/5).
