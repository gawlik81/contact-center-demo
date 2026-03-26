# CR-FRONTEND.md – Code Review Frontend
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
