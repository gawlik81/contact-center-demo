---
name: feedback_partitioned_table_before_trigger_routing
description: BEFORE ROW trigger na tabeli partycjonowanej odpala się PO routingu — nie może uzupełniać klucza partycji (błąd "moving row to another partition"); do uzupełnienia klucza używaj DEFAULT kolumny (liczony przed routingiem)
metadata:
  type: feedback
---

Trigger `BEFORE INSERT` zdefiniowany na tabeli RANGE-partycjonowanej (PG 16.13, zweryfikowane na scratch w DB-067) odpala się na **partycji, do której wiersz został już wyroutowany**. Gdy klucz partycjonowania jest NULL, routing trafia do partycji DEFAULT (albo błąd, gdy brak DEFAULT), a trigger ustawiający klucz kończy się:
`ERROR: moving row to another partition during a BEFORE FOR EACH ROW trigger is not supported`.

**Why:** Ticket DB-067 zakładał trigger BEFORE INSERT jako „sieć bezpieczeństwa" dla starych instancji aplikacji (bez kolumny w INSERT). Na tabeli partycjonowanej ta sieć nie działa — stara instancja dostaje błąd zamiast cichego uzupełnienia.

**How to apply:**
- Uzupełnianie klucza partycjonowania przy INSERT bez jawnej wartości: `ALTER COLUMN ... SET DEFAULT now()` (DEFAULT jest liczony PRZED routingiem). Ustawiać PO backfillu (nie przed), żeby ADD COLUMN nie przepisał tabeli i nie nadał czasu transakcji wszystkim wierszom.
- Wartość DEFAULT, która nie jest funkcją innych kolumn, nie odtworzy `COALESCE(...)` — świadomie zaakceptować różnicę i udokumentować.
- Trigger `BEFORE UPDATE OF <klucz>` (ochrona niemodyfikowalności) na partycjonowanej tabeli JEST poprawny — zgłasza wyjątek przed przeniesieniem wiersza. Użyj `UPDATE OF` kolumny, nie `BEFORE UPDATE`, żeby nie odpalać go na gorącej ścieżce zapisu statusu.
- Zawsze testuj routing `tableoid` po NULL i po jawnej wartości (wzorzec z DB-065/DB-067).

Powiązane: [[project_db067_email_message_partitioning]], [[project_db065_social_message_partitioning]].
