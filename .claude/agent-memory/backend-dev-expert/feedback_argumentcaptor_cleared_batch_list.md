---
name: ArgumentCaptor + lista czyszczona po wywołaniu (batch.clear())
description: ArgumentCaptor.getValue() po teście widzi pustą listę, gdy produkcyjny kod woła batch.clear() na tej samej referencji zaraz po przekazaniu jej do mocka
type: feedback
---

Gdy produkcyjna metoda przekazuje mutowalną kolekcję (np. `List<Object[]> batch`) do zamockowanej
zależności, a NASTĘPNIE czyści tę samą referencję (`batch.clear()`) – co jest częstym wzorcem przy
batch-insertach z podziałem na chunki (flush → insert → clear → kontynuuj pętlę) – zwykły
`ArgumentCaptor.capture()` + odczyt `captor.getValue()` PO zakończeniu testu zwróci PUSTĄ listę.
Mockito nie kopiuje argumentów, tylko przechowuje referencję.

**Why:** Odkryte przy pisaniu testu `validJsonImport_insertsRows_completesJob` dla
`CampaignImportServiceImpl.doJsonImport()` (BE-023 rozszerzenie JSON) – `flushBatch()` woła
`campaignContactRepository.batchInsert(tenantId, campaignId, batch, skipDuplicates)`, a zaraz
potem `batch.clear()`. Test z klasycznym `ArgumentCaptor` failował z "Expected size: 2 but was: 0",
mimo że logi potwierdzały poprawny import (total=2, imported=2).

**How to apply:** Gdy testowana metoda przekazuje mutowalną listę do mocka i later ją czyści –
NIE używaj zwykłego `ArgumentCaptor.getValue()` po teście. Zamiast tego przechwyć DEFENSYWNĄ KOPIĘ
w momencie wywołania przez `thenAnswer`:
```java
List<List<Object[]>> capturedBatches = new ArrayList<>();
when(repo.batchInsert(eq(tenantId), eq(campaignId), anyList(), eq(true)))
        .thenAnswer(invocation -> {
            List<Object[]> batchArg = invocation.getArgument(2);
            capturedBatches.add(new ArrayList<>(batchArg)); // kopia TERAZ, zanim caller ją wyczyści
            return 2;
        });
```
Dotyczy to również `CustomerImportServiceImpl` – tam problem nie występuje, bo `flushBatch`
buduje tam NOWĄ listę (`insertParams`) przed wywołaniem `jdbcTemplate.batchUpdate(sql, insertParams)`,
więc oryginalny `batch.clear()` w caller nie wpływa na przechwycony argument. Przy nowym kodzie
sprawdź, czy przekazywana do mocka lista jest tą samą referencją, którą produkcyjny kod czyści.
