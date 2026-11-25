---
name: feedback-explain-test-tie-on-empty-partitions
description: Test EXPLAIN na pustych tabelach to remis kosztow (np. V151 vs stary indeks 0.12..8.15); utwardzaj realistycznymi danymi + ANALYZE w transakcji z rollbackiem i kontrola negatywna
metadata:
  type: feedback
---

`ProgressiveDialerCampaignLockIntegrationTest#agentFreeProbeUsesV151Indexes` (BE-248): na pustych partycjach kazdy kandydat ma identyczny wyswietlany koszt, indeks V151 wygrywa o ulamek (kwalifikator statusu wynika z predykatu indeksu), a stary asercja "plan zawiera KTORYKOLWIEK indeks potomny" zalezala od remisu i od stanu wspoldzielonej bazy (statystyki, autovacuum). Nie udalo sie odtworzyc faktycznego padniecia (raz w pelnym przebiegu, bez logu planu) - przyczyna wnioskowana, nie zaobserwowana.

**Why:** remis kosztow + wspolna baza Testcontainers per JVM; kazdy ANALYZE/autovacuum/wyciek partycji z innej klasy moze go przestawic.

**How to apply:** test planu = (1) dane realistyczne w TRANSAKCJI z rollbackiem (zadnego zasmiecania wspolnej bazy), (2) `ANALYZE` w tej samej transakcji (pg_statistic tez sie cofa), (3) asercja na partycji, ktora faktycznie trzyma wiersze (`tableoid::regclass`) i nazwie indeksu potomnego z pg_inherits, (4) kontrola negatywna: `DROP INDEX` w tej samej transakcji i plan ma stracic indeks i podrozec wielokrotnie. Zmierzone: z V151 22 vs bez 243 (10k wierszy). Zweryfikowane RepeatedTest(25) zielone.
