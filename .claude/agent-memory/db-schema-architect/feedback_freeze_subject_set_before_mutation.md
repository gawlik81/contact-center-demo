---
name: feedback_freeze_subject_set_before_mutation
description: Funkcja PL/pgSQL, która najpierw WYZNACZA zbiór wierszy do zmiany (na podstawie danych, które sama potem zeruje), musi zamrozić ten zbiór RAZ na starcie — inaczej kolejne kroki widzą kurczący się zbiór ("progressive narrowing")
metadata:
  type: feedback
---

Gdy funkcja SQL/PL/pgSQL (a) wyznacza zbiór powiązanych wierszy na podstawie identyfikatora (np. telefonu/e-maila klienta odczytanego z `customer.phone`/`email`) i (b) w KOLEJNYCH krokach TEJ SAMEJ funkcji zeruje właśnie te kolumny źródłowe — każde PONOWNE wywołanie funkcji wyznaczającej zbiór (nawet w tej samej transakcji) zwróci coraz MNIEJSZY zbiór, bo dopasowanie identyfikatorowe znika w miarę zerowania danych.

**Why:** przy DB-062 (`anonymize_customer` V096) zbiór podmiotu (`fn_customer_subject_ids`) zależy od `customer.phone`/`customer.email` (do zbudowania listy znormalizowanych identyfikatorów) — a funkcja PÓŹNIEJ zeruje właśnie te kolumny (`customer.phone = '[]'` w ostatnim kroku). Gdyby funkcja pomocnicza była wywoływana OSOBNO przy każdym UPDATE/DELETE (wzorem "policz i zaraz zrób"), późniejsze wywołania (dla `email_message`, `social_message` itd.) widziałyby PUSTĄ listę identyfikatorów i pomijałyby rekordy dopasowane wyłącznie po identyfikatorze — cichy błąd niedomiaru, trudny do wykrycia bez testu na fixturze z realnym dopasowaniem identyfikatorowym.

**How to apply:** w takiej funkcji wywołaj funkcję wyznaczającą zbiór DOKŁADNIE RAZ, na samym początku, PRZED jakąkolwiek mutacją — zapisz wynik w strukturze, która przetrwa do końca funkcji (tablice PL/pgSQL `array_agg`/`unnest` w kolejnych `FROM (SELECT unnest(...) ...) subj` per statement, albo tabela tymczasowa) i reużywaj TĘ SAMĄ zamrożoną wersję we wszystkich kolejnych krokach (liczenie dry-run, UPDATE, DELETE). Dotyczy każdej przyszłej funkcji GDPR/retencji o podobnym kształcie (np. BE-129, przyszłe rozszerzenia D9). Test wykrywający regres: porównaj wynik trybu podglądu (`p_dry_run=TRUE`, czysto odczytowy) z faktycznie zmienionymi wierszami trybu rzeczywistego — jeśli się rozjeżdżają, to prawdopodobnie symptom tego dokładnie problemu.
