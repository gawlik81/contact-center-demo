---
name: project_resizable_dialog_directive
description: ResizableDialogDirective (shared/directives) dodaje drag-to-resize do natywnych <dialog> modali; pierwsze podłączenie w AdHocEmailModalComponent, CSS max-width/max-height gotcha do pamiętania przy podłączaniu do kolejnych ~32 modali
metadata:
  type: project
---

## Co istnieje

`shared/directives/resizable-dialog.directive.ts` — pierwsza directive w projekcie (katalog
`shared/directives/` wcześniej był pusty, tylko `.gitkeep`; podobnie `shared/pipes/` wciąż pusty).
Standalone `@Directive({ selector: '[appResizableDialog]' })`, dodaje manualny drag-handle
(pointerdown/move/up, nie CSS `resize: both`) w prawym dolnym rogu hosta.

Style współdzielone: `shared/styles/_resizable-dialog.scss` (placeholder `%resizable-dialog-host`
+ klasa `.resizable-dialog__handle`), importowany przez `@use '...' as *;` w SCSS komponentu
(ten sam wzorzec co `_contact-badges.scss` — partial czeka na konsumenta, nic nie importuje go
globalnie w `styles.scss`).

Pierwsze (jedyne na razie) podłączenie: `AdHocEmailModalComponent`
(`features/agent/pages/customers/adhoc-email-modal/`). Atrybut na `<dialog>`:
`appResizableDialog [appResizableDialogMinWidth]="520" [appResizableDialogMinHeight]="420"`.

## Decyzja: manual drag-handle, nie CSS `resize: both`

**Why:** wszystkie ~33 dialogi w projekcie używają `overflow: hidden` (potrzebne dla
`border-radius` na headerze z gradientem) — `resize: both` wymaga `overflow: auto/scroll` i nie
działa z `overflow: hidden`. Do tego natywny resize handle przeglądarki ląduje pod zaokrąglonym
narożnikiem. Manual JS drag-handle obchodzi obie te przeszkody i działa identycznie niezależnie
od layoutu konkretnego modala — ważne, bo cel jest podłączyć tę samą directive do kolejnych ~32
modali później.

## CSS gotcha odkryty podczas implementacji (ważne dla przyszłych podłączeń)

Directive ustawia inline `style="width: Npx; height: Mpx"` na hoście. Jeśli komponent ma w SCSS
`max-width: 520px` (typowy wzorzec w tym projekcie — każdy dialog ma jakiś `max-width` jako
domyślny limit szerokości), **`max-width` zawsze wygrywa nad `width`, niezależnie czy `width`
pochodzi z inline style czy klasy** — bez fixa dialog wizualnie "zamykał się" na starej
szerokości mimo że inline `width` rosło (computed width zostawał spięty do `max-width`).

Fix zastosowany w `adhoc-email-modal.component.scss`: directive dodaje trwałą klasę
`is-resized` (osobną od tymczasowej `is-resizing`, która trwa tylko podczas drag) przy pierwszym
`pointerdown`. SCSS komponentu ma:
```scss
&.is-resizable-dialog.is-resized,
&.is-resizing {
  max-width: none;
  max-height: none;
}
```
Górny limit po zdjęciu CSS max-width jest wymuszany w JS przez directive (`appResizableDialogMaxWidthVw`
= 0.95 * `window.innerWidth`, `appResizableDialogMaxHeightVh` = 0.9 * `window.innerHeight`) —
zweryfikowane Playwrightem: clamp do exactly `0.95vw x 0.9vh` i exactly min-width/min-height przy
agresywnym przeciąganiu w obu kierunkach.

**Gdy podłączasz directive do kolejnego modala:** sprawdź czy ten modal ma analogiczny
`max-width`/`max-height` w swoim `.scss` i dodaj tę samą regułę `&.is-resized { max-width: none;
max-height: none; }` (selektor klasy dialogu tego modala), inaczej resize "nie będzie działać"
mimo że directive jest podłączona poprawnie — sam inline style nie wystarczy.

Także: `&__content` w dialogu musi mieć `height: 100%; min-height: 0;` i `&__body` (scrollowalna
część) `flex: 1 1 auto` (NIE `max-height: calc(92vh - 148px)` na sztywno) żeby resize w pionie
faktycznie powiększał użyteczny obszar formularza, a nie tylko ramkę dialogu.

## Jak testować wizualnie tę aplikację (local-demo docker setup)

`docker-compose` z `.env.local-demo` NIE publikuje portu backendu na hosta (`cc-backend` ma tylko
`8080/tcp` wewnętrzny, dostępny przez `cc-nginx`). `npm start` (proxy do `localhost:8080`) więc
nie złapie backendu bez dodatkowego mostka. Tymczasowe (bez modyfikacji compose) rozwiązanie:
```
docker run -d --rm --name temp-port-forward --network contact-center-network -p 8080:8080 \
  alpine/socat tcp-listen:8080,fork,reuseaddr tcp-connect:cc-backend:8080
```
Pamiętać o `docker stop temp-port-forward` po teście.

Baza w tym docker-demo env (`contact_center` db, user `ccapp`, NIE `contact_center_dev`/`postgres`
z czystego dev seed) ma własne konto demo, różne od tych w README.md głównego repo:
`agent1@kmnsoftware.com` / `Test@12345`, tenant "KMN Software". Tylko jeden seedowany klient w tym
tenancie: Paweł Miernik (`pawel.miernik81@gmail.com`) — szukać po "Miernik" w wyszukiwarce klientów,
nie po przypadkowych literach (wymaga min. 2 znaków i faktycznego matcha).

Logowanie jest dwuetapowe: ekran 1 = tylko email + przycisk "Dalej" (woła
`POST /api/public/tenants-by-email`, ustawia tenantId), ekran 2 = hasło + "Zaloguj się". Direct
`page.goto('/agent/customers')` po zalogowaniu czasem przekierowuje na `/forbidden` (pełny reload
gubi coś w auth state) — nawigować przez kliknięcie linku w sidenav (SPA routing), nie przez
`page.goto` na chronioną trasę.

Playwright nie był zainstalowany w projekcie; `npx playwright install chromium` (bez
`--with-deps`, bo brak sudo) wystarczył do testów headless na tym hoście.

Zobacz też [[project_design_alignment]] dla ogólnych wzorców weryfikacji wizualnej ekranów agenta.
