---
name: project-db-cr-v134-comment-fix
description: V134 (2026-10-10) korekta COMMENT ON INDEX idx_scheduled_callback_due po CR V126-V133; pulapki testowe
metadata:
  type: project
---

V134 = sam COMMENT ON INDEX (guard to_regclass). V129 komentarz byl falszywy (findDueCallbacks uzywa idx_callback_scheduled).
Test Db057: testy idempotencji re-wykonuja V129 i NADPISUJA komentarz V134 -> test komentarza V134 musi isc przed idempotencja (Order 12 vs 14), a idempotencja wykonuje tez V134.
Brak GUC pod app_user na niepustej tabeli archiwum: swieze polaczenie = cichy 0; po set_config('') = SQLSTATE 22P02.
Testy migracji Db057/058/076/ContactsDw sa sekwencyjne (@Order + stan statyczny) - nie uruchamiac pojedynczych metod.
