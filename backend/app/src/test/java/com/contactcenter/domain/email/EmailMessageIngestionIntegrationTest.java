package com.contactcenter.domain.email;

import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test integracyjny przepływu wiadomości email na kluczu {@code (message_id, message_at)} — BE-134
 * (EPIC-30, DB-067). Testcontainers, pełny łańcuch Flyway V001..V102, repozytorium pod rolą BEZ
 * {@code BYPASSRLS} (RLS aktywne, GUC {@code app.current_tenant_id} z {@link TenantContext}).
 *
 * <p>Zakres (AC BE-134, WP-1):
 * <ul>
 *   <li>pełny przepływ INBOUND: {@link EmailPollingServiceImpl#pollTenantInbox} z mockowanym
 *       {@link Store} → zapis → zdarzenie {@code email.received}/{@code email.queued} →
 *       {@link EmailContactCreator} → powiązanie z kontaktem → EML;</li>
 *   <li>redelivery tego samego Message-ID = 1 wiersz (dedup aplikacyjny i wyścig pod advisory lockiem);</li>
 *   <li>OUTBOUND: {@code messageAt == sentAt};</li>
 *   <li>zdarzenie w formacie sprzed BE-134 (bez {@code messageAt}) obsłużone; nowe pole tolerowane przez
 *       konsumenta sprzed BE-134 (wdrożenie kroczące);</li>
 *   <li>INBOUND: {@code messageAt = INTERNALDATE}, nie nagłówek {@code Date} (przeszłość i przyszłość);</li>
 *   <li>zapis, aktualizacja i lookup pod rolą bez BYPASSRLS z GUC tenanta.</li>
 * </ul>
 *
 * <p>Routing ({@link EmailRoutingService}) jest zastąpiony mockiem — jego zadaniem jest tylko wybór
 * kolejki i publikacja {@code email.queued}; to samo zdarzenie buduje tu {@link EmailEventPublisher#publishQueued}.
 */
@DisplayName("BE-134: przepływ email na kluczu (message_id, message_at) — Testcontainers")
class EmailMessageIngestionIntegrationTest {

    private static final String ROLE = "cc_be134_ingest";
    /** Klucz szyfrowania IMAP/SMTP wyłącznie do testu (64 znaki hex). */
    private static final String TEST_KEY = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
    /** INTERNALDATE serwera IMAP — precyzja milisekund jak w {@link Date}. */
    private static final Instant INTERNAL_DATE = Instant.parse("2026-09-01T08:15:30.123Z");
    private static final String AGENT_ID_TEXT = "a9a9a9a9-a9a9-4a9a-8a9a-a9a9a9a9a9a9";

    private static HikariDataSource superuserPool;
    private static HikariDataSource restrictedPool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static EmailMessageRepository repository;

    private UUID tenantId;
    private Tenant tenant;

    private RabbitTemplate rabbit;
    private final List<Object> published = new CopyOnWriteArrayList<>();
    private EmailEventPublisher publisher;
    private EmailRoutingService routing;
    private TenantService tenantService;
    private EmailEncryptionService encryption;
    private EmailAttachmentStorageService storage;
    private ContactService contactService;
    private CustomerService customerService;
    private EmailEmlService emlService;
    private EmailPollingServiceImpl poller;
    private EmailContactCreator creator;
    private EmailSendServiceImpl sender;

    @BeforeAll
    static void startContext() {
        superuserPool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(superuserPool);
        String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, ROLE);
        restrictedPool = PostgresTestDatabase.pool(ROLE, password, 4);

        ctx = JpaTestContext.create(
                restrictedPool,
                new Class<?>[]{EmailMessage.class},
                new Class<?>[]{EmailMessageRepository.class},
                null);
        repository = ctx.getBean(EmailMessageRepository.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        restrictedPool.close();
        superuserPool.close();
    }

    @BeforeEach
    void setUp() {
        tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-134 " + UUID.randomUUID());
        tenant = buildTenant(tenantId);
        TenantContext.setTenantId(tenantId);

        rabbit = mock(RabbitTemplate.class);
        doAnswer(inv -> {
            published.add(inv.getArgument(2));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));

        publisher = new EmailEventPublisher(rabbit);
        routing = mock(EmailRoutingService.class);
        tenantService = mock(TenantService.class);
        encryption = new EmailEncryptionServiceImpl(TEST_KEY);
        storage = mock(EmailAttachmentStorageService.class);
        contactService = mock(ContactService.class);
        customerService = mock(CustomerService.class);
        emlService = mock(EmailEmlService.class);
        when(emlService.generateAndUpload(any(), any(), any())).thenReturn("eml/be134.eml");

        poller = new EmailPollingServiceImpl(tenantService, repository, routing, publisher,
                encryption, storage, new ObjectMapper());
        creator = new EmailContactCreator(contactService, customerService, repository, rabbit, emlService);
        sender = new EmailSendServiceImpl(repository, publisher, tenantService, encryption,
                mock(EmailTemplateService.class), mock(TemplateVariableResolver.class), storage, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM email_message WHERE tenant_id = ?", tenantId);
    }

    // =========================================================================
    // (a) Pełny przepływ IMAP → zapis → zdarzenie → EmailContactCreator
    // =========================================================================

    @Test
    @DisplayName("(a) IMAP → zapis (messageAt = INTERNALDATE) → email.queued → kontakt, powiązanie i EML")
    void inboundFullChain_pollSaveEventContactAndEml() throws Exception {
        String header = "<flow-" + UUID.randomUUID() + "@ext.example>";
        pollOnce(inboundMessage(header, INTERNAL_DATE, new Date(0L)));

        UUID messageId = idByHeader(header);
        Map<String, Object> row = row(messageId);
        assertThat(instant(row, "message_at")).isEqualTo(INTERNAL_DATE);
        assertThat(instant(row, "received_at")).isEqualTo(INTERNAL_DATE);
        assertThat(row.get("contact_id")).isNull();

        // Zdarzenie email.received niesie pełny klucz
        EmailEventPublisher.EmailEvent received = events(EmailEventPublisher.EventType.RECEIVED).get(0);
        assertThat(received.messageId()).isEqualTo(messageId);
        assertThat(received.messageAt()).isEqualTo(INTERNAL_DATE);

        // Routing (zastąpiony): wybór kolejki → email.queued z tym samym kluczem
        UUID queueId = UUID.randomUUID();
        EmailMessage saved = repository.findById(messageId, INTERNAL_DATE).orElseThrow();
        publisher.publishQueued(saved, queueId);
        EmailEventPublisher.EmailEvent queued = events(EmailEventPublisher.EventType.QUEUED).get(0);
        assertThat(queued.messageAt()).isEqualTo(INTERNAL_DATE);

        creator.onEmailEvent(queued);

        ArgumentCaptor<Contact> contact = ArgumentCaptor.forClass(Contact.class);
        verify(contactService).insertContact(contact.capture());
        UUID contactId = contact.getValue().getContactId();
        assertThat(contact.getValue().getChannel()).isEqualTo("EMAIL");

        assertThat(jdbc.queryForObject("SELECT contact_id FROM email_message WHERE message_id = ?",
                UUID.class, messageId)).isEqualTo(contactId);
        verify(contactService).updateRecordingUrl(eq(contactId), eq(tenantId), eq("eml/be134.eml"));
        assertThat(instant(row(messageId), "message_at")).as("UPDATE powiązania nie zmienia klucza partycji")
                .isEqualTo(INTERNAL_DATE);
    }

    // =========================================================================
    // (b) Redelivery tego samego Message-ID = 1 wiersz
    // =========================================================================

    @Nested
    @DisplayName("(b) redelivery i duplikaty")
    class Redelivery {

        @Test
        @DisplayName("ponowne odebranie tej samej wiadomości (dedup aplikacyjny) → dokładnie 1 wiersz")
        void samePollTwice_storesOneRow() throws Exception {
            String header = "<redeliver-" + UUID.randomUUID() + "@ext.example>";
            MimeMessage message = inboundMessage(header, INTERNAL_DATE, null);

            pollOnce(message);
            pollOnce(message);

            assertThat(headerCount(header)).isEqualTo(1);
        }

        @Test
        @DisplayName("zapis duplikatu nagłówka (ta sama data) → Optional.empty, bez wyjątku, 1 wiersz")
        void duplicateSave_returnsEmptyNoException() {
            String header = "<dup-" + UUID.randomUUID() + "@ext.example>";
            Optional<EmailMessage> first = repository.save(draftInbound(header, INTERNAL_DATE));
            Optional<EmailMessage> second = repository.save(draftInbound(header, INTERNAL_DATE));

            assertThat(first).isPresent();
            assertThat(second).isEmpty();
            assertThat(headerCount(header)).isEqualTo(1);
        }

        @Test
        @DisplayName("dwa równoległe zapisy tego samego nagłówka (wyścig pollerów) → jeden zapis, drugi pominięty, bez błędu")
        void concurrentSaves_sameHeader_oneRowNoError() throws Exception {
            String header = "<race-" + UUID.randomUUID() + "@ext.example>";
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CyclicBarrier start = new CyclicBarrier(2);
            try {
                List<Future<Optional<EmailMessage>>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    futures.add(pool.submit(() -> {
                        TenantContext.setTenantId(tenantId);
                        try {
                            start.await();
                            return repository.save(draftInbound(header, INTERNAL_DATE));
                        } finally {
                            TenantContext.clear();
                        }
                    }));
                }
                long present = 0;
                for (Future<Optional<EmailMessage>> f : futures) {
                    if (f.get().isPresent()) {
                        present++;
                    }
                }
                assertThat(present).as("dokładnie jeden z dwóch zapisów wstawia wiersz").isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
            assertThat(headerCount(header)).isEqualTo(1);
        }
    }

    // =========================================================================
    // (c) OUTBOUND: messageAt == sentAt
    // =========================================================================

    @Nested
    @DisplayName("(c) OUTBOUND: messageAt = sentAt (jeden Instant)")
    class Outbound {

        @Test
        @DisplayName("sendNew: messageAt == sentAt == zapisany sent_at; wiersz w bazie zgodny")
        void sendNew_messageAtEqualsSentAt() throws Exception {
            when(tenantService.findTenantEntity(tenantId)).thenReturn(Optional.of(tenant));
            EmailSendServiceImpl spied = spy(sender);
            doNothing().when(spied).sendSmtp(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

            EmailMessage sent = spied.sendNew(tenantId, "klient@example.com", "Temat", "<p>x</p>",
                    UUID.fromString(AGENT_ID_TEXT), List.of());

            assertThat(sent.getMessageAt()).isNotNull();
            assertThat(sent.getSentAt()).isEqualTo(sent.getMessageAt());
            assertThat(sent.getMessageAt()).isEqualTo(sent.getMessageAt().truncatedTo(ChronoUnit.MICROS));

            Map<String, Object> row = row(sent.getId());
            assertThat(row.get("direction")).isEqualTo("OUTBOUND");
            assertThat(instant(row, "message_at")).isEqualTo(sent.getMessageAt());
            assertThat(instant(row, "sent_at")).isEqualTo(sent.getMessageAt());
        }

        @Test
        @DisplayName("sendReply: lookup oryginału po samym id (API), odpowiedź ma messageAt == sentAt")
        void sendReply_lookupByIdAndMessageAtEqualsSentAt() throws Exception {
            String header = "<orig-" + UUID.randomUUID() + "@ext.example>";
            EmailMessage original = repository.save(draftInbound(header, INTERNAL_DATE)).orElseThrow();
            when(tenantService.findTenantEntity(tenantId)).thenReturn(Optional.of(tenant));
            EmailSendServiceImpl spied = spy(sender);
            doNothing().when(spied).sendSmtp(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

            EmailMessage reply = spied.sendReply(tenantId, original.getId(), "<p>odp</p>", null,
                    UUID.fromString(AGENT_ID_TEXT), List.of());

            assertThat(reply.getSentAt()).isEqualTo(reply.getMessageAt());
            assertThat(instant(row(reply.getId()), "message_at")).isEqualTo(reply.getMessageAt());
            assertThat(reply.getContactId()).isEqualTo(original.getContactId());
        }
    }

    // =========================================================================
    // (d) Zgodność formatu zdarzeń (sprzed BE-134 i wdrożenie kroczące)
    // =========================================================================

    @Nested
    @DisplayName("(d) zdarzenie bez messageAt (format sprzed BE-134)")
    class LegacyEvents {

        @Test
        @DisplayName("zdarzenie email.queued bez messageAt → kontakt powiązany (lookup po samym message_id)")
        void legacyQueuedEvent_withoutMessageAt_linksContact() throws Exception {
            EmailMessage stored = repository.save(draftInbound("<legacy-" + UUID.randomUUID() + "@ext.example>",
                    INTERNAL_DATE)).orElseThrow();
            String legacyJson = """
                    {"eventType":"QUEUED","messageId":"%s","tenantId":"%s","contactId":null,
                     "agentId":null,"queueId":"%s","fromAddress":"klient@example.com","subject":"s",
                     "direction":"INBOUND","timestamp":"2026-09-01T08:16:00Z","metadata":null}
                    """.formatted(stored.getId(), tenantId, UUID.randomUUID());

            EmailEventPublisher.EmailEvent legacy = legacyMapper().readValue(legacyJson, EmailEventPublisher.EmailEvent.class);
            assertThat(legacy.messageAt()).as("brak pola → null").isNull();

            creator.onEmailEvent(legacy);

            ArgumentCaptor<Contact> contact = ArgumentCaptor.forClass(Contact.class);
            verify(contactService).insertContact(contact.capture());
            assertThat(jdbc.queryForObject("SELECT contact_id FROM email_message WHERE message_id = ?",
                    UUID.class, stored.getId())).isEqualTo(contact.getValue().getContactId());
            assertThat(instant(row(stored.getId()), "message_at")).isEqualTo(INTERNAL_DATE);
        }

        @Test
        @DisplayName("nowy event z messageAt przechodzi przez konwerter RabbitMQ bez utraty precyzji mikrosekund")
        void newEvent_roundTripThroughRabbitConverter_keepsMessageAt() throws Exception {
            UUID messageId = UUID.randomUUID();
            Instant at = Instant.parse("2026-09-01T08:15:30.123456Z");
            EmailEventPublisher.EmailEvent event = new EmailEventPublisher.EmailEvent(
                    EmailEventPublisher.EventType.RECEIVED, messageId, tenantId, null, null, null,
                    "a@a.pl", "s", "INBOUND", Instant.parse("2026-09-01T08:16:00Z"), null, at);

            Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
            org.springframework.amqp.core.Message wire = converter.toMessage(event, new MessageProperties());
            EmailEventPublisher.EmailEvent back = (EmailEventPublisher.EmailEvent) converter.fromMessage(wire);

            assertThat(back.messageAt()).isEqualTo(at);
            assertThat(back.messageId()).isEqualTo(messageId);
        }

        @Test
        @DisplayName("konsument sprzed BE-134 (bez pola messageAt) toleruje nowy event — wdrożenie kroczące nie zrywa kolejki")
        void preBe134Consumer_toleratesNewField() throws Exception {
            EmailEventPublisher.EmailEvent event = new EmailEventPublisher.EmailEvent(
                    EmailEventPublisher.EventType.QUEUED, UUID.randomUUID(), tenantId, null, null, UUID.randomUUID(),
                    "a@a.pl", "s", "INBOUND", Instant.parse("2026-09-01T08:16:00Z"), null,
                    Instant.parse("2026-09-01T08:15:30.123456Z"));
            Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
            org.springframework.amqp.core.Message wire = converter.toMessage(event, new MessageProperties());
            // Ten sam JSON, ale typ docelowy = kształt sprzed BE-134 (bez messageAt)
            wire.getMessageProperties().setHeader("__TypeId__", PreBe134EmailEvent.class.getName());

            Object parsed = converter.fromMessage(wire);

            assertThat(parsed).isInstanceOf(PreBe134EmailEvent.class);
            assertThat(((PreBe134EmailEvent) parsed).messageId()).isEqualTo(event.messageId());
        }
    }

    // =========================================================================
    // (e) INBOUND: messageAt = INTERNALDATE, nie nagłówek Date nadawcy
    // =========================================================================

    @Nested
    @DisplayName("(e) INBOUND: messageAt z INTERNALDATE, nagłówek Date ignorowany")
    class InternalDate {

        @Test
        @DisplayName("nagłówek Date z przeszłości (1990) → messageAt = INTERNALDATE")
        void dateHeaderInPast_ignored() throws Exception {
            String header = "<past-" + UUID.randomUUID() + "@ext.example>";
            Date past = Date.from(Instant.parse("1990-01-01T00:00:00Z"));
            pollOnce(inboundMessage(header, INTERNAL_DATE, past));

            Map<String, Object> row = row(idByHeader(header));
            assertThat(instant(row, "message_at")).isEqualTo(INTERNAL_DATE).isNotEqualTo(past.toInstant());
        }

        @Test
        @DisplayName("nagłówek Date z przyszłości (2035) → messageAt = INTERNALDATE (przyszłość nie blokuje wygasania)")
        void dateHeaderInFuture_ignored() throws Exception {
            String header = "<future-" + UUID.randomUUID() + "@ext.example>";
            Date future = Date.from(Instant.parse("2035-06-01T00:00:00Z"));
            pollOnce(inboundMessage(header, INTERNAL_DATE, future));

            Map<String, Object> row = row(idByHeader(header));
            assertThat(instant(row, "message_at")).isEqualTo(INTERNAL_DATE).isNotEqualTo(future.toInstant());
        }

        @Test
        @DisplayName("brak INTERNALDATE → fallback now() (w przedziale czasu odbioru), nie nagłówek Date")
        void noInternalDate_fallsBackToNow() throws Exception {
            String header = "<nodate-" + UUID.randomUUID() + "@ext.example>";
            Instant before = Instant.now().minusSeconds(1);
            pollOnce(inboundMessage(header, null, new Date(0L)));
            Instant after = Instant.now().plusSeconds(1);

            Instant messageAt = instant(row(idByHeader(header)), "message_at");
            assertThat(messageAt).isBetween(before, after);
            assertThat(messageAt).isEqualTo(messageAt.truncatedTo(ChronoUnit.MICROS));
        }
    }

    // =========================================================================
    // (f) Zapis / odczyt / aktualizacja pod rolą bez BYPASSRLS z GUC
    // =========================================================================

    @Nested
    @DisplayName("(f) pod rolą bez BYPASSRLS z GUC tenanta")
    class RlsRole {

        @Test
        @DisplayName("rola testu nie ma BYPASSRLS ani SUPERUSER (sanity)")
        void roleIsRestricted() {
            assertThat(jdbc.queryForObject(
                    "SELECT rolbypassrls OR rolsuper FROM pg_roles WHERE rolname = ?", Boolean.class, ROLE))
                    .isFalse();
        }

        @Test
        @DisplayName("save → findById(id, messageAt) → update: wszystko przechodzi z GUC; klucz niezmieniony")
        void saveFindUpdate_underRestrictedRoleWithGuc() {
            String header = "<rls-" + UUID.randomUUID() + "@ext.example>";
            EmailMessage saved = repository.save(draftInbound(header, INTERNAL_DATE)).orElseThrow();

            EmailMessage found = repository.findById(saved.getId(), INTERNAL_DATE).orElseThrow();
            assertThat(found.getMessageAt()).isEqualTo(INTERNAL_DATE);

            UUID contactId = UUID.randomUUID();
            found.setContactId(contactId);
            repository.update(found);

            Map<String, Object> row = row(saved.getId());
            assertThat(row.get("contact_id")).isEqualTo(contactId);
            assertThat(instant(row, "message_at")).isEqualTo(INTERNAL_DATE);
        }

        @Test
        @DisplayName("update z niepasującym messageAt nie aktualizuje cicho zera wierszy → IllegalStateException")
        void updateWithWrongKey_failsLoudly() {
            EmailMessage saved = repository.save(draftInbound("<wrongkey-" + UUID.randomUUID() + "@ext.example>",
                    INTERNAL_DATE)).orElseThrow();
            // messageAt jest niemodyfikowalne (brak settera) — „zła" wartość to inny klucz tego samego id
            EmailMessage wrongKey = draftInbound(saved.getMessageIdHeader(), INTERNAL_DATE.plusSeconds(60));
            wrongKey.setId(saved.getId());

            assertThatThrownBy(() -> repository.update(wrongKey)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("save z messageAt = null lub z precyzją > mikrosekund → IllegalArgumentException (brak cichego now())")
        void saveRejectsMissingOrNonMicrosecondMessageAt() {
            EmailMessage missing = draftInbound("<nullat-" + UUID.randomUUID() + "@ext.example>", null);
            assertThatThrownBy(() -> repository.save(missing)).isInstanceOf(IllegalArgumentException.class);

            EmailMessage nanos = draftInbound("<nanos-" + UUID.randomUUID() + "@ext.example>",
                    Instant.parse("2026-09-01T08:15:30.123456789Z"));
            assertThatThrownBy(() -> repository.save(nanos)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("zapis wiadomości innego tenanta niż TenantContext → CrossTenantAccessException (przed SQL)")
        void crossTenantSave_rejected() {
            EmailMessage foreign = draftInbound("<cross-" + UUID.randomUUID() + "@ext.example>", INTERNAL_DATE);
            foreign.setTenantId(UUID.randomUUID());

            assertThatThrownBy(() -> repository.save(foreign)).isInstanceOf(CrossTenantAccessException.class);
        }
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    /** Odbiór przez IMAP: prawdziwa ścieżka {@link EmailPollingServiceImpl#pollTenantInbox} z mockowanym Store. */
    private void pollOnce(MimeMessage message) throws Exception {
        EmailPollingServiceImpl spied = spy(poller);
        Store store = mock(Store.class);
        Folder inbox = mock(Folder.class);
        doReturn(store).when(spied).connectImap(any(), anyString());
        when(store.getFolder("INBOX")).thenReturn(inbox);
        when(inbox.search(any())).thenReturn(new Message[]{message});

        spied.pollTenantInbox(tenant);
    }

    /**
     * Wiadomość IMAP: {@code receivedDate} = INTERNALDATE (albo {@code null} — serwer go nie podał),
     * nagłówek {@code Date} = {@code sentHeader} (kontrolowany przez nadawcę).
     */
    private static MimeMessage inboundMessage(String header, Instant internalDate, Date sentHeader) throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session) {
            @Override
            public Date getReceivedDate() {
                return internalDate != null ? Date.from(internalDate) : null;
            }
        };
        message.setFrom(new InternetAddress("klient@example.com"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse("biuro@example.com"));
        message.setSubject("Temat BE-134");
        message.setHeader("Message-ID", header);
        if (sentHeader != null) {
            message.setSentDate(sentHeader);
        }
        message.setText("Treść wiadomości testowej.");
        message.setFlag(Flags.Flag.SEEN, false);
        return message;
    }

    /** Szkic INBOUND o podanym kluczu (bez IMAP) — do testów repozytorium. */
    private EmailMessage draftInbound(String header, Instant messageAt) {
        return EmailMessage.builder()
                .tenantId(tenantId)
                .direction(EmailMessage.Direction.INBOUND.name())
                .fromAddress("klient@example.com")
                .toAddress("biuro@example.com")
                .subject("Temat")
                .bodyText("Treść")
                .messageIdHeader(header)
                .receivedAt(messageAt)
                .messageAt(messageAt)
                .build();
    }

    private static Tenant buildTenant(UUID id) {
        Map<String, Object> config = new HashMap<>();
        config.put("email_enabled", true);
        config.put("email_imap_host", "imap.test.com");
        config.put("email_imap_port", 993);
        config.put("email_imap_ssl", true);
        config.put("email_smtp_host", "smtp.test.com");
        config.put("email_smtp_port", 587);
        config.put("email_smtp_ssl", false);
        config.put("email_username", "inbox@company.com");
        config.put("email_password", new EmailEncryptionServiceImpl(TEST_KEY).encrypt("test-password"));
        return Tenant.builder()
                .id(id)
                .name("Test Tenant BE-134")
                .status(Tenant.TenantStatus.ACTIVE)
                .config(config)
                .build();
    }

    private List<EmailEventPublisher.EmailEvent> events(EmailEventPublisher.EventType type) {
        return published.stream()
                .filter(EmailEventPublisher.EmailEvent.class::isInstance)
                .map(EmailEventPublisher.EmailEvent.class::cast)
                .filter(e -> e.eventType() == type)
                .toList();
    }

    private UUID idByHeader(String header) {
        return jdbc.queryForObject(
                "SELECT message_id FROM email_message WHERE tenant_id = ? AND message_id_header = ?",
                UUID.class, tenantId, header);
    }

    private long headerCount(String header) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM email_message WHERE tenant_id = ? AND message_id_header = ?",
                Long.class, tenantId, header);
    }

    private Map<String, Object> row(UUID messageId) {
        return jdbc.queryForMap(
                "SELECT message_id, message_at, received_at, sent_at, contact_id, direction "
                        + "FROM email_message WHERE message_id = ?", messageId);
    }

    private static Instant instant(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : ((Timestamp) value).toInstant();
    }

    /** Mapper JSON zbliżony do konfiguracji Spring (daty ISO-8601, nie liczby). */
    private static ObjectMapper legacyMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** Kształt zdarzenia sprzed BE-134 (bez {@code messageAt}) — do testu wdrożenia kroczącego. */
    public record PreBe134EmailEvent(
            EmailEventPublisher.EventType eventType,
            UUID messageId,
            UUID tenantId,
            UUID contactId,
            UUID agentId,
            UUID queueId,
            String fromAddress,
            String subject,
            String direction,
            Instant timestamp,
            Map<String, String> metadata
    ) {}
}
