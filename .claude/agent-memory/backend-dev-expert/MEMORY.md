# Agent Memory – Backend Dev Expert

## Projekt
- [project_contact_center.md](project_contact_center.md) – Stack, struktura Maven, konwencje, klasy konfiguracyjne, profile Spring Boot, Docker Compose

## Znane pułapki
- [feedback_stomp_mutable_headers.md](feedback_stomp_mutable_headers.md) – ChannelInterceptor.preSend(): `getAccessor()` zwraca immutable; fix: `StompHeaderAccessor.wrap(message)` + `setLeaveMutable(true)` + `MessageBuilder.createMessage()`
- [feedback_hibernate6_null_param_bytea.md](feedback_hibernate6_null_param_bytea.md) – Hibernate 6: JPQL z `:param IS NULL` + LOWER() na tym samym parametrze String → PostgreSQL `lower(bytea) does not exist`; fix: natywny SQL z `CAST(:param AS TEXT)`
- [feedback_partitioned_table_jpa.md](feedback_partitioned_table_jpa.md) – JPA na tabelach partycjonowanych: `@IdClass` + native INSERT przez `@Modifying @Query(nativeQuery=true)`, odczyt przez JPQL działa normalnie
- [feedback_mockito_nested_beforeeach.md](feedback_mockito_nested_beforeeach.md) – @BeforeEach zewnętrznej klasy może nie inicjalizować pól w @Nested gdy Surefire uruchamia nested osobno; używaj @MockitoSettings(LENIENT) + przenoś setUp do nested
- [feedback_mockito_injectmocks_lombok_constructor.md](feedback_mockito_injectmocks_lombok_constructor.md) – Mockito 5 + @RequiredArgsConstructor: pola non-final pomijane w @InjectMocks; fix: ręczne wywołanie settera w @BeforeEach
- [feedback_jsonb_list_converter.md](feedback_jsonb_list_converter.md) – JSONB List<String>: brak hypersistence-utils → używaj JsonStringListConverter (@Convert), nie @Type(JsonType.class)
- [feedback_contact_table_no_is_deleted.md](feedback_contact_table_no_is_deleted.md) – Tabela contact (partycjonowana) nie ma is_deleted; aktywne statusy: QUEUED/ACTIVE/ON_HOLD
