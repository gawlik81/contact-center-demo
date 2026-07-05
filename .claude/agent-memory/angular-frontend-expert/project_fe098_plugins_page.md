---
name: project_fe098_plugins_page
description: FE-098 strona Ustawienia > Pluginy (supervisor) — dryf BE/FE w PluginVersionDto.permissions, brak narzędzia do weryfikacji w przeglądarce w tym środowisku
metadata:
  type: project
---

FE-098 (EPIC-28) zrealizowane 2026-06-23: `frontend/src/app/features/supervisor/pages/settings/plugins/plugins-page.component.{ts,html,scss}`. Upload .jar (drag&drop + input), dialog instalacji z checkboxami `permissions`, lista instalacji z badge `healthStatus`, toggle enable/disable, rollback, uninstall (przez `ConfirmDialogComponent`).

**Why:** Ticket FE-097 (warstwa danych) został napisany PRZED tym, jak backend rozszerzył `PluginVersionDto.java` o pole `permissions: List<String>` (zadeklarowane z explicit przeznaczeniem dla FE-098 w javadocu). Frontendowy `plugin.model.ts` z FE-097 nie miał tego pola — zweryfikowane bezpośrednio w `PluginVersionDto.java`, nie tylko zaufane na słowo z promptu/ticketu. Dryf BE/FE między sesjami jest możliwy gdy ticket-zlecający pracę nad FE pisany jest z założeniem stanu backendu, który już się zmienił.

**How to apply:** Gdy ticket twierdzi "pole X jest teraz dostępne w DTO Y" — zawsze zweryfikować bezpośrednio w pliku backendowym (`grep`/`Read` na `*Dto.java`) PRZED zaufaniem stwierdzeniu w opisie zadania, nie tylko w istniejącym pliku `*.model.ts` (który może być nieaktualny). W tym przypadku potwierdzone, że pole faktycznie istnieje w `PluginVersionDto.java` (record, linia `permissions` między `validationErrors` i `uploadedByUserId`) — model TS bezpiecznie rozszerzony (brak istniejących testów na ten model).

`ConfirmDialogComponent` (`shared/components/confirm-dialog/`) NIE jest sterowany przez `ElementRef` + `showModal()`/`close()` manualnie z komponentu rodzica — ma własny `ngAfterViewInit()` który wywołuje `showModal()` automatycznie. Wzorzec użycia: `@if (targetSignal())  { <app-confirm-dialog [message]="..." (confirmed)="..." (cancelled)="targetSignal.set(null)" /> }`. Zobacz `disposition-sets-page.component.html` linie ~317-338 jako referencyjny wzorzec.

**Środowiskowe ograniczenie weryfikacji wizualnej:** w tym sandboxie (`/home/pawelm/contact-center`) kontener `cc-backend` (docker compose) NIE publikuje portu na hosta (tylko sieć wewnętrzna compose) — `curl localhost:8080` zwraca exit 7. Tylko `cc-nginx` publikuje port 80, ale serwuje starszy zbudowany obraz `cc-frontend`, nie dev build z aktualnymi zmianami. W tym środowisku nie jest też dostępne żadne narzędzie do automatyzacji przeglądarki (Playwright/zrzuty ekranu) — `ToolSearch` na "browser screenshot navigate" nie znalazł nic. Weryfikacja zmian frontendowych w żywej przeglądarce wymaga zgłoszenia tego ograniczenia użytkownikowi wprost, zamiast próby fałszywego potwierdzenia — zamiast tego opieraj się na `npm run build` (sprawdź że nowy lazy chunk się generuje bez nowych warningów) i `npm run lint`.
