# Agent Memory – Backend Dev Expert

## Projekt

- [project_contact_center.md](project_contact_center.md) – Stack, struktura Maven, konwencje, klasy konfiguracyjne, profile Spring Boot, Docker Compose
- [project_async_tenant_context.md](project_async_tenant_context.md) – Serwisy w wątkach RabbitMQ nie mają TenantContext; CrossTenantAspect poprawiony do
  odróżniania HTTP vs async (2026-03-22)
- [project_campaign_crud.md](project_campaign_crud.md) – BE-022 Campaign CRUD API: encja, repo, serwis, kontroler, migracja V026 (konwersja ENUM kampanii na
  VARCHAR)
- [IVR Architecture](project_ivr_architecture.md) — Architektura silnika IVR: tryby twimlMode vs mock, sesje Redis, tworzenie rekordu contact przy webhook
  Twilio
- [Twilio Conference Audio Pattern](project_twilio_conference_pattern.md) — wzorzec konferencji Twilio do zestawiania audio klient-agent z nagrywaniem (
  BUGFIX-TWILIO-AUDIO-RECORDING)
- [Voicebot Service BE-014](project_voicebot_be014.md) — mikrousługa Python FastAPI ASR+NLU, IvrNodeType.VOICEBOT, VoicebotClient conditional bean, Docker
  profile `ai`
- [Progressive Dialer BE-024](project_be024_progressive_dialer.md) — ProgressiveDialerService @RabbitListener agent.status.changed, Redis guard SET NX, FOR
  UPDATE SKIP LOCKED, DialerCallbackHandler, ScheduledCallback entity, V031 indeksy
- [ScheduledCallbackExecutor BE-038](project_be038_scheduled_callback_executor.md) — @Scheduled fixedDelay scheduler oddzwonień, updateStatusIfPending atomowa
  ochrona double-processing, ręczny TenantContext w wątku schedulera
- [Inbound Callback Endpoint BE-040](project_be040_inbound_callback.md) — POST /api/contacts/{contactId}/callback w DialerController (ścieżka absolutna),
  sourceType=INBOUND_CALLBACK, originContactId, logika 403/404 dla agentów

## Wzorce/konwencje

- [feedback_self_invocation_transactional.md](feedback_self_invocation_transactional.md) – @Transactional self-invocation: fix przez `@Autowired @Lazy NazwaSerwisu self` + wywołanie `self.metoda()`
- [feedback_jdbc_set_tenant_context.md](feedback_jdbc_set_tenant_context.md) – JdbcTemplate set_tenant_context: `jdbcTemplate.update("SELECT set_tenant_context(?::uuid)", id)` nie string concat
- [feedback_tenant_context_http_vs_async.md](feedback_tenant_context_http_vs_async.md) – TenantContext.clear() tylko w ścieżce async (RabbitMQ), NIE w metodach wywoływanych z HTTP
- [feedback_rabbitmq_queue_bean_vs_binding_annotation.md](feedback_rabbitmq_queue_bean_vs_binding_annotation.md) – Kolejki RabbitMQ jako @Bean w RabbitMQConfig + stała QUEUE_NAME; @RabbitListener(queues = STAŁA)

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
- [feedback_twilio_webhook_async_pattern.md](feedback_twilio_webhook_async_pattern.md) – Webhook handler zwraca 204 natychmiast; logika Twilio REST API (Conference.fetcher) w @Async; X-Twilio-Signature walidacja przez RequestValidator; HttpClient jako pole
- [feedback_oauth_csrf_state_redis.md](feedback_oauth_csrf_state_redis.md) – OAuth state w Redis: klucz `oauth:state:{state}` → tenantId, TTL 10min, single-use; ustawia TenantContext w publicznym callbacku
- [feedback_transactional_no_external_io.md](feedback_transactional_no_external_io.md) – @Transactional bez blokującego HTTP I/O: podziel na readOnly→delete→external-call; metody pomocnicze muszą być protected (nie private)

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
- [BE-021 Wait Time Estimation](project_be021_wait_time.md) – EWT co 30s, WaitTimeEstimationService, QUEUE_WAIT_UPDATE WebSocket, GET /api/queues/{id}/stats
- [BE-036 Contact API Advanced Filters](project_be036_contact_filters.md) – rozszerzenie GET /api/contacts: queueId, campaignId, remoteAddress (ILIKE), durationMin/Max; ContactFilterParams record, appendFilterConditions pattern
- [BE-030 ETL Pipeline](project_be030_etl_pipeline.md) – polling CDC PostgreSQL→DW, EtlSyncService @Scheduled, PostgresDwWriter (upsert), alert RabbitMQ cc.events/etl.lag.alert, GET /api/admin/etl/status
- [BE-033 PhoneNumber CRUD API](project_be033_phonenumber_api.md) – CRUD numerów telefonu E.164, PhoneNumber encja, PhoneNumberRepository, PhoneRoutingRuleRepository (stub dla BE-034), soft delete blokowany przez aktywne reguły routingu
- [BE-017 Social OAuth](project_be017_social_oauth.md) – OAuth flow i szyfrowanie tokenów AES-256-GCM (BYTEA), SocialIntegration encja, callback publiczny w SecurityConfig+TenantFilter, @Scheduled refresh co 1h
- [BE-018 Social Media Adapter](project_be018_social_adapter.md) – webhook handler FB/IG/WA, SocialMessage encja, adapter stubs, async RabbitMQ (cc.queue.social-incoming), cross-tenant findByPlatformAndPageId
