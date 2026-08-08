# Changelog

## [1.5.0](https://github.com/gawlik81/contact-center-demo/compare/v1.4.0...v1.5.0) (2026-08-08)


### Features

* **admin:** dodaj pełną stronę Metryki platformy dla Super Admina ([dfdf74e](https://github.com/gawlik81/contact-center-demo/commit/dfdf74ed6bfab568bcec69e7afa8fb1743d24c69))
* **admin:** dodaj pełną stronę Metryki platformy dla Super Admina ([7f040a1](https://github.com/gawlik81/contact-center-demo/commit/7f040a1d665f8fc6db58d8aa621eb4529259306c))


### Bug Fixes

* napraw nawigację po rozdziałach w dokumentacji HTML ([8f2a15a](https://github.com/gawlik81/contact-center-demo/commit/8f2a15a5775bd1e6a7c72171eca36aa581ff3028))

## [1.4.0](https://github.com/gawlik81/contact-center-demo/compare/v1.3.0...v1.4.0) (2026-07-13)


### Features

* **auth:** refaktor ról użytkowników i dodanie roli SUPER_ADMIN ([64a20cd](https://github.com/gawlik81/contact-center-demo/commit/64a20cd94c31409bf7cc112b5362ae06a7a9c793))
* **auth:** refaktor ról użytkowników i dodanie roli SUPER_ADMIN ([f8470a9](https://github.com/gawlik81/contact-center-demo/commit/f8470a95aca906c02e8a7710a3dac7d52ebed044))
* **campaign-import:** dodaj import kontaktów kampanii z pliku JSON ([87cf1e8](https://github.com/gawlik81/contact-center-demo/commit/87cf1e879ab7bab35fac71a5d73448d85dcf4b97))
* **customer-create:** dodaj pola dodatkowe i zgody RODO do modala nowego klienta ([853d1a6](https://github.com/gawlik81/contact-center-demo/commit/853d1a6b543d1f151e884d81cc4e5e700490fb71))
* **customer-import:** dodaj import klientów z pliku JSON ([ab1f685](https://github.com/gawlik81/contact-center-demo/commit/ab1f68537fb53a514196bceb76b9f08bd1e433df))
* **customer:** dodaj externalId (identyfikator zewnętrzny CRM) do domeny klienta ([ae64741](https://github.com/gawlik81/contact-center-demo/commit/ae6474157fd7d017d032ee22b5e30375b0198d45))
* **email-templates:** dodaj externalId i pola dodatkowe do zmiennych szablonów ([cba1dee](https://github.com/gawlik81/contact-center-demo/commit/cba1dee3bed282cbc1c0e89c5e9f0bb82cdf39a4))


### Bug Fixes

* **customer-detail:** napraw czytelność i układ kart w profilu klienta ([10fb430](https://github.com/gawlik81/contact-center-demo/commit/10fb4305572a87320005f10cf512ef273dbd2b42))
* **customer-import:** obsługa wielu telefonów/e-maili, nazwanych pól dodatkowych i zgód RODO ([fa0a0d1](https://github.com/gawlik81/contact-center-demo/commit/fa0a0d191223d9227fef2faf55f6155cd6151046))
* **examples:** zanonimizuj przykładowe dane w campaign.json ([84ddfb2](https://github.com/gawlik81/contact-center-demo/commit/84ddfb2b0cdd9e1fbe6778a727e318cab15fe450))
* **plugins:** dodaj podstawianie {customerExternalId} w CRM URL Launcher ([bb2549a](https://github.com/gawlik81/contact-center-demo/commit/bb2549adf35f6a43229a79becf9aed6318a642dd))

## [1.3.0](https://github.com/gawlik81/contact-center-demo/compare/v1.2.3...v1.3.0) (2026-07-05)


### Features

* **plugins:** dodaj async hooki przez RabbitMQ (BE-104) ([db9acc2](https://github.com/gawlik81/contact-center-demo/commit/db9acc2b16b76920c703b19f95a628f2434040ed))
* **plugins:** dodaj cc-plugin-panel-host — iframe sandboxed (FE-099) ([5b32fdc](https://github.com/gawlik81/contact-center-demo/commit/5b32fdc376d048c7386fb109840db8f153dd8fdb))
* **plugins:** dodaj DbEgressClient i przykładowy plugin zapisu wyniku połączenia do zewnętrznej DB ([a37f22b](https://github.com/gawlik81/contact-center-demo/commit/a37f22b754ca0fe03a35ff5b448e9058db0bf4bf))
* **plugins:** dodaj dispatch i fault containment dla hooków (BE-102) ([bda124d](https://github.com/gawlik81/contact-center-demo/commit/bda124dc0f251bb4b653e5b30a12dc25229cc4ea))
* **plugins:** dodaj GET /api/agent/plugins dla FE-100 ([3500ab4](https://github.com/gawlik81/contact-center-demo/commit/3500ab4e0ee48ed0a08b68f2bac85e811cc23389))
* **plugins:** dodaj instalację pluginu per tenant (BE-100) ([cf4ead8](https://github.com/gawlik81/contact-center-demo/commit/cf4ead82d4448e069b9c637b45f6908d73959111))
* **plugins:** dodaj jądro izolacji wykonania pluginów (BE-101) ([f67c5b7](https://github.com/gawlik81/contact-center-demo/commit/f67c5b7a1dc04fb3192ce157fad87c5a50e9d2bf))
* **plugins:** dodaj moduł plugin-sdk (BE-097) ([2caa64f](https://github.com/gawlik81/contact-center-demo/commit/2caa64f560f33165e9ee909c78cb67b7815f1c8e))
* **plugins:** dodaj panel admina pluginów i kill switch (BE-106) ([60dd9a5](https://github.com/gawlik81/contact-center-demo/commit/60dd9a534936c50429c5c82ad2fc82ea43918d2b))
* **plugins:** dodaj permissions do PluginVersionDto dla FE-098 ([399e28b](https://github.com/gawlik81/contact-center-demo/commit/399e28b019fb8ccbdfc862d6272f0e000f58b78e))
* **plugins:** dodaj przeglądarkę katalogu i UI konfiguracji instalacji ([5beba78](https://github.com/gawlik81/contact-center-demo/commit/5beba785ac95a9f118463dc6f8dd51db72f09b65))
* **plugins:** dodaj serwowanie UI assetów i proxy manual-action (BE-107) ([4e48131](https://github.com/gawlik81/contact-center-demo/commit/4e481314bce25e52b3da16049243450ad7cb2111))
* **plugins:** dodaj statyczny plugin-ui-sdk.js dla FE-099 ([36a872d](https://github.com/gawlik81/contact-center-demo/commit/36a872df0108e568b2e26cb78347bf78997d7126))
* **plugins:** dodaj szyfrowaną konfigurację instalacji pluginu ([f38a192](https://github.com/gawlik81/contact-center-demo/commit/f38a19278783e5d829ea42162a660a9069440456))
* **plugins:** dodaj trwałe logowanie wywołań pluginów (BE-105) ([4df69d3](https://github.com/gawlik81/contact-center-demo/commit/4df69d3ee7a2f1348d7140966d784f3fefe8b15a))
* **plugins:** dodaj UI zarządzania pluginami i rozszerzenia rejestracji (EPIC-28) ([b56a974](https://github.com/gawlik81/contact-center-demo/commit/b56a974c77d52150d7e23553d8c9a24a748ca2da))
* **plugins:** dodaj upload pluginów do globalnego katalogu (BE-099) ([2a0f4c2](https://github.com/gawlik81/contact-center-demo/commit/2a0f4c20b71930133080e46ac7e6e15945eb778f))
* **plugins:** dodaj usuwanie wersji pluginu z katalogu per-tenant ([4eeeb2d](https://github.com/gawlik81/contact-center-demo/commit/4eeeb2d92351d753a5bf67ddcd7bf15aee773a9d))
* **plugins:** dodaj walidację manifestu i bytecode pluginów (BE-098) ([15036c1](https://github.com/gawlik81/contact-center-demo/commit/15036c119d12a1fc5b1d46a94ec069ce648c0148))
* **plugins:** dodaj warstwę danych Angular dla panelu pluginów (FE-097) ([0c9e07e](https://github.com/gawlik81/contact-center-demo/commit/0c9e07ee6a7b5d0335c5f58d7b9325c8f0406be3))
* **plugins:** dziedzicz config przy upgrade pluginu, odbuduj runtime po restarcie ([51c0d45](https://github.com/gawlik81/contact-center-demo/commit/51c0d45de2c806865e53288f21648802d25484b2))
* **plugins:** izolacja katalogu pluginów per-tenant (V078) ([99461a4](https://github.com/gawlik81/contact-center-demo/commit/99461a46f6c7fd113b213e9caad7729585ac5d75))
* **plugins:** podłącz PRE_CONTACT_CONNECT/MANUAL_ACTION do telefonii (BE-103) ([a2765a4](https://github.com/gawlik81/contact-center-demo/commit/a2765a457571fd9b16cc3792acb603c6790b1d30))
* **plugins:** podłącz stronę pluginów do routingu i nawigacji (FE-098) ([51b1085](https://github.com/gawlik81/contact-center-demo/commit/51b10858f6b4f2afaf1b163e4f3b0a560f8e29cd))
* **plugins:** wzbogać DTO pluginów o metadane wyświetlania dla FE-097 ([c80b677](https://github.com/gawlik81/contact-center-demo/commit/c80b6776199be63c57e241d7507cfcd4dcc095a7))
* **plugins:** zaimplementuj warstwę DB dla EPIC-28 (DB-042..045) ([67b1e9c](https://github.com/gawlik81/contact-center-demo/commit/67b1e9c7be7e97f73cbb9d98399f969d0e306b43))
* **plugins:** zamontuj panele pluginów w agent desktop (FE-100) ([7906564](https://github.com/gawlik81/contact-center-demo/commit/7906564ff2ffbff34a2ce387c0bc9b28ed618e0b))
* **plugins:** zaprojektuj EPIC-28 — system pluginów per tenant ([c26e86f](https://github.com/gawlik81/contact-center-demo/commit/c26e86f96138986f1ed2305476fb4e2ddf4c65bf))


### Bug Fixes

* **contact:** umożliw zmianę rozmiaru modala szczegółów kontaktu ([5c79514](https://github.com/gawlik81/contact-center-demo/commit/5c7951431acca6de0f8f5b466eb3d0bfbde0f24a))
* **docs:** napraw linki do Plugin Developer Guide w wygenerowanym HTML ([70f87ba](https://github.com/gawlik81/contact-center-demo/commit/70f87bac86d883dec6a93eb250cb708cd913b9b4))
* **docs:** poszerzenie layoutu Plugin Developer Guide HTML ([ddbf760](https://github.com/gawlik81/contact-center-demo/commit/ddbf760b395503818cfd63b4a5ff3a52900ca196))
* **frontend:** napraw brak customerId dla ad-hoc połączeń wychodzących ([78ae276](https://github.com/gawlik81/contact-center-demo/commit/78ae276b5f166c33ea5d9902bd12c9b08f4feea2))
* **frontend:** napraw testy plugin-panel-host — targetOrigin '*' zamiast window.location.origin ([1c5e5c7](https://github.com/gawlik81/contact-center-demo/commit/1c5e5c7199c15f99f80534f1df574d0bca5d0fb0))
* **frontend:** przekazuj customerId do taba dla połączeń OUTBOUND ([13d633c](https://github.com/gawlik81/contact-center-demo/commit/13d633ccbab5f4d07df16e3d43eeeafde129495c))
* **i18n:** napraw brakujący przecinek w plikach tłumaczeń po dodaniu kluczy deleteCatalog ([2382c17](https://github.com/gawlik81/contact-center-demo/commit/2382c17682d3a4087870fdab31bfe4a6c3d42960))
* **plugins:** napraw brak dispatchu eventów i izolację ClassLoadera; dodaj crm-demo-db ([0de9b52](https://github.com/gawlik81/contact-center-demo/commit/0de9b52df0f737e80325fc18fba86675b390e20a))
* **plugins:** napraw duplikaty CONTACT_ENDED i timezone w customer-callresult-db-sync ([94cd0ba](https://github.com/gawlik81/contact-center-demo/commit/94cd0bae6162ba144db8f85408cb2e3187f11adb))
* **plugins:** napraw kolizję nazw bean'ów PluginInvocationExecutor ([8dae68d](https://github.com/gawlik81/contact-center-demo/commit/8dae68d6de91252b3067ae2807a090786dbdd76d))
* **plugins:** napraw ładowanie SDK w sandboxed iframe (Firefox + Chrome) ([cd92ed8](https://github.com/gawlik81/contact-center-demo/commit/cd92ed80a37278a2545f8c1d64ba7acf21ecb626))
* **plugins:** napraw pusty config/permissions przy każdym wywołaniu pluginu ([a87960b](https://github.com/gawlik81/contact-center-demo/commit/a87960b0ce100cc7d4c081a14d15098e319a91bc))
* **plugins:** napraw start aplikacji po dodaniu modułu plugin-sdk ([007eaba](https://github.com/gawlik81/contact-center-demo/commit/007eaba3dab05d50b724cce16090e282fe69e090))
* **plugins:** napraw zapis validation_errors do kolumny jsonb ([a960ca4](https://github.com/gawlik81/contact-center-demo/commit/a960ca4dc1aa639b7a5f2fc068b707a27d9eb52f))
* **plugins:** usuń zduplikowane pluginVersionId z InstallPluginRequest ([fb521f4](https://github.com/gawlik81/contact-center-demo/commit/fb521f4edffac38ee1c51aef4c8d18d0a6b557af))
* **plugins:** zastosuj uwagi z code review customer-callresult-db-sync ([c4b6f4f](https://github.com/gawlik81/contact-center-demo/commit/c4b6f4f6d9086b712939b1e971c8c2fd6016416f))
* **tests:** napraw 5 failujących testów backendowych ([6f24b08](https://github.com/gawlik81/contact-center-demo/commit/6f24b08db4274f9183d3845224cca1558b31d487))

## [1.2.3](https://github.com/gawlik81/contact-center-demo/compare/v1.2.2...v1.2.3) (2026-06-19)


### Bug Fixes

* **email:** napraw layout modala maila ad hoc po wczytaniu szablonu ([7e633df](https://github.com/gawlik81/contact-center-demo/commit/7e633df4c363fc7b865695ca8bc6b528a73d4c93))
* **email:** podstawiaj prawdziwe dane klienta w podglądzie szablonu maila ad hoc ([46be85b](https://github.com/gawlik81/contact-center-demo/commit/46be85b454543ed44360f5651f5e4ae70d2f820a))

## [1.2.2](https://github.com/gawlik81/contact-center-demo/compare/v1.2.1...v1.2.2) (2026-06-18)


### Bug Fixes

* **email:** zamień textarea na edytor wysiwyg i dodaj resize okna w m… ([ac3396a](https://github.com/gawlik81/contact-center-demo/commit/ac3396ac8d380fc6bba38704c0284017ce832e87))
* **email:** zamień textarea na edytor wysiwyg i dodaj resize okna w mailu ad hoc ([0c387fb](https://github.com/gawlik81/contact-center-demo/commit/0c387fb7b4ffd6870a6b2921b3c2c2349268c70d))

## [1.2.1](https://github.com/gawlik81/contact-center-demo/compare/v1.2.0...v1.2.1) (2026-06-17)


### Bug Fixes

* **test:** usuń flaky mockowanie SCAN w SupervisorMetricsServiceTest ([c98adb2](https://github.com/gawlik81/contact-center-demo/commit/c98adb2a822f99be8117940dd9ac64c7a21193db))
* **test:** usuń flaky mockowanie SCAN w SupervisorMetricsServiceTest ([1a95da8](https://github.com/gawlik81/contact-center-demo/commit/1a95da865a4a7ad0faf3c6814fcfa3124d5a9a79))

## [1.2.0](https://github.com/gawlik81/contact-center-demo/compare/v1.1.0...v1.2.0) (2026-06-17)


### Features

* **email:** dodaj obsługę załączników w mailu ad hoc ([ae73df5](https://github.com/gawlik81/contact-center-demo/commit/ae73df5255b08335dea1904a661b47a61241e731))
* **email:** dodaj obsługę załączników w panelu agenta ([684b4c7](https://github.com/gawlik81/contact-center-demo/commit/684b4c7dba8b0e6a03cd9e29ee2f0495a6f486f6))


### Bug Fixes

* **email:** napraw 4 błędy w obsłudze emaili i załączników ([342b33c](https://github.com/gawlik81/contact-center-demo/commit/342b33c698fd4ca5f38b2f05d53a884e70dce9d5))
* **email:** pobieranie załączników przez JWT zamiast direct link ([50697e1](https://github.com/gawlik81/contact-center-demo/commit/50697e14592a33939be2ef974d6ea0ca303da6b9))
* **email:** utwórz kontakt OUTBOUND dla emaila ad hoc ([38dde05](https://github.com/gawlik81/contact-center-demo/commit/38dde05ac85f3ac33fed46858e3a7bc707d2cdf0))
* **tests:** zaktualizuj testy email po dodaniu EmailAttachmentStorageService i ObjectMapper ([8ebf397](https://github.com/gawlik81/contact-center-demo/commit/8ebf397ca89a44c20a463d2caabad569c610df36))
* **ui:** usuń margin-top: auto z przycisku akcji w szufladzie klienta ([eb58077](https://github.com/gawlik81/contact-center-demo/commit/eb5807724329a95ed01b33847651fcec4c2af8c3))
* **ui:** wyśrodkuj etykietę i ikonę w przycisku Zadzwoń ([a6b9e76](https://github.com/gawlik81/contact-center-demo/commit/a6b9e76e74780f7e60261d51151db3cd1d9be61c))

## [1.1.0](https://github.com/gawlik81/contact-center-demo/compare/v1.0.0...v1.1.0) (2026-06-15)


### Features

* **ai-summary:** aktywuj przycisk AI dopiero po gotowości nagrania i transkrypcji ([cd55802](https://github.com/gawlik81/contact-center-demo/commit/cd55802d03e96cb757b8a4036c2897563103b846))
* **backend:** EPIC-25 Phase 2 — campaign agent assignment (BE-079..BE-085) ([039f48c](https://github.com/gawlik81/contact-center-demo/commit/039f48c5035b84aeac1602184ecaa68d634daabe))
* **campaigns:** walidacja unikalności nazwy kampanii w czasie rzeczywistym ([b8f703a](https://github.com/gawlik81/contact-center-demo/commit/b8f703a399a2b10fbacb8b7c76e5472fb1baa636))
* **contacts:** include TRANSFERRED in agent stats and show agent name on contact views ([8758808](https://github.com/gawlik81/contact-center-demo/commit/8758808ae62cebc09cb72aa43017401f3ac243df))
* **contacts:** unify contact data presentation across all views ([adca73e](https://github.com/gawlik81/contact-center-demo/commit/adca73ecb020b4405647288576a3d863ab065750))
* **db:** EPIC-25 Phase 1 — campaign agent assignment schema (V062, V063) ([e2bc907](https://github.com/gawlik81/contact-center-demo/commit/e2bc907c9abd95c0f49e3d80400125cf1c4c0cc2))
* dodaj logowanie wersji przy starcie i pipeline CI/release ([c943694](https://github.com/gawlik81/contact-center-demo/commit/c943694a74aee86764e68ee1e775d814962aa11a))
* **EPIC-26:** DB migrations + TenantAiConfig entity + AI summarize endpoint (Faza 1+2) ([6c975b2](https://github.com/gawlik81/contact-center-demo/commit/6c975b23ef9b169244c5443b878449c1ccdd8237))
* **EPIC-26:** endpoint POST /api/contacts/{id}/ai-summary (BE-090) ([b9c5fd5](https://github.com/gawlik81/contact-center-demo/commit/b9c5fd5f90f756412974630fdcc245198b9136d6))
* **EPIC-26:** frontend AI summary — serwisy, komponenty, panel supervisora (FE-086–089) ([19801ea](https://github.com/gawlik81/contact-center-demo/commit/19801ea67cbface43aa5e94fe8206628cfb737dd))
* **EPIC-26:** popraw design ai-summary-panel + dodaj kopiowanie do notatki ([4d51f4b](https://github.com/gawlik81/contact-center-demo/commit/4d51f4bd543b492a324df7a1b6a7c5672dd3beca))
* **EPIC-26:** TenantAiConfigController + AiSummaryService (BE-088, BE-089) ([685d003](https://github.com/gawlik81/contact-center-demo/commit/685d003bf1ed87138c1d39abeae0c122617e2306))
* **EPIC-26:** TenantAiConfigService + DTOs + testy jednostkowe (BE-087) ([a7ff122](https://github.com/gawlik81/contact-center-demo/commit/a7ff1224c9f599331289a34f3745f2cbcaed2274))
* **epic-27:** BE-092 CustomDisposition encja, repozytorium i serwis ([e41edbb](https://github.com/gawlik81/contact-center-demo/commit/e41edbb1fc4097137741eaf904643268b2e08026))
* **epic-27:** BE-093 CustomDispositionController + BE-094 available-dispositions endpoint ([38f3808](https://github.com/gawlik81/contact-center-demo/commit/38f380820ad871073bb5a7dd0fa908509b7415a5))
* **epic-27:** DB-040 migracja V069 — tabela custom_disposition ([cf5d682](https://github.com/gawlik81/contact-center-demo/commit/cf5d682b23b87cc9104a1a7bbc23e760d3e989c3))
* **epic-27:** DB-041 migracja V071 — tabele disposition_set i disposition_set_item ([14cec57](https://github.com/gawlik81/contact-center-demo/commit/14cec57db08a2b0d2e1fe405dce5875fa4ada4b4))
* **epic-27:** FE-090 CustomDispositionService i modele Angular ([07322b4](https://github.com/gawlik81/contact-center-demo/commit/07322b4575733892d94f79f47dc050205e1d95e4))
* **epic-27:** FE-091/092/093 panele dyspozycji supervisora i agenta ([c14adef](https://github.com/gawlik81/contact-center-demo/commit/c14adef9d7b9d9a613d6aed6c25d5bf2230e6883))
* **EPIC-27:** zestawy dyspozycji wielokrotnego użytku (DB-041, BE-095/096, FE-094/095/096) ([d96c654](https://github.com/gawlik81/contact-center-demo/commit/d96c654d97159fa6b01f6dddd9090f30b41c4155))
* **EPIC-email:** pełna obsługa szablonów email z predefiniowanymi zmiennymi i UX agenta ([ab51027](https://github.com/gawlik81/contact-center-demo/commit/ab51027bc33fa28a8f8ce03d2fb65bd3210a69cd))
* **frontend:** add agent assignment access to campaign form and list ([4375c90](https://github.com/gawlik81/contact-center-demo/commit/4375c90b4dcc8553b1d474890a66331e80a4061e))
* **frontend:** EPIC-25 Phase 3 — campaign agent assignment UI (FE-081..FE-085) ([e7b973b](https://github.com/gawlik81/contact-center-demo/commit/e7b973b46157af1e0c500ce4fd95e8053c5887a1))
* **frontend:** remove agent assignment button from campaign-info modal ([f155bb3](https://github.com/gawlik81/contact-center-demo/commit/f155bb3db61a1976bfe30731243fa5dbd4a54798))
* **i18n:** Transloco dla komponentów dyspozycji (DE/EN/PL/UK) ([bb0477d](https://github.com/gawlik81/contact-center-demo/commit/bb0477d3211001d44e9094307ecc852fee1feda2))
* **ivr:** persystencja pozycji węzłów w definicji + zoom i fit-to-view w edytorze ([9afdd13](https://github.com/gawlik81/contact-center-demo/commit/9afdd1326d11e3b84023d46fda5549910be2ef1b))
* **ivr:** podpowiadanie i interpolacja zmiennych ${} w promptach węzłów ([d44a22d](https://github.com/gawlik81/contact-center-demo/commit/d44a22d5fc98f369925a9ed3255ad992a07c7d61))
* pokaż liczbę przypisanych agentów w tabeli kolejek ([e4bbe95](https://github.com/gawlik81/contact-center-demo/commit/e4bbe9584ba6942a02c7457439600ad14f3b8c02))
* **softphone:** FE-076 — TransferTargetType, nowe metody transferu do agenta i kolejki ([9e853df](https://github.com/gawlik81/contact-center-demo/commit/9e853df5ab0cfac169947a92bba1c7bb4f4debdd))
* **softphone:** FE-077 — zakładki Telefon/Agent/Kolejka w panelu transferu ([fc3387d](https://github.com/gawlik81/contact-center-demo/commit/fc3387d973c61880bd4f50f1569279abac315267))
* **softphone:** FE-078 — komponent listy agentów do transferu z wyszukiwaniem i statusem ([7ab2e17](https://github.com/gawlik81/contact-center-demo/commit/7ab2e17b206c5ff4e9ae488fe6536b0c543172b5))
* **softphone:** FE-079 — komponent listy kolejek do transferu ([8e2251a](https://github.com/gawlik81/contact-center-demo/commit/8e2251a44a7de4cf36fe40027c99d1ef25ed4c6a))
* **softphone:** FE-080 — integracja transferu agent/kolejka, spinner, dynamiczny nagłówek konsultacji ([edb3eee](https://github.com/gawlik81/contact-center-demo/commit/edb3eee29bf939d0f447ba6373637b0ee1a19470))
* statystyki rekordów w tabeli kampanii (łącznie · zakończone · pozostałe) ([3b87d1e](https://github.com/gawlik81/contact-center-demo/commit/3b87d1e6536723ddcf2465fb22304d758a394066))
* **supervisor:** dodaj KPI "W IVR" i napraw sprzątanie sesji IVR po rozłączeniu ([c25c7ec](https://github.com/gawlik81/contact-center-demo/commit/c25c7ec459e3546f4448e81ca5e63d430d8898e9))
* **telephony:** BE-074 — TransferTargetType, TransferRequest, initiateTransfer w adapterach ([5c4017a](https://github.com/gawlik81/contact-center-demo/commit/5c4017a2f01df22bb170d7e17d6b5fc19363377d))
* **telephony:** BE-075 — GET /api/telephony/transfer/agents — lista agentów do transferu ([70e0674](https://github.com/gawlik81/contact-center-demo/commit/70e0674fa26844e2f8af882ea1de42d54b5e23a3))
* **telephony:** BE-076 — GET /api/telephony/transfer/queues — lista kolejek do transferu ([de97d88](https://github.com/gawlik81/contact-center-demo/commit/de97d88bd438dfc7814f9f873a844a484f54846a))
* **telephony:** BE-077 — POST /api/telephony/calls/{callId}/transfer — ujednolicony transfer ([bcf5960](https://github.com/gawlik81/contact-center-demo/commit/bcf5960dabe130130ad8a7ee51cbf79f5affbbfc))
* **telephony:** BE-078 — POST /api/telephony/calls/{callId}/bridge/{secondCallId} — finalizacja attended ([a482ed8](https://github.com/gawlik81/contact-center-demo/commit/a482ed853273713de7d0a1ffbd41d796748876a1))
* **transfer:** attended transfer – consultation & bridge ([5454d8c](https://github.com/gawlik81/contact-center-demo/commit/5454d8c9df461c80a91e1801e46bd7df61939b86))
* **transfer:** badge TRANSFERRED i powiązania kontaktów w UI ([38b1e3c](https://github.com/gawlik81/contact-center-demo/commit/38b1e3cd10a4ea5c614241d51c1ef786b7ef492c))
* **transfer:** blind transfer do agenta via konferencja + poprawki historii ([6baa11d](https://github.com/gawlik81/contact-center-demo/commit/6baa11da4206679ee798293a6cfcb89577c0d65c))
* **transfer:** obsluga anulowania konsultacji attended transfer ([75244a2](https://github.com/gawlik81/contact-center-demo/commit/75244a2d0305a38a32515614f496110368ae289d))
* **transfer:** powiązanie kontaktów po transferze połączenia ([4f19b33](https://github.com/gawlik81/contact-center-demo/commit/4f19b33afe8e12afb3b1c362e6873735d69c2dd8))
* **twilio:** implementacja initiateTransfer dla AGENT (client:agent-{id}) i QUEUE (TwiML Conference redirect) ([4dec06b](https://github.com/gawlik81/contact-center-demo/commit/4dec06b92638d30b95886e37a1a9f5f22fd1f3bc))


### Bug Fixes

* **agent-break:** walidacja czasu w przeszłości i czytelne komunikaty błędów ([ee59567](https://github.com/gawlik81/contact-center-demo/commit/ee59567e50aa5a818228b33f978a0a2ba3c97555))
* aktywuj przycisk 'Przekaż' dopiero po odebraniu konsultacji ([63930df](https://github.com/gawlik81/contact-center-demo/commit/63930df32209ca5ad952415d9755a14ab50005ad))
* **backend:** dokończ encapsulation pass dla repozytoriów domeny queue ([5274c9d](https://github.com/gawlik81/contact-center-demo/commit/5274c9da9d79d1de471ed3cc2293d6428a77437f))
* **backend:** dokończ refaktor domeny ivr (1993534) - pominięte zmiany pakietów ([3bf2bb0](https://github.com/gawlik81/contact-center-demo/commit/3bf2bb0d658d6ba912a080d93ba3ee5126bc0673))
* **backend:** nie loguj false-positive CrossTenant dla UserServiceImpl.findAuthenticatableUser ([0a32c71](https://github.com/gawlik81/contact-center-demo/commit/0a32c71a4265085ca96a951c3803f3cc3849f09a))
* **backend:** resolveDispositionLabel fallback do custom dyspozycji tenanta ([045ab78](https://github.com/gawlik81/contact-center-demo/commit/045ab7855e53aef60d4b84f5ed73cda24f3c9eb3))
* **backend:** rozwiąż łańcuch cyklicznych zależności beanów (SecurityConfig/ContactService/UserService) ([f8eca71](https://github.com/gawlik81/contact-center-demo/commit/f8eca713469e8fedc1afae4e7ff0e1e554b41c13))
* **calendar:** kampanie manualne bez kolejki widoczne w kalendarzu agenta ([7d6305d](https://github.com/gawlik81/contact-center-demo/commit/7d6305d2b63ba199e5d422a2f920626f9307b9b8))
* **calendar:** odświeżanie kalendarza po dodaniu przerwy i zmianie statusu ([c474df9](https://github.com/gawlik81/contact-center-demo/commit/c474df9a9a2e159e29e9df23b1d22a12722418f8))
* **calendar:** ukryj kampanie terminalne bez zakresu dat ([7c1a871](https://github.com/gawlik81/contact-center-demo/commit/7c1a871678890e97c22d2f594ca41d924dc86e7a))
* campaign assignment badge always showing 'no agents' ([d09df79](https://github.com/gawlik81/contact-center-demo/commit/d09df79edc6a642c305bb0fa22bce0a0abe678be))
* CR EPIC-24 add files ([1d085f9](https://github.com/gawlik81/contact-center-demo/commit/1d085f97183dedc4342d856d9621adfdc2bb0c24))
* **dialer:** naprawy ścieżki połączenia manualnego w kampanii ([646ff9a](https://github.com/gawlik81/contact-center-demo/commit/646ff9a3fae3f3eb44aeb4b5f26b36fc4701f0cf))
* **dialer:** rekordy NO_ANSWER z next_attempt_at&lt;=NOW widoczne w widoku manualnym ([e87aea8](https://github.com/gawlik81/contact-center-demo/commit/e87aea881983f1d7fe2d4fc13389f7e90f5493ae))
* **dialer:** zezwól na ponowne wydzwonienie rekordu NO_ANSWER/FAILED w kampanii manualnej ([b56dba0](https://github.com/gawlik81/contact-center-demo/commit/b56dba0a993caf6cf765aef977b4afb4679a027a))
* **EPIC-26:** drobne poprawki ([497cd7d](https://github.com/gawlik81/contact-center-demo/commit/497cd7dc40314f3c66adffc5e841e27db07e71f8))
* **epic-27:** naprawy z code review zestawów dyspozycji (CR-027) ([4cfd4e1](https://github.com/gawlik81/contact-center-demo/commit/4cfd4e173df0402aee3c2cbe742dc0dbf5022aae))
* **epic-27:** poprawka designu DispositionListEditorComponent — spójność z design systemem ([048eafd](https://github.com/gawlik81/contact-center-demo/commit/048eafd0061380e005b830a40d5ba51c06354a7c))
* **epic-27:** poprawka designu DispositionListEditorComponent — spójność z design systemem ([cc80913](https://github.com/gawlik81/contact-center-demo/commit/cc80913c43ae65486a0992e6f50644bcaa3afb3a))
* **epic-27:** poprawki z code review — RLS, race condition, scope guard, N+1, reaktywność FE ([9ad4134](https://github.com/gawlik81/contact-center-demo/commit/9ad4134eb982c26747b4eaf8a794992b5de2a405))
* **frontend:** add horizontal padding to queue form assignment section ([f562dc4](https://github.com/gawlik81/contact-center-demo/commit/f562dc4c102f267957f643b90319eb55360c6b65))
* **frontend:** aktualizuj customerName w zakładce gdy przyjdzie wzbogacony event CLI ([b40500d](https://github.com/gawlik81/contact-center-demo/commit/b40500dac7a3ded3840273a734641aab67ea7a5b))
* **frontend:** EPIC-25 post-build error fixes ([4ac6788](https://github.com/gawlik81/contact-center-demo/commit/4ac67885b1753aee1d6032990ce72f5217fc07f6))
* **frontend:** mapuj dispositionCode na etykiete w panelu klienta agenta ([1350401](https://github.com/gawlik81/contact-center-demo/commit/1350401a6b41c2e88cb9c81f7030a3f4cb3418d5))
* **frontend:** napraw brak rejestracji Twilio Device po przelogowaniu agenta ([d26bd58](https://github.com/gawlik81/contact-center-demo/commit/d26bd58633270498c82ee9242719b8463df32e59))
* **frontend:** podłącz contact-detail-modal do widoku kontaktów kampanii ([a14fde4](https://github.com/gawlik81/contact-center-demo/commit/a14fde47780fdb6babc05c0ee0fe74396a4aeb2c))
* **frontend:** popraw kierunkowość i zakładkę Kolejka dla połączeń wychodzących ([a88b7f7](https://github.com/gawlik81/contact-center-demo/commit/a88b7f7b5f71e751eebf21782d99dad514582d82))
* **frontend:** poprawne czyszczenie Twilio Device przy wylogowaniu agenta ([b72e28a](https://github.com/gawlik81/contact-center-demo/commit/b72e28a0132ebe0a84657399e2385bec640fb5df))
* **frontend:** przycisk Zapisz w modalach niewidoczny po najechaniu ([2f9eaf3](https://github.com/gawlik81/contact-center-demo/commit/2f9eaf3559d0882f34077d53fea235081e99b442))
* **frontend:** tłumacz status próby kontaktu zamiast wyświetlać enum ([a4f7b73](https://github.com/gawlik81/contact-center-demo/commit/a4f7b738ae7c7573f2967fb5579218e5ab20d408))
* **frontend:** tłumacz systemowe kody dyspozycji (CALLBACK, TRANSFER, ESCALATE) ([c96c831](https://github.com/gawlik81/contact-center-demo/commit/c96c8312c9d7de3349ddb7e7ad6a08553c509c72))
* **frontend:** ujednolicenie kolorów badge agentów — kolejki = kampanie ([8df985d](https://github.com/gawlik81/contact-center-demo/commit/8df985d2b3d2837b99427e789f692046d36e8df3))
* **frontend:** wyświetlaj label dyspozycji zamiast kodu enum ([a19996b](https://github.com/gawlik81/contact-center-demo/commit/a19996b2cb462a417f1a20ed2bb5c6ee54265640))
* **frontend:** wyświetlaj nazwę agenta/kolejki zamiast UUID podczas transferu ([dee66eb](https://github.com/gawlik81/contact-center-demo/commit/dee66ebd4af1824fe1f6c29d88dac8058a4f3007))
* **ivr:** COLLECT_DTMF w trybie TwiML - kompletna kolekcja w jednym żądaniu ([f87aaff](https://github.com/gawlik81/contact-center-demo/commit/f87aaff5d3a69e7b1fdea1c381f95fc48d911c0d))
* **ivr:** czas IVR w historii, audio HANGUP/QUEUE_TRANSFER, przejście PLAY_AUDIO ([a72a7bb](https://github.com/gawlik81/contact-center-demo/commit/a72a7bb9c9274ffd823695aedf2a97930a6d19d4))
* **ivr:** popraw status, audio i czas IVR przy rozłączeniu z menu ([2cd3e0f](https://github.com/gawlik81/contact-center-demo/commit/2cd3e0fb187c33bf1aca3c8f82182a9252747ab4))
* **ivr:** usuń wyjście no-input z COLLECT_DTMF, ujednolić logikę retry/timeout z MENU ([2a76cf7](https://github.com/gawlik81/contact-center-demo/commit/2a76cf732bb836cb38ff52befc2ad0dd974404d3))
* napraw serię bugów w łańcuchu transferów attended/blind ([5cc093f](https://github.com/gawlik81/contact-center-demo/commit/5cc093f5f4d2dfdf4cf392b620df7871f5b314d5))
* naprawa przepływu nagrywania rozmów (RecordingService) ([e2d5afb](https://github.com/gawlik81/contact-center-demo/commit/e2d5afb85984e9c3d41f1a41dd1cc14bc56166ca))
* popraw badge agentów w tabeli kolejek — obsługa flagi allAgents ([909cb3f](https://github.com/gawlik81/contact-center-demo/commit/909cb3f31defcce3f64d245374e4631badf63d91))
* popraw numer klienta w konsultacji i wysyłaj CONSULT_CANCELLED przy no-answer ([f193871](https://github.com/gawlik81/contact-center-demo/commit/f19387197dda9bffc7d294066180078c4a9d01bb))
* poprawki UI i liczenia agentów w kampaniach ([f6f1c05](https://github.com/gawlik81/contact-center-demo/commit/f6f1c059a60db4f22433fabc7be61cea1d0c7bfe))
* race condition ACTIVE→ABANDONED przy zakończeniu konferencji Twilio ([b40de8d](https://github.com/gawlik81/contact-center-demo/commit/b40de8d8c152995c5785aacd1a498f706efa67e9))
* **recording:** propaguj nagranie do wszystkich kontaktow w lancuchu transferu ([23397b9](https://github.com/gawlik81/contact-center-demo/commit/23397b99091e9c31ede5c1525b7746134a145b5a))
* **recording:** propaguj nagranie tylko dla attended transfer, nie blind/queue ([3107e3b](https://github.com/gawlik81/contact-center-demo/commit/3107e3b012c7f20744971553d939b0245b7c28db))
* **softphone:** CR EPIC-24 frontend — attended recovery, i18n, typy statusu, disabled guard, dead code ([dfcf7ba](https://github.com/gawlik81/contact-center-demo/commit/dfcf7ba611fed6aeb46a5b407c4d051bacbc5f20))
* **supervisor:** napraw KPI śr. czas oczekiwania i wprowadź status IVR ([e8e09fa](https://github.com/gawlik81/contact-center-demo/commit/e8e09fac9d2f46df32d592dfe32006f469f78330))
* **telecom:** naprawy bezpieczenstwa i stabilnosci warstwy Twilio (CR-TELECOM IMP+CRIT) ([f111564](https://github.com/gawlik81/contact-center-demo/commit/f11156426b74ccb2279247084399b7f48025750b))
* **telephony:** CR EPIC-24 backend — cross-tenant bridge, E.164, is_deleted, N+1, transakcje, HTTP 501 ([d4950b8](https://github.com/gawlik81/contact-center-demo/commit/d4950b83049157297b5b132fd52a82dcdc20bfec))
* **transfer:** napraw attended transfer dla połączeń wychodzących (OUTBOUND) ([8ac3b5a](https://github.com/gawlik81/contact-center-demo/commit/8ac3b5abd638db8e9714ef6025d3dd4e6823c0db))
* **transfer:** napraw lancuch attended transfer ([5bfbf7c](https://github.com/gawlik81/contact-center-demo/commit/5bfbf7c76d242f2bbc24accda3a60c1b1d7f1680))
* **transfer:** napraw race condition ABANDONED po attended transfer ([52c3d05](https://github.com/gawlik81/contact-center-demo/commit/52c3d05cbe0bdc4d58126c96b59a027a47a4f9bc))
* **transfer:** napraw transfer dla połączeń wychodzących ([87591a4](https://github.com/gawlik81/contact-center-demo/commit/87591a460bc597b900331825ba9f46a69744b6cc))
* **transfer:** napraw zestawianie połączenia po transferze do kolejki ([bd8eead](https://github.com/gawlik81/contact-center-demo/commit/bd8eeadc0825b43303a730c18dbfd9a78de710ba))
* **transfer:** popraw historie kontaktow po attended transfer ([5e5e6ca](https://github.com/gawlik81/contact-center-demo/commit/5e5e6ca475fbe1f066cb677820d30227f5a4b20e))
* **transfer:** popraw historię kontaktu po transferze kolejkowym ([2f9ebcf](https://github.com/gawlik81/contact-center-demo/commit/2f9ebcfaeb5fbd6ff70c1fc4befa0ee0a98ad4c9))
* **transfer:** popraw historie kontaktu przy anulowaniu konsultacji ([9d9cae5](https://github.com/gawlik81/contact-center-demo/commit/9d9cae5b2aad1f30b5c45966278b36ad07ac3c53))
* **transfer:** popraw wyświetlanie historii kontaktu po transferze ([483d8e9](https://github.com/gawlik81/contact-center-demo/commit/483d8e93025c7fdd57e289c3fcb386be282ddae1))
* **transfer:** Prettier + korekty formatowania komponentów transferu ([661b5bd](https://github.com/gawlik81/contact-center-demo/commit/661b5bd32709610546e0760e11f6c701b11fc2e8))
* **transfer:** usuń AND c.is_deleted z contact — kolumna nie istnieje w tej tabeli ([0ab5c2d](https://github.com/gawlik81/contact-center-demo/commit/0ab5c2d500810221c3ab109a59ddf2f4adb09546))
* **twilio:** użyj session.getCallId() zamiast contactId przy wywołaniach Twilio API (transfer, bridge, hangup) ([78b7685](https://github.com/gawlik81/contact-center-demo/commit/78b7685dcd0daca0a0cf315dadf4089a1aea7629))
* **websocket:** disconnect WS on supervisor logout to unblock agent reconnect ([6bdf0be](https://github.com/gawlik81/contact-center-demo/commit/6bdf0beac085b1ee877395a6c788f9e6ee86cbcd))
* wyświetlaj imię klienta w zakładce zamiast numeru telefonu ([9684bba](https://github.com/gawlik81/contact-center-demo/commit/9684bba401030417e8d74c88b9013a58a1c44349))
* wyświetlaj nazwę agenta/kolejki zamiast UUID podczas transferu + napraw ContactServiceTest ([1f587c9](https://github.com/gawlik81/contact-center-demo/commit/1f587c92376df8732bb1d9320c2a1cc644d8e59d))
