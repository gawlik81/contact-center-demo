# Agent Memory – Backend Dev Expert

## Projekt

- [project_contact_center.md](project_contact_center.md) – Stack, struktura Maven, konwencje, klasy konfiguracyjne, profile Spring Boot, Docker Compose
- [project_async_tenant_context.md](project_async_tenant_context.md) – Serwisy w wątkach RabbitMQ nie mają TenantContext; CrossTenantAspect poprawiony do
  odróżniania HTTP vs async (2026-03-22)
- [project_campaign_crud.md](project_campaign_crud.md) – BE-022 Campaign CRUD API: encja, repo, serwis, kontroler, migracja V026 (konwersja ENUM kampanii na
  VARCHAR)

## Znane pułapki

- [feedback_stomp_mutable_headers.md](feedback_stomp_mutable_headers.md) – ChannelInterceptor.preSend(): `getAccessor()` zwraca immutable; fix:
  `StompHeaderAccessor.wrap(message)` + `setLeaveMutable(true)` + `MessageBuilder.createMessage()`
- [feedback_hibernate6_null_param_bytea.md](feedback_hibernate6_null_param_bytea.md) – Hibernate 6: JPQL z `:param IS NULL` + LOWER() na tym samym parametrze
  String → PostgreSQL `lower(bytea) does not exist`; fix: natywny SQL z `CAST(:param AS TEXT)`
- [feedback_partitioned_table_jpa.md](feedback_partitioned_table_jpa.md) – JPA na tabelach partycjonowanych: `@IdClass` + native INSERT przez
  `@Modifying @Query(nativeQuery=true)`, odczyt przez JPQL działa normalnie
- [feedback_mockito_nested_beforeeach.md](feedback_mockito_nested_beforeeach.md) – @BeforeEach zewnętrznej klasy może nie inicjalizować pól w @Nested gdy
  Surefire uruchamia nested osobno; używaj @MockitoSettings(LENIENT) + przenoś setUp do nested
- [feedback_mockito_injectmocks_lombok_constructor.md](feedback_mockito_injectmocks_lombok_constructor.md) – Mockito 5 + @RequiredArgsConstructor: pola
  non-final pomijane w @InjectMocks; fix: ręczne wywołanie settera w @BeforeEach
- [feedback_jsonb_list_converter.md](feedback_jsonb_list_converter.md) – JSONB List<String>: brak hypersistence-utils → używaj JsonStringListConverter (
  @Convert), nie @Type(JsonType.class)
- [feedback_contact_table_no_is_deleted.md](feedback_contact_table_no_is_deleted.md) – Tabela contact (partycjonowana) nie ma is_deleted; aktywne statusy:
  QUEUED/ACTIVE/ON_HOLD
- [feedback_jsonb_phone_array_query.md](feedback_jsonb_phone_array_query.md) – customer.phone to JSONB array (nie TEXT[]): używaj
  `phone @> to_jsonb(CAST(:phone AS text))` z GIN index, nie `ANY()`
- [feedback_mock_callid_as_contactid.md](feedback_mock_callid_as_contactid.md) – MockTelephonyAdapter tworzy rekord contact w DB PRZED publishIncoming; UUID z
  DB trafia jako `contactId` do WebSocket payload; `CallEvent.contactId` (UUID) obok `callId` (String); fallback na callId gdy null
- [feedback_mock_disposition_agent_ownership.md](feedback_mock_disposition_agent_ownership.md) – setDisposition blokuje gdy agent_id=null LUB status=QUEUED;
  MockCallController defaultuje agentId na TenantContext.getUserId(); MockTelephonyAdapter.hangupCall() aktualizuje status na COMPLETED przez jdbcTemplate (nie
  EntityManager – brak TenantContext)
- [feedback_contact_enum_to_varchar.md](feedback_contact_enum_to_varchar.md) – contact.channel/direction/status były ENUM w V007, pominięte przez V019; fix:
  V025 konwertuje je do VARCHAR+CHECK; wzorzec: DROP widoki+indeksy partial → ALTER TYPE → DROP TYPE → odtwórz
- [feedback_contact_enum_cast_after_v025.md](feedback_contact_enum_cast_after_v025.md) – Po V025 typy ENUM usunięte; ContactRepository musi używać
  `CAST(:x AS VARCHAR)` nie `CAST(:x AS contact_channel/status/direction)` – inaczej INSERT/UPDATE rzuca `type does not exist`

- [feedback_twilio_sdk_api.md](feedback_twilio_sdk_api.md) – Twilio SDK 10.1.5: `CallCreator` (nie `Call.Creator`), `Call.UpdateStatus` (nie
  `CallUpdater.Status`), ambiguous mocks w testach

## Projekty

- [BE-025 Customer CRUD API](project_be025_customer_api.md) – implementacja Customer CRUD, fuzzy search, RODO, RabbitMQ UNKNOWN_CALLER
- [BE-026 Customer CSV Import](project_be026_customer_import.md) – async import klientów z CSV, DeduplicationMode SKIP/OVERWRITE, Redis job status, batch
  JdbcTemplate, wielokrotne phone/email (;)
- [BE-027 Contact API](project_be027_contact_api.md) – CRUD historii kontaktów, tabela partycjonowana, ContactRepository rozszerza istniejący plik (BE-010
  recording)
- [BE-019 Routing Engine](project_routing_engine.md) – silnik routingu (skill-based, round-robin, sticky agent), pakiet domain.routing, spy pattern w testach
- [BE-023 Campaign CSV Import](project_be023_csv_import.md) – async CSV import, JdbcTemplate batch, Redis status QUEUED/PROCESSING/COMPLETED/FAILED_PARTIAL,
  V027 unique index
- [BE-013 IVR Engine](project_be013_ivr_engine.md) – silnik IVR, drzewa węzłów JSONB, sesja Redis (ivr:session:), TTS cache (ivr:tts:), fallback do kolejki,
  TaskScheduler DTMF timeout
- [BE-015 Email Adapter](project_be015_email_adapter.md) – IMAP polling + SMTP wysyłka, AES-256-GCM hasła, email_routing_rule, schemat z V010, angus-mail
  dependency
- [BE-016 Email Templates CRUD API](project_be016_email_templates.md) – CRUD szablonów email, Mustache rendering, TemplateRenderException (HTTP 422), V028
  migracja, integracja z EmailSendService
