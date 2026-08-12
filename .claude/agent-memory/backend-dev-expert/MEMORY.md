# Agent Memory – Backend Dev Expert

## Projekt

- [project_contact_center.md](project_contact_center.md) – Stack, struktura Maven, konwencje, profile Spring Boot, Docker Compose
- [project_async_tenant_context.md](project_async_tenant_context.md) – RabbitMQ wątki bez TenantContext; CrossTenantAspect HTTP vs async
- [project_campaign_crud.md](project_campaign_crud.md) – BE-022 Campaign CRUD, V026 ENUM→VARCHAR
- [IVR Architecture](project_ivr_architecture.md) — IVR: twimlMode vs mock, sesje Redis, contact przy webhook Twilio
- [Twilio Conference Audio Pattern](project_twilio_conference_pattern.md) — Konferencja Twilio audio klient-agent + nagrywanie
- [Voicebot Service BE-014](project_voicebot_be014.md) — Python FastAPI ASR+NLU, VoicebotClient conditional bean
- [Progressive Dialer BE-024](project_be024_progressive_dialer.md) — Redis SET NX, FOR UPDATE SKIP LOCKED, ScheduledCallback
- [ScheduledCallbackExecutor BE-038](project_be038_scheduled_callback_executor.md) — @Scheduled oddzwonienia, ochrona double-processing
- [Inbound Callback Endpoint BE-040](project_be040_inbound_callback.md) — POST contacts/{id}/callback, sourceType=INBOUND_CALLBACK
- [Email Attachments](project_email_attachments.md) — S3 storage, IMAP extraction, SMTP multipart/mixed

## Wzorce/konwencje

- [feedback_self_invocation_transactional.md](feedback_self_invocation_transactional.md) – @Transactional self-invocation: @Lazy self + self.metoda()
- [feedback_jdbc_set_tenant_context.md](feedback_jdbc_set_tenant_context.md) – set_tenant_context(?::uuid) przez jdbcTemplate.update, nie string concat
- [feedback_tenant_context_http_vs_async.md](feedback_tenant_context_http_vs_async.md) – TenantContext.clear() tylko w async (RabbitMQ), nie w HTTP
- [feedback_rabbitmq_queue_bean_vs_binding_annotation.md](feedback_rabbitmq_queue_bean_vs_binding_annotation.md) – Kolejki jako @Bean+stała QUEUE_NAME
- [Testy repozytoriów – styl i podejście](feedback_repository_tests.md) — Brak H2; Mockito EntityManager+ReflectionTestUtils; List<Object[]> pitfall

## Znane pułapki

- [feedback_stomp_mutable_headers.md](feedback_stomp_mutable_headers.md) – StompHeaderAccessor.wrap + setLeaveMutable(true)
- [feedback_jdbc_batchupdate_param_count_mismatch.md](feedback_jdbc_batchupdate_param_count_mismatch.md) – batchUpdate array length = liczbie `?`
- [feedback_hibernate6_null_param_bytea.md](feedback_hibernate6_null_param_bytea.md) – JPQL :param IS NULL+LOWER() → bytea error; fix CAST(:param AS TEXT)
- [feedback_partitioned_table_jpa.md](feedback_partitioned_table_jpa.md) – Partycjonowane tabele: @IdClass + native INSERT
- [feedback_mockito_nested_beforeeach.md](feedback_mockito_nested_beforeeach.md) – @BeforeEach+@Nested: @MockitoSettings(LENIENT), setUp w nested
- [feedback_mockito_injectmocks_lombok_constructor.md](feedback_mockito_injectmocks_lombok_constructor.md) – Mockito5+@RequiredArgsConstructor: ręczny setter
- [feedback_jsonb_list_converter.md](feedback_jsonb_list_converter.md) – JSONB List<String>: JsonStringListConverter (@Convert)
- [feedback_contact_table_no_is_deleted.md](feedback_contact_table_no_is_deleted.md) – contact bez is_deleted; aktywne = QUEUED/ACTIVE/ON_HOLD
- [feedback_jsonb_phone_array_query.md](feedback_jsonb_phone_array_query.md) – customer.phone JSONB array: `@> to_jsonb(...)`, nie ANY()
- [feedback_mock_callid_as_contactid.md](feedback_mock_callid_as_contactid.md) – MockTelephonyAdapter: contact w DB przed publishIncoming
- [feedback_mock_disposition_agent_ownership.md](feedback_mock_disposition_agent_ownership.md) – setDisposition blokuje agent_id=null/QUEUED
- [feedback_contact_enum_to_varchar.md](feedback_contact_enum_to_varchar.md) – contact ENUM→VARCHAR w V025; wzorzec DROP→ALTER→odtwórz
- [feedback_contact_enum_cast_after_v025.md](feedback_contact_enum_cast_after_v025.md) – Po V025: CAST(:x AS VARCHAR), nie AS contact_channel
- [feedback_twilio_sdk_api.md](feedback_twilio_sdk_api.md) – Twilio SDK 10.1.5: CallCreator, Call.UpdateStatus
- [feedback_twilio_sdk_create_update_overloads.md](feedback_twilio_sdk_create_update_overloads.md) – Po BE-058: create/update(TwilioRestClient) jedyne sygnatury
- [feedback_twilio_webhook_async_pattern.md](feedback_twilio_webhook_async_pattern.md) – Webhook 204 natychmiast, REST API w @Async
- [feedback_oauth_csrf_state_redis.md](feedback_oauth_csrf_state_redis.md) – OAuth state w Redis: TTL 10min, single-use
- [feedback_transactional_no_external_io.md](feedback_transactional_no_external_io.md) – @Transactional bez HTTP I/O: readOnly→delete→external split
- [feedback_supervisor_metrics_flaky_ivr_test.md](feedback_supervisor_metrics_flaky_ivr_test.md) – KpiCallsInIvrTests pre-existing flaky, nie regresja
- [feedback_argumentcaptor_cleared_batch_list.md](feedback_argumentcaptor_cleared_batch_list.md) – ArgumentCaptor+batch.clear(): kopia obronna w thenAnswer
- [RLS cross-tenant admin aggregation](feedback_rls_cross_tenant_admin_aggregation.md) — Zawsze pętla per-tenant, nigdy `tenant_id = ANY(:ids)`
- [Micrometer gauge WeakReference pułapka](feedback_micrometer_gauge_weak_reference.md) — W testach trzymaj wartość jako pole instancji
- [JVM heap/uptime źródło metryk](feedback_jvm_heap_uptime_metrics_source.md) — Czytać przez ManagementFactory, nie Micrometer (G1 per-pula)
- [Redis cache name = 1 typ DTO](feedback_redis_cache_name_per_type.md) — Nigdy nie współdziel cache name między typami DTO

