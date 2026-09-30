---
name: feedback-recording-delete-from-s3-swallows-errors
description: RecordingService#deleteFromS3 połyka S3Exception (brak sygnału porażki) — nie używać go tam, gdzie kolejność „S3 przed wierszem" wymaga wiedzy o niepowodzeniu
metadata:
  type: feedback
---

`RecordingServiceImpl#deleteFromS3` (:307–322) łapie `S3Exception`, loguje ERROR i **nie rzuca ani nie zwraca wyniku**. Rzucają tylko błędy klienta (`SdkClientException`: sieć/timeout), bo to nie podklasa `S3Exception`.

**Why:** ustalone w BE-124 (EPIC-30). Konsekwencje w istniejącym kodzie: `RecordingRetentionJob#deleteRecording` po „udanym" (połkniętym) błędzie S3 i tak wykonuje `clearRecordingUrl` → obiekt osiera; `GdprServiceImpl#deleteCustomerRecordingsFromS3` liczy `failed`, który dla `S3Exception` nie rośnie.

**How to apply:** gdy usuwanie z S3 ma decydować o dalszych krokach (usuń wiersz tylko po sukcesie, zbierz listę niepowodzeń do audytu — BE-125/126/129/131), użyj metody zwracającej wynik/rzucającej (np. `EmailAttachmentStorageService#delete` z BE-125), nie `RecordingService#deleteFromS3`. Przy naprawie samego `RecordingRetentionJob` zmień kontrakt `deleteFromS3` świadomie (zmienia zachowanie jobu). Powiązane: [[project-epic30-be124-message-retention-adr]].
