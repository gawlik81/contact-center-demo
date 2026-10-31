---
name: feedback_verify_live_db_state
description: Stwierdzenia o stanie żywej bazy (które migracje Flyway zastosowane) starzeją się w godzinach — zawsze odczyt flyway_schema_history przed zapisem w PROGRESS/TASKS
metadata:
  type: feedback
---

Przed zapisem JAKIEGOKOLWIEK stwierdzenia o stanie żywej bazy (które wersje Flyway zastosowane, czy kolumna/widok istnieje) wykonaj odczyt: `docker exec cc-postgres psql -U ccapp -d contact_center -c "select version, installed_on, success from flyway_schema_history order by installed_rank desc limit 10"` (tylko SELECT) oraz `docker ps` / `unzip -l /app/app.jar` w `cc-backend` dla zawartości obrazu.

**Why:** w turach 21–24 (2026-10-09) powielano zdanie „V127–V133 niezastosowane, restart zastosuje, wymaga zgody właściciela” — a backend został przebudowany poza naszą pracą o 21:00 UTC i zastosował V127–V133 (w tym nieodwracalny drop `contacts_dw.remote_address`). Pomyłka kosztowała 4 tury błędnych ostrzeżeń (wcześniej analogicznie V094–V097 opisane jako niezastosowane, a były od 2026-09-27; V125 vs V126). Wykryto dopiero w turze 25.

**How to apply:** przy każdej turze dotykającej migracji/ostrzeżeń „NIE zastosowana” najpierw SELECT, potem zapis z datą i godziną odczytu („odczyt flyway_schema_history 2026-10-10”); nie kopiuj stanu z poprzedniej tury; opisuj zastosowanie jako fakt (kto/kiedy nieznane → „przez zewnętrzną przebudowę”), nie jako wykonanie WP-4. Poprawiając stare adnotacje, nie przepisuj historii — dopisuj „[korekta tury N: …]”. Powiązane: [[project_progress_state]].