## Projekty

- [BE-025 Customer CRUD API](project_be025_customer_api.md) – Fuzzy search, RODO, RabbitMQ UNKNOWN_CALLER
- [BE-026 Customer CSV Import](project_be026_customer_import.md) – DeduplicationMode, Redis job status; +JSON multi-column
- [BE-027 Contact API](project_be027_contact_api.md) – CRUD historii kontaktów, tabela partycjonowana
- [BE-019 Routing Engine](project_routing_engine.md) – skill-based/round-robin/sticky, spy pattern w testach
- [BE-023 Campaign CSV Import](project_be023_csv_import.md) – async, Redis status, V027 unique index
- [BE-013 IVR Engine](project_be013_ivr_engine.md) – Drzewa JSONB, sesja Redis, TTS cache, DTMF timeout
- [BE-015 Email Adapter](project_be015_email_adapter.md) – IMAP+SMTP, AES-256-GCM hasła
- [BE-016 Email Templates CRUD API](project_be016_email_templates.md) – Mustache rendering, TemplateRenderException 422
- [BE-021 Wait Time Estimation](project_be021_wait_time.md) – EWT co 30s, QUEUE_WAIT_UPDATE WebSocket
- [BE-036 Contact API Advanced Filters](project_be036_contact_filters.md) – queueId/campaignId/remoteAddress/duration
- [BE-030 ETL Pipeline](project_be030_etl_pipeline.md) – CDC PostgreSQL→DW, alert cc.events/etl.lag
- [BE-030b ClickHouse DW](project_be030b_clickhouse.md) – @Primary, ReplacingMergeTree, port 8123/9002
- [BE-033 PhoneNumber CRUD API](project_be033_phonenumber_api.md) – E.164, soft delete blokowany przez routing rules
- [BE-017 Social OAuth](project_be017_social_oauth.md) – AES-256-GCM tokeny, @Scheduled refresh 1h
- [BE-018 Social Media Adapter](project_be018_social_adapter.md) – Webhook FB/IG/WA, async RabbitMQ social-incoming
- [WS Resilience – ASSIGNED Status](project_ws_resilience_assigned_status.md) — ContactAssignmentMonitor, retry Redis
- [BE-048 Manual Callback Endpoint](project_be048_manual_callback.md) — sourceType=AGENT_MANUAL, min. 5min
- [BE-043 AgentGroup domain package](project_agent_groups.md) — domain/agentgroup encja+repo
- [BE-050 AgentBreak REST API](project_agent_breaks.md) — wzorzec właścicielski per-agent
- [BE-056 TenantTwilioConfig serwis domenowy](project_twilio_config.md) — upsert+masking+decrypted DTO
- [BE-057 TenantTwilioConfig REST API (kontroler)](project_twilio_config_controller.md) — GET/PUT/DELETE/test, 204 przy braku
- [BE-059 per-tenant Twilio config](project_be059_per_tenant_twilio.md) — fallback, klucze testowe >=32 znaki
- [BE-075 Transfer Agents endpoint](project_be075_transfer_agents.md) — UNION 3 źródeł kolejek, batch bez N+1
- [BE-077 Transfer Call endpoint](project_be077_transfer_endpoint.md) — ContactService.initiateTransfer
- [BE-097 Plugin SDK module](project_be097_plugin_sdk.md) — EPIC-28, zero Spring/JPA, parent classloader
- [BE-098 PluginValidationService](project_be098_plugin_validation.md) — checksum, JSON Schema, ASM blacklist
- [BE-100 PluginRegistrationService](project_be100_plugin_registration.md) — RLS natywny SQL; install=przecięcie uprawnień
- [BE-101 PluginRuntimeManager/PluginClassLoader (RT-10)](project_be101_plugin_runtime.md) — delegacja do parenta; WeakReference+GC testy
- [BE-102 ExtensionPointPublisher/PluginInvocationExecutor/CircuitBreakerState](project_be102_extension_point_publisher.md) — dispatch timeout+TCCL
- [BE-103 integracja PRE_CONTACT_CONNECT/MANUAL_ACTION](project_be103_pre_contact_connect_integration.md) — CallEventEnricher
- [BE-104 async extension pointy przez RabbitMQ](project_be104_async_extension_points_rabbitmq.md) — POST_CONTACT_END/CUSTOMER_SYNC
- [BE-105 PluginInvocationLogService + REST historii](project_be105_invocation_log.md) — @IdClass PK złożony, PiiRedactor
- [BE-106 PluginAdminController/PluginRevokeController](project_be106_plugin_admin_controller.md) — REVOKED kill switch
- [BE-099/BE-100 DTO enrichment dla FE-097/FE-098](project_be099_be100_dto_enrichment_fe097.md) — zasada "rozszerzaj backend"
- [PluginAgentController dla FE-100](project_plugin_agent_controller.md) — GET agent/plugins, filtruje enabled=true
- [Jackson convertValue + nieznane pola](feedback_jackson_convertvalue_unknown_properties.md) — RZUCA na nieznanych polach
- [BE-108 szyfrowanie installation_config](project_be108_plugin_installation_config_encryption.md) — insert() też szyfruje, nie tylko update()
- [Plugin runtime startup reload](project_be_plugin_startup_reload.md) — PluginRuntimeStartupLoader odbudowuje registry
- [BE-023 rozszerzenie – import JSON kontaktów kampanii](project_be023_json_import_extension.md) — CSV+JSON współdzielone
- [EPIC-21 Dialer – retry i callback w kampaniach wychodzących](project_epic21_dialer.md) — BE-062–066, markAsDialingForCallback
- [TwilioTelephonyAdapter – transfer AGENT i QUEUE](project_twilio_transfer.md) — client: vs Conference redirect
- [Attended transfer refactor – Wariant A](project_attended_transfer_refactor.md) — bridgeCalls redirect, customerCallSid
- [EPIC-25 Campaign Assignment BE-079–085](project_epic25_campaign_assignment.md) — queueId→campaignId, trójpoziomowa kwalifikacja
- [EPIC-28 System pluginów](project_epic28_plugin_system.md) — installation_config AES-GCM, dziedziczenie przy upgrade
- [DbEgressClient + customer-callresult-db-sync](project_be_db_egress_client.md) — db:egress uprawnienia, bez poolingu
- [Import klientów z JSON (równolegle do CSV)](project_customer_json_import.md) — processRow współdzielone
- [AgentBreak scheduler implementation](project_agentbreak_scheduler.md) — bulk PLANNED→ACTIVE→COMPLETED co 30s
- [AdminMetrics – Contacts by Channel matrix](project_admin_metrics_contacts_by_channel.md) — "dzisiaj"=COMPLETED/TRANSFERRED+duration
- [Semantyka "agent online" i sesji Redis](project_agent_online_redis_session_semantics.md) — session:agent:* przetrwa logout (status=OFFLINE)
- [BE-117 @IdClass dla partycjonowanych encji (EPIC-29)](project_epic29_be117_idclass_partitioned_entities.md) — ContactEvent/ContactAiSummary wzorzec Contact/ContactId, ai_summary partycjonuje po generated_at nie created_at
- [BE-113 RetentionPurgeService (EPIC-29)](project_epic29_be113_retention_purge_service.md) — silnik usuwania async, self-injection, encapsulation pass do ContactService/ContactEventService/EmailMessageService/SocialMessageService
- [ctid NIEBEZPIECZNE na tabelach partycjonowanych](feedback_partitioned_table_ctid_delete_pitfall.md) — DELETE...WHERE ctid IN (...) usuwa wiersze z innych partycji/tenantów; bezpieczny wzorzec: pełny PK (id+partition_col)
