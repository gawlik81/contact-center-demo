# Changelog

## [1.7.0](https://github.com/gawlik81/contact-center-demo/compare/v1.6.0...v1.7.0) (2026-10-31)


### Features

* **agent:** baner bledu telefonii z ponowieniem na pulpicie agenta (FE-145) ([22d4d24](https://github.com/gawlik81/contact-center-demo/commit/22d4d242ca4f3812ea16253eed78058e8722d13a))
* **agent:** bezpieczne zamykanie kart i znaczniki stanu (FE-132, FE-133) ([a80fb05](https://github.com/gawlik81/contact-center-demo/commit/a80fb053c86326d399a93f2ff9ec1b7bda1443c8))
* **agent:** elastyczny uklad pulpitu 1366x768 i skala breakpointow (FE-123) ([ae5654f](https://github.com/gawlik81/contact-center-demo/commit/ae5654fcc2b7fdd5b17165d27a7b54417c8e6735))
* **agent:** i18n, domkniecia hardeningu i listy z klawiatury (EPIC-32 FE-119, FE-120, FE-126) ([7e15637](https://github.com/gawlik81/contact-center-demo/commit/7e156376c07bf3e639b3449bea082d800059a686))
* **agent:** kontrast tokenow i landmarki z fokusem (EPIC-32 FE-115, FE-117) ([005bffc](https://github.com/gawlik81/contact-center-demo/commit/005bffc89735ce50c1a619d59f0883ab7edebf3c))
* **agent:** niemodalny stan ACW w karcie i baner dyspozycji (FE-122) ([c405f24](https://github.com/gawlik81/contact-center-demo/commit/c405f24d971c2edc1bd439d8e4448c0aa6890619))
* **agent:** palety na tokeny i skala typografii (EPIC-32 FE-116, FE-118) ([1eec4f7](https://github.com/gawlik81/contact-center-demo/commit/1eec4f72e613a4d52aa9bdb2565d78e71a3f2ede))
* **agent:** szkice per kontakt, status agenta w pasku, social tylko do odczytu ([eaed523](https://github.com/gawlik81/contact-center-demo/commit/eaed5234b1cee36d9dfc90ab7085fca3c8819dfe))
* **customers:** podgląd D9 i unifikacja anonimizacji RODO (FE-112) ([0437a2f](https://github.com/gawlik81/contact-center-demo/commit/0437a2f1f1e9646823b3c70e2fb3e2f8fb7901aa))
* **db:** dodaj indeksy pod sweep wiadomości osieroconych (DB-059, V097) ([6b4797c](https://github.com/gawlik81/contact-center-demo/commit/6b4797cde1c796369e7ead32fe8c24b646aa3c30))
* **db:** okres archiwizacji kontaktow kampanii per tenant (DB-084, V136-V138) ([bc09af7](https://github.com/gawlik81/contact-center-demo/commit/bc09af77d7028dbaaff9f2de7a3497f71010816b))
* **db:** porzadki indeksow scheduled_callback, agent_group_member, archiwum (DB-057) ([6110fdd](https://github.com/gawlik81/contact-center-demo/commit/6110fdde80749b773d4fcc0d4822704c0ea7dcbc))
* **db:** usun nieuzywane widoki materializowane (DB-058) ([cbbe74a](https://github.com/gawlik81/contact-center-demo/commit/cbbe74aae71e43469dc7526676886cb17351d6a5))
* **db:** uzgodnij scheduled_job z wykonawcami Java i doc retencji (DB-076, DB-077) ([f4c8228](https://github.com/gawlik81/contact-center-demo/commit/f4c822895e30f744786c633014b1e6f5f16c35bd))
* **email:** partycjonowanie email_message i klucz złożony EmailMessage (DB-067, BE-134) ([c3ca6e6](https://github.com/gawlik81/contact-center-demo/commit/c3ca6e6e831bf8594e20283a968f4f40305d95dd))
* **email:** sprzątaj porzucone załączniki pending w S3 (BE-131) ([5fa469c](https://github.com/gawlik81/contact-center-demo/commit/5fa469c042748aac062287e0a1546f4e82ed4545))
* **gdpr:** podepnij anonymize_customer/export_customer_data do Javy (BE-129) ([8eff7ad](https://github.com/gawlik81/contact-center-demo/commit/8eff7adac5f5f4b1f0c9e0b1e51f97974cc87d67))
* **plugins:** dodaj overwrite przy reupload tej samej wersji (EPIC-28) ([2b1e210](https://github.com/gawlik81/contact-center-demo/commit/2b1e21007eb67d1232918deb23d7f5769638133a))
* **plugins:** przełącznik overwrite przy wgrywaniu pluginu (EPIC-28) ([a34a107](https://github.com/gawlik81/contact-center-demo/commit/a34a107db0c348bce7028b768a21176bb048b11d))
* **retention:** api okresu archiwizacji kontaktow kampanii per tenant (BE-151) ([760fb26](https://github.com/gawlik81/contact-center-demo/commit/760fb263860180b13b1158f5cf1990463b9db637))
* **retention:** horyzont platformowy audit_log/plugin_invocation_log (BE-123) ([a0071d5](https://github.com/gawlik81/contact-center-demo/commit/a0071d53b334d26adf1cc5321b12652e5a00fa86))
* **retention:** job archiwizacji kampanii i batch purge archiwum (BE-120, DB-056) ([bbaaade](https://github.com/gawlik81/contact-center-demo/commit/bbaaade3a3aae5619444c0cf1947af2fc82ec6a2))
* **retention:** job czyszczenia refresh tokenow i opisy retencji w UI (BE-122, FE-110) ([2a7ec16](https://github.com/gawlik81/contact-center-demo/commit/2a7ec164784be6a76b42ed38e120ad1010f632d8))
* **retention:** licz wiadomości w eligibleRowCount dashboardu retencji (BE-128) ([d5c0143](https://github.com/gawlik81/contact-center-demo/commit/d5c014301bc1814125b90a0eb5cbc4d103ccad6f))
* **retention:** maskuj PII w audit_log przy anonimizacji klienta (BE-142) ([9e28ebc](https://github.com/gawlik81/contact-center-demo/commit/9e28ebc3e568bb951c63744d8ba114ec0d5796d2))
* **retention:** partycjonuj social_message RANGE po sent_at (DB-065) ([5de7756](https://github.com/gawlik81/contact-center-demo/commit/5de7756a819e994fef087398bc0bbeaecb18b2d0))
* **retention:** petla batchowa purge archiwum i drop contacts_dw.remote_address (BE-121, DB-078) ([4eb54ec](https://github.com/gawlik81/contact-center-demo/commit/4eb54ec84366962ba65a5bce7809cbb97b779612))
* **retention:** podepnij email_message, REVOKE na partycjach, włącz delete-messages ([b4def99](https://github.com/gawlik81/contact-center-demo/commit/b4def99bc4a0738947ebfffa8bc401e1b5d40a32))
* **retention:** podepnij social_message do maszynerii partycji/retencji (BE-133) ([1f00054](https://github.com/gawlik81/contact-center-demo/commit/1f000541581a5cc4853490035cbd144d639e2852))
* **retention:** podepnij usuwanie wiadomości do purge kontaktów (BE-126) ([58b5f82](https://github.com/gawlik81/contact-center-demo/commit/58b5f82dd30a46e1b69cf35d73990f7f7a581e99))
* **retention:** pole okresu archiwizacji kontaktow kampanii w GUI (FE-113) ([81c5561](https://github.com/gawlik81/contact-center-demo/commit/81c5561ec6b2bfb9868cc63a2cc72420beba16b6))
* **retention:** purge wiadomości osieroconych (BE-127) ([bdd716a](https://github.com/gawlik81/contact-center-demo/commit/bdd716a8ac157255d005b01653c5b944ae1c66f1))
* **retention:** trwaly slad awarii zadan cyklicznych (DB-082, BE-147) ([7bea097](https://github.com/gawlik81/contact-center-demo/commit/7bea097b4996ed0fdadf29c426657ba4bf13fb7b))
* **retention:** usuwanie wiadomości e-mail/social po contact_id wraz z S3 (BE-125) ([8c1d710](https://github.com/gawlik81/contact-center-demo/commit/8c1d71079da5708f73feb5da42aa35a3a45bf5ad))
* **rls:** domknij RLS pozostalych tabel tenantowych (DB-074, BE-138, DB-081) ([7810048](https://github.com/gawlik81/contact-center-demo/commit/78100483ed0416a263f125429b63d41a32e750ad))
* **rls:** pelne RLS campaign_contact/campaign_contact_archive (D7 Opcja 1, DB-072/DB-073) ([fce75f9](https://github.com/gawlik81/contact-center-demo/commit/fce75f91a1502cdc7ac3fb3bffd15141d25387d8))
* **routing:** kontakty social trafiaja do agenta, czas pracy KPI od zalogowania ([f947842](https://github.com/gawlik81/contact-center-demo/commit/f94784208ad94b691738696d4051b1404082fc1d))
* **routing:** zwrot kontaktu async do kolejki i wykluczenie zwracajacego agenta (BE-160) ([20ce2f4](https://github.com/gawlik81/contact-center-demo/commit/20ce2f48fac18fff95b18b16773f53bfdacc4461))
* **security:** wzmocnij RLS email_message/social_message na ALL+WITH CHECK+FORCE (DB-064) ([d855b1f](https://github.com/gawlik81/contact-center-demo/commit/d855b1f489c561b1d8505a5562ff9d9cf096f2dd))
* **social:** dodaj integrację WhatsApp Business (ręczne podłączenie … ([aa8a799](https://github.com/gawlik81/contact-center-demo/commit/aa8a799ffc45b4d6d799852c3cff77e3f7399916))
* **social:** dodaj integrację WhatsApp Business (ręczne podłączenie + realna wysyłka) ([67943eb](https://github.com/gawlik81/contact-center-demo/commit/67943ebdb8ea2a9ab0391106acbac76ae3c61d14))
* **social:** migruj SocialMessage na klucz złożony (messageId, sentAt) (BE-132) ([4a3fc83](https://github.com/gawlik81/contact-center-demo/commit/4a3fc83b4d8c9f3ba8679ca4dd2c986ec6e90ce0))


### Bug Fixes

* **admin:** podpowiedzi umiejetnosci w formularzu uzytkownika i i18n literalow pl (FE-127, FE-128) ([c41bbef](https://github.com/gawlik81/contact-center-demo/commit/c41bbefe63f9c2cef575512b668c70704f806920))
* **agent:** domkniecia audytu ux agenta - kontrast, cele 24px, region live (FE-125) ([9eac998](https://github.com/gawlik81/contact-center-demo/commit/9eac998303ce6330f81a9a25d436624aa71f7416))
* **agent:** moment dzwonienia - widoczny baner, skroty klawiszowe, Esc w modalach ([322db0c](https://github.com/gawlik81/contact-center-demo/commit/322db0c16ba2ec1a0c357bb5612aab78389925a1))
* **agent:** reaktywne tlumaczenie etykiety kolejki i domkniecia kpi navbara (FE-143) ([a019f56](https://github.com/gawlik81/contact-center-demo/commit/a019f569ec2c7aeecf749d3e07a3adcd0d27ad71))
* **audit:** popraw entity_id dla akcji CREATED w AuditAspect (BE-146) ([04c40d7](https://github.com/gawlik81/contact-center-demo/commit/04c40d7e13402a3dd29f9ca385520726c7a3d4c5))
* **ci:** pomiń test MinIO gdy obraz niedostępny w rejestrze (BE-144) ([d3038c3](https://github.com/gawlik81/contact-center-demo/commit/d3038c3857dbc4fdeb3555b824d8e0d174fa6244))
* **db:** napraw export_customer_data, dodaj funkcję pomocniczą reguły D9 (DB-061, V095) ([deec652](https://github.com/gawlik81/contact-center-demo/commit/deec652990139ab3f7a6446fc23d778282ad83fd))
* **db:** purge archiwum kontaktow - archived_at w kluczu usuwania i guard tenanta (DB-087, V143) ([15cf66d](https://github.com/gawlik81/contact-center-demo/commit/15cf66df00f45c9389d441d63a978572b5caac1b))
* **db:** rozszerz anonymize_customer o pełen zakres RODO Art. 17 (DB-062, V096) ([4da4c68](https://github.com/gawlik81/contact-center-demo/commit/4da4c684f59f4a0a04b7fc28098140a0cd4efe29))
* **db:** trigger ended_at na insert i bezpieczny delete w archiwizacji kontaktow (DB-085, DB-086) ([fa9820a](https://github.com/gawlik81/contact-center-demo/commit/fa9820a5a97c4d6b9e13892fe91e8bd351ba9978))
* **db:** usuń zduplikowane indeksy z tabeli contact (DB-055, V093) ([09f5623](https://github.com/gawlik81/contact-center-demo/commit/09f5623e43b865351f010ed4900e36742964918f))
* **db:** usuń zduplikowane indeksy z tabeli contact (DB-055, V093) ([bc0c520](https://github.com/gawlik81/contact-center-demo/commit/bc0c5207eba668ffeef01e674e5cf7bbf5c8fb8e))
* **db:** zawężenie fn_contact_ref_integrity - UPDATE bez zmiany referencji (DB-079, V094) ([e9d6d65](https://github.com/gawlik81/contact-center-demo/commit/e9d6d658923ef642f4a5a61da333ceb962d658d7))
* **email:** waliduj prefiks tenanta dla s3Key w wysyłce i pobieraniu załącznika (BE-143) ([dc385e8](https://github.com/gawlik81/contact-center-demo/commit/dc385e85c884abb5dbf0148a115b9797eabad685))
* **navbar:** czas pracy w formacie h:mm i jednostki dla czytnika ekranu (FE-129) ([20ab715](https://github.com/gawlik81/contact-center-demo/commit/20ab7150b31afefe3b2fbe621b785b712de14186))
* **plugins:** wymuś unikalny klucz S3 przy overwrite wersji pluginu ([28b8a58](https://github.com/gawlik81/contact-center-demo/commit/28b8a58b2b26a030e04631b0368ab5450908e36e))
* **retention:** dialog okresu archiwizacji pokazuje przyrost, dostepnosc i wyscig podgladu (FE-113) ([67c85f8](https://github.com/gawlik81/contact-center-demo/commit/67c85f8c2db0dbe098a1698cd7dc88fdc1be3cc2))
* **retention:** nie usuwaj (DROP TABLE) niepustej partycji tabel per-tenant (BE-145) ([ba23c70](https://github.com/gawlik81/contact-center-demo/commit/ba23c70e2d6273f62424a294cb0424d34f32aa47))
* **retention:** podglad okresu archiwizacji uzywa zegara z created_at jak funkcja sql ([a0ad887](https://github.com/gawlik81/contact-center-demo/commit/a0ad8870ea3c2eb860545054f46e9a75048a5fc3))
* **retention:** purge z flaga truncated, V134, poprawki i18n po review (BE-121, FE-110) ([f09ab9e](https://github.com/gawlik81/contact-center-demo/commit/f09ab9ecfed1b8f549cb6bd9d3835c2a3134991b))
* **retention:** test podgladu archiwizacji odporny na wyjatek i przypadek przeciwny (BE-152) ([3e48367](https://github.com/gawlik81/contact-center-demo/commit/3e4836711a038fdbcc3ac63bd94137338282d58e))
* **retention:** tlumaczona fraza potwierdzenia i zawijanie tabeli polityk (FE-110) ([23ac56b](https://github.com/gawlik81/contact-center-demo/commit/23ac56b1b56a08cfb2f8af83caa68b2a0f936d11))
* **supervisor:** tokeny systemu zamiast niezdefiniowanych --color-* w zestawach dyspozycji (FE-141) ([c4129f9](https://github.com/gawlik81/contact-center-demo/commit/c4129f94ce9d9cde1ecda77738996ddedd5fb65b))
* **test:** deterministyczny plan DELETE w EmailMessagePurgeIntegratio… ([d282700](https://github.com/gawlik81/contact-center-demo/commit/d2827002b2f35db746ff50eb3d53f39e2f039eec))
* **test:** deterministyczny plan DELETE w EmailMessagePurgeIntegrationTest ([23cc5ff](https://github.com/gawlik81/contact-center-demo/commit/23cc5ff49d2c391e20da44a4d314b73e1137322f))
* **test:** rozwiąż kolizję nazw CustomerLikeResult/ContactLikeResult po merge BE-142+BE-146 ([700610f](https://github.com/gawlik81/contact-center-demo/commit/700610ff3471eafed92c24f8e2a7c008cc8ef494))


### Performance Improvements

* **agent:** budzet stylow pulpitu, tokeny zamiast fallbackow i plaski ruch (FE-124) ([592bedf](https://github.com/gawlik81/contact-center-demo/commit/592bedf23cc1659a916a61a6ca2a80ed504da3c4))
* **db:** purge archiwum kontaktow usuwa po kluczu glownym zamiast seq scan (DB-056, V142) ([9c800c8](https://github.com/gawlik81/contact-center-demo/commit/9c800c814bcad885fc53a29a71c23b0b73856381))

## [1.6.0](https://github.com/gawlik81/contact-center-demo/compare/v1.5.0...v1.6.0) (2026-08-23)


### Features

* **backend:** dodaj PartitionMaintenanceJob i PartitionReclaimJob (BE-114, BE-115) ([24de535](https://github.com/gawlik81/contact-center-demo/commit/24de53536d91d792447b4cd3e32fdde59c72969c))
* **backend:** dodaj RetentionController, REST API zarządzania retencją (BE-118) ([1a1af52](https://github.com/gawlik81/contact-center-demo/commit/1a1af520477595304dce6857c48d1a762935e59a))
* **backend:** dodaj RetentionEvaluationJob, liczenie danych do usunięcia (BE-112) ([a3888ec](https://github.com/gawlik81/contact-center-demo/commit/a3888ec5458ac4b59b73be6b3366640699684085))
* **backend:** dodaj RetentionPolicyService, CRUD polityk retencji per tenant (BE-111) ([80d82ce](https://github.com/gawlik81/contact-center-demo/commit/80d82ce61f131b486831564d83e27335d5cfea01))
* **backend:** dodaj RetentionPurgeService, silnik usuwania per-tenant (BE-113) ([a80a07e](https://github.com/gawlik81/contact-center-demo/commit/a80a07ee8714433634073147e640c17e269899f5))
* **backend:** integracja CAMPAIGN_DATA z RetentionPurgeService (BE-119) ([fb327f3](https://github.com/gawlik81/contact-center-demo/commit/fb327f3af2a1f6f0450cd82799da3d447a1aaae0))
* **backend:** migruj ContactEvent/ContactAiSummary na klucz złożony @IdClass (BE-117) ([acaed59](https://github.com/gawlik81/contact-center-demo/commit/acaed59b53a19bbb6d158b291b83e27dc9af258b))
* **backend:** per-tenant retencja nagrań, usuń recording_retention_days z tenant.config (BE-116) ([0f86268](https://github.com/gawlik81/contact-center-demo/commit/0f86268732a0fc4029046e7480c6900eb42f2ce6))
* **db:** dodaj indeksy (tenant_id, kolumna_czasowa) pod purge per-tenant (DB-053, V089) ([c5efbda](https://github.com/gawlik81/contact-center-demo/commit/c5efbda4eaa4a867964dbd397ce390c8dc62f9fc))
* **db:** dodaj tabelę audytu retention_purge_log (DB-048, V084) ([5b55a10](https://github.com/gawlik81/contact-center-demo/commit/5b55a103c36d64fe9b0c31ae29e6b2ea6f5a7342))
* **db:** dodaj tabelę cache tenant_retention_pending_summary (DB-047, V083) ([fe7ff8e](https://github.com/gawlik81/contact-center-demo/commit/fe7ff8e003a524938b6f70ebd4361fa7731848d7))
* **db:** dodaj tabelę tenant_retention_policy retencji danych (DB-046, V082) ([573befe](https://github.com/gawlik81/contact-center-demo/commit/573befe4e12892ff347f1d9ebf85d9a47af539e5))
* **db:** partycjonuj contact_ai_summary po generated_at, online, zero utraty danych (DB-051, V087) ([c506ced](https://github.com/gawlik81/contact-center-demo/commit/c506ced939fcea1f66befb4eda41a72fa7db340a))
* **db:** partycjonuj contact_event po started_at, online, zero utraty danych (DB-049, V085) ([3455411](https://github.com/gawlik81/contact-center-demo/commit/34554110e4c481b1f5c6986aed26fe67b8c3453f))
* **db:** partycjonuj contact_transcription po created_at, online, zero utraty danych (DB-050, V086) ([13e2feb](https://github.com/gawlik81/contact-center-demo/commit/13e2febf55eac39d477e8869d8a05b4d3e32407f))
* dodaj ręczne przeliczenie dashboardu "dane do usunięcia" ([28b6ac5](https://github.com/gawlik81/contact-center-demo/commit/28b6ac5a3767e21ac68c321d2b4597ea810431ac))
* **frontend:** dodaj akcję "Usuń teraz" z modalem potwierdzenia (FE-106) ([bc224f2](https://github.com/gawlik81/contact-center-demo/commit/bc224f22c6c2f9909ca82c3c5d4e3de760b56b9b))
* **frontend:** dodaj dashboard "dane do usunięcia" (FE-105) ([2cfef89](https://github.com/gawlik81/contact-center-demo/commit/2cfef89b84451f0589424e43e2d3bd16791d7b9e))
* **frontend:** dodaj globalny badge "dane do usunięcia" w nawigacji (FE-108) ([a77b0b6](https://github.com/gawlik81/contact-center-demo/commit/a77b0b655008d9e04e5d70d7f815cf051c870567))
* **frontend:** dodaj historię operacji usuwania do panelu retencji (FE-107) ([7a70955](https://github.com/gawlik81/contact-center-demo/commit/7a709550b9c8e647f1ed8b3544724653ab97b2fc))
* **frontend:** dodaj RetentionService i modele TS dla panelu retencji (FE-103) ([3d2ecad](https://github.com/gawlik81/contact-center-demo/commit/3d2ecad0f27a115feb5c77fcb9d1b7e901650358))
* **frontend:** dodaj stronę Ustawienia &gt; Retencja danych (FE-104) ([1bb92af](https://github.com/gawlik81/contact-center-demo/commit/1bb92af610de4c711fbc80f9be764bae7ce1eafe))


### Bug Fixes

* **backend:** napraw ClassCastException w odczycie polityk/historii retencji ([2978f30](https://github.com/gawlik81/contact-center-demo/commit/2978f305d48dba75bd774f4363a2e7a7e89dd3d4))
* **backend:** napraw NPE w listContacts/getCustomerHistory dla kontaktów bez agenta ([f1e8ecb](https://github.com/gawlik81/contact-center-demo/commit/f1e8ecb3cf5d4c61d2d435e045380e22764a182a))
* **backend:** ustaw TenantContext w wątku schedulera dla jobów retencji ([67a03af](https://github.com/gawlik81/contact-center-demo/commit/67a03af6871dab584451e5945375fbcf8e9daa8f))
* **db:** napraw nazwę GUC RLS na 4 tabelach, app.tenant_id -&gt; app.current_tenant_id (DB-054, V090) ([13968d1](https://github.com/gawlik81/contact-center-demo/commit/13968d103bb2b5274ef4c71bd91f12b93b616740))
* **db:** napraw rotację partycji contact/audit_log, dodaj 3 nowe tabele (DB-052, V088) ([12cb1b5](https://github.com/gawlik81/contact-center-demo/commit/12cb1b58fa8498b1ad076af5b75b898bcb140edb))

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
