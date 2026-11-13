package com.contactcenter.domain.email;

import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.Customer;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.domain.websocket.ContactMessageReceivedPublisher;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BE-154 (e-mail path) on a REAL PostgreSQL (Testcontainers + Flyway, role without BYPASSRLS):
 * a follow-up e-mail (In-Reply-To -> message of an open, assigned contact) is attached to that contact and
 * publishes exactly one {@code CONTACT_MESSAGE_RECEIVED} to the assigned agent; the first message of a
 * thread keeps the routing path and publishes nothing.
 */
@DisplayName("BE-154: follow-up e-mail -> CONTACT_MESSAGE_RECEIVED (Testcontainers)")
class EmailFollowUpEventIntegrationTest {

    private static final String ROLE = "cc_be154_email";
    private static final String TEST_KEY = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
    private static final Instant PARENT_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant INTERNAL_DATE = Instant.parse("2026-09-01T09:15:30.123Z");

    private static HikariDataSource superuserPool;
    private static HikariDataSource restrictedPool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static EmailMessageRepository repository;

    private UUID tenantId;
    private Tenant tenant;
    private UUID agentId;
    private UUID contactId;
    private EmailRoutingService routing;
    private ContactService contactService;
    private CustomerService customerService;
    private WebSocketEventBroadcaster broadcaster;
    private EmailPollingServiceImpl poller;
    private EmailFollowUpJoiner joiner;
    private EmailEmlService emlService;
    private Instant contactStartedAt;
    private String parentHeader;

    @BeforeAll
    static void startContext() {
        superuserPool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(superuserPool);
        String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, ROLE);
        restrictedPool = PostgresTestDatabase.pool(ROLE, password, 4);
        ctx = JpaTestContext.create(restrictedPool, new Class<?>[]{EmailMessage.class},
                new Class<?>[]{EmailMessageRepository.class}, null);
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
        tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-154 " + UUID.randomUUID());
        tenant = buildTenant(tenantId);
        TenantContext.setTenantId(tenantId);
        agentId = UUID.randomUUID();
        contactId = UUID.randomUUID();
        routing = mock(EmailRoutingService.class);
        contactService = mock(ContactService.class);
        customerService = mock(CustomerService.class);
        broadcaster = mock(WebSocketEventBroadcaster.class);
        emlService = mock(EmailEmlService.class);
        joiner = new EmailFollowUpJoiner(repository, contactService, customerService,
                new ContactMessageReceivedPublisher(broadcaster), emlService);
        poller = new EmailPollingServiceImpl(mock(TenantService.class), repository, routing,
                mock(EmailEventPublisher.class), new EmailEncryptionServiceImpl(TEST_KEY),
                mock(EmailAttachmentStorageService.class), new ObjectMapper(), joiner,
                com.contactcenter.support.NoOpLockProvider.executor());
        insertAgentAndContact("ACTIVE", "EMAIL");

        parentHeader = "<parent-" + UUID.randomUUID() + "@ext.example>";
        repository.save(EmailMessage.builder().tenantId(tenantId).contactId(contactId)
                .direction("INBOUND").fromAddress("klient@example.com").toAddress("biuro@example.com")
                .subject("Pierwsza").bodyText("tresc").messageIdHeader(parentHeader)
                .receivedAt(PARENT_AT).messageAt(PARENT_AT).build());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM email_message WHERE tenant_id = ?", tenantId);
    }

    /** Real agent + contact rows: the atomic UPDATE re-checks the contact state in the database. */
    private void insertAgentAndContact(String status, String channel) {
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status) "
                        + "VALUES (?, ?, 'AGENT', ?, ?, 'AVAILABLE')",
                agentId, tenantId, "a-" + agentId + "@test.local", "h".repeat(60));
        jdbc.update("INSERT INTO contact (contact_id, tenant_id, agent_id, channel, direction, status, "
                        + "remote_address, created_at, updated_at) VALUES (?, ?, ?, ?, 'INBOUND', ?, "
                        + "'klient@example.com', now(), now())",
                contactId, tenantId, agentId, channel, status);
        contactStartedAt = jdbc.queryForObject("SELECT started_at FROM contact WHERE contact_id = ?",
                java.sql.Timestamp.class, contactId).toInstant();
    }

    private void setDbContactStatus(String status) {
        jdbc.update("UPDATE contact SET status = ? WHERE contact_id = ?", status, contactId);
    }

    private Contact contact(String status, UUID agent) {
        return Contact.builder().contactId(contactId).tenantId(tenantId).channel("EMAIL").status(status)
                .agentId(agent).startedAt(contactStartedAt)
                .remoteAddress("Klient <KLIENT@example.com> ").build();
    }

    @Test
    @DisplayName("follow-up w kontakcie z agentem: dokladnie 1 zdarzenie do tego agenta, payload bez tresci, wiadomosc w kontakcie, bez routingu")
    void followUpPublishesOneEventToAssignedAgent() throws Exception {
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(contact("ACTIVE", agentId)));
        String header = "<follow-" + UUID.randomUUID() + "@ext.example>";

        pollOnce(tenant, mime(header, parentHeader));

        UUID messageId = jdbc.queryForObject("SELECT message_id FROM email_message WHERE message_id_header = ?",
                UUID.class, header);
        assertThat(jdbc.queryForObject("SELECT contact_id FROM email_message WHERE message_id = ?",
                UUID.class, messageId)).isEqualTo(contactId);
        verify(routing, never()).route(any(), any());

        ArgumentCaptor<WebSocketEvent> captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(broadcaster, times(1)).sendToUser(eq(agentId), captor.capture());
        verify(broadcaster, never()).sendToTenantSupervisors(any(), any());
        verify(broadcaster, never()).sendToTenantAgents(any(), any());
        WebSocketEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("CONTACT_MESSAGE_RECEIVED");
        assertThat(event.tenantId()).isEqualTo(tenantId);
        WebSocketEvent.ContactMessageReceivedPayload p = (WebSocketEvent.ContactMessageReceivedPayload) event.payload();
        assertThat(p.contactId()).isEqualTo(contactId.toString());
        assertThat(p.channel()).isEqualTo("EMAIL");
        assertThat(p.messageId()).isEqualTo(messageId.toString());
        assertThat(p.receivedAt()).isEqualTo(INTERNAL_DATE);

        String json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(p);
        assertThat(json).contains("\"receivedAt\":\"2026-09-01T09:15:30.123Z\"")
                .doesNotContain("Temat follow-up").doesNotContain("Tresc follow-up").doesNotContain("klient@");
        assertThat(new ObjectMapper().readTree(json).fieldNames())
                .toIterable().containsExactlyInAnyOrder("contactId", "channel", "messageId", "receivedAt");
    }

    @Test
    @DisplayName("follow-up: generuje EML per wiadomosc, zapisuje klucz w email_message.eml_s3_key, nie rusza kontaktu")
    void followUpGeneratesPerMessageEmlAndStoresKey() throws Exception {
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(contact("ACTIVE", agentId)));
        String emlKey = tenantId + "/2026/09/" + contactId + "/key.eml";
        when(emlService.generateAndUploadForMessage(any(), eq(contactId), eq(tenantId))).thenReturn(emlKey);
        String header = "<follow-eml-" + UUID.randomUUID() + "@ext.example>";

        pollOnce(tenant, mime(header, parentHeader));

        ArgumentCaptor<EmailMessage> generated = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emlService).generateAndUploadForMessage(generated.capture(), eq(contactId), eq(tenantId));
        assertThat(generated.getValue().getContactId()).as("EML generowany po dolaczeniu do kontaktu").isEqualTo(contactId);
        assertThat(jdbc.queryForObject("SELECT eml_s3_key FROM email_message WHERE message_id_header = ?",
                String.class, header)).isEqualTo(emlKey);
        // pierwsza wiadomosc (rodzic) nie jest ruszana
        assertThat(jdbc.queryForObject("SELECT eml_s3_key FROM email_message WHERE message_id_header = ?",
                String.class, parentHeader)).isNull();
        // contact.recording_url (EML pierwszej wiadomosci) nie jest nadpisywany
        verify(contactService, never()).updateRecordingUrl(any(), any(), any());
        verify(broadcaster, times(1)).sendToUser(eq(agentId), any());
    }

    @Test
    @DisplayName("blad generowania EML NIE psuje dolaczenia: wiadomosc w kontakcie, zdarzenie WS wyslane, eml_s3_key = NULL, bez routingu")
    void emlFailureDoesNotBreakJoin() throws Exception {
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(contact("ACTIVE", agentId)));
        when(emlService.generateAndUploadForMessage(any(), any(), any()))
                .thenThrow(new EmailEmlService.EmailEmlException("s3 down", new RuntimeException()));
        String header = "<follow-emlfail-" + UUID.randomUUID() + "@ext.example>";

        pollOnce(tenant, mime(header, parentHeader));

        assertThat(jdbc.queryForObject("SELECT contact_id FROM email_message WHERE message_id_header = ?",
                UUID.class, header)).isEqualTo(contactId);
        assertThat(jdbc.queryForObject("SELECT eml_s3_key FROM email_message WHERE message_id_header = ?",
                String.class, header)).isNull();
        verify(routing, never()).route(any(), any());
        verify(broadcaster, times(1)).sendToUser(eq(agentId), any());
    }

    @Test
    @DisplayName("pierwsza wiadomosc watku (bez In-Reply-To): routing jak dotad, brak zdarzenia")
    void firstMessageKeepsRoutingAndPublishesNothing() throws Exception {
        pollOnce(tenant, mime("<first-" + UUID.randomUUID() + "@ext.example>", null));

        verify(routing).route(any(), any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("kontakt bez agenta (QUEUED): brak zdarzenia i brak dolaczenia (jak dotad nowy kontakt przez routing)")
    void contactWithoutAgentPublishesNothing() throws Exception {
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(contact("QUEUED", null)));

        pollOnce(tenant, mime("<noagent-" + UUID.randomUUID() + "@ext.example>", parentHeader));

        verify(routing).route(any(), any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("kontakt zakonczony (COMPLETED) z agentem: brak zdarzenia, nowy kontakt przez routing")
    void completedContactIsNotJoined() throws Exception {
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(contact("COMPLETED", agentId)));

        pollOnce(tenant, mime("<done-" + UUID.randomUUID() + "@ext.example>", parentHeader));

        verify(routing).route(any(), any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("izolacja tenantow: odpowiedz w tenancie B na Message-ID z tenanta A nie dolacza i nie powiadamia agenta A")
    void otherTenantNeverReceives() throws Exception {
        UUID tenantB = PostgresTestDatabase.insertTenant(jdbc, "Tenant B BE-154 " + UUID.randomUUID());
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(contact("ACTIVE", agentId)));
        TenantContext.setTenantId(tenantB);
        try {
            pollOnce(buildTenant(tenantB), mime("<cross-" + UUID.randomUUID() + "@ext.example>", parentHeader));
        } finally {
            jdbc.update("DELETE FROM email_message WHERE tenant_id = ?", tenantB);
        }

        verifyNoInteractions(broadcaster);
        verify(routing).route(any(), any());
        verify(contactService, never()).findContactEntity(eq(contactId), eq(tenantB));
    }

    @Test
    @DisplayName("blad nadawania WS nie przerywa przyjecia wiadomosci")
    void broadcastFailureDoesNotFailIngestion() throws Exception {
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(contact("ACTIVE", agentId)));
        org.mockito.Mockito.doThrow(new RuntimeException("ws down")).when(broadcaster).sendToUser(any(), any());
        String header = "<boom-" + UUID.randomUUID() + "@ext.example>";

        pollOnce(tenant, mime(header, parentHeader));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_message WHERE message_id_header = ?",
                Integer.class, header)).isEqualTo(1);
    }


    @Test
    @DisplayName("ON_HOLD z agentem: follow-up dolacza i powiadamia agenta")
    void onHoldContactIsJoined() throws Exception {
        setDbContactStatus("ON_HOLD");
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(contact("ON_HOLD", agentId)));

        pollOnce(tenant, mime("<hold-" + UUID.randomUUID() + "@ext.example>", parentHeader));

        verify(broadcaster, times(1)).sendToUser(eq(agentId), any());
        verify(routing, never()).route(any(), any());
    }

    @Test
    @DisplayName("kontakt nie-EMAIL: brak dolaczenia i zdarzenia")
    void nonEmailContactIsNotJoined() throws Exception {
        Contact c = contact("ACTIVE", agentId);
        c.setChannel("SOCIAL_FACEBOOK");
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(c));

        pollOnce(tenant, mime("<nonmail-" + UUID.randomUUID() + "@ext.example>", parentHeader));

        verify(routing).route(any(), any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("nadawca spoza stron kontaktu (spoof): rutowane jak nowe, brak zdarzenia, brak contact_id")
    void unknownSenderIsRoutedNotJoined() throws Exception {
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(contact("ACTIVE", agentId)));
        String header = "<spoof-" + UUID.randomUUID() + "@ext.example>";

        pollOnce(tenant, mime(header, parentHeader, "obcy@evil.example"));

        verify(routing).route(any(), any());
        verifyNoInteractions(broadcaster);
        assertThat(jdbc.queryForObject("SELECT contact_id FROM email_message WHERE message_id_header = ?",
                UUID.class, header)).isNull();
    }

    @Test
    @DisplayName("wlasna skrzynka tenanta (adres odbiorcy watku) nie jest strona klienta")
    void ownMailboxIsNotAParty() throws Exception {
        Contact c = contact("ACTIVE", agentId);
        c.setRemoteAddress(null); // force thread-based matching: parent inbound has to=biuro@, from=klient@
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(c));

        pollOnce(tenant, mime("<own-" + UUID.randomUUID() + "@ext.example>", parentHeader, "biuro@example.com"));

        verify(routing).route(any(), any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("nadawca znany z wiadomosci watku (bez remoteAddress kontaktu): dolacza")
    void senderKnownFromThreadIsJoined() throws Exception {
        Contact c = contact("ACTIVE", agentId);
        c.setRemoteAddress(null);
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(c));

        pollOnce(tenant, mime("<thr-" + UUID.randomUUID() + "@ext.example>", parentHeader, "Klient <Klient@Example.com>"));

        verify(broadcaster, times(1)).sendToUser(eq(agentId), any());
    }

    @Test
    @DisplayName("nadawca = e-mail klienta kontaktu (customerId): dolacza")
    void senderMatchingCustomerEmailIsJoined() throws Exception {
        UUID customerId = UUID.randomUUID();
        Contact c = contact("ACTIVE", agentId);
        c.setRemoteAddress("ktos-inny@example.com");
        c.setCustomerId(customerId);
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(c));
        Customer customer = new Customer();
        customer.setEmail(List.of("Jan.Kowalski@Firma.example"));
        when(customerService.findById(customerId, tenantId)).thenReturn(Optional.of(customer));

        pollOnce(tenant, mime("<cust-" + UUID.randomUUID() + "@ext.example>", parentHeader, "jan.kowalski@firma.example"));

        verify(broadcaster, times(1)).sendToUser(eq(agentId), any());
    }

    @Test
    @DisplayName("kontakt zamkniety miedzy odczytem a UPDATE: 0 wierszy -> routing, brak zdarzenia")
    void contactClosedBetweenReadAndUpdateIsRouted() throws Exception {
        // the read still sees ACTIVE, the database row is already COMPLETED
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(contact("ACTIVE", agentId)));
        setDbContactStatus("COMPLETED");
        String header = "<race-" + UUID.randomUUID() + "@ext.example>";

        pollOnce(tenant, mime(header, parentHeader));

        verify(routing).route(any(), any());
        verifyNoInteractions(broadcaster);
        assertThat(jdbc.queryForObject("SELECT contact_id FROM email_message WHERE message_id_header = ?",
                UUID.class, header)).isNull();
    }

    @Test
    @DisplayName("wyjatek przy odczycie kontaktu: wiadomosc zachowana i rutowana jak nowa")
    void lookupFailureFallsBackToRouting() throws Exception {
        when(contactService.findContactEntity(contactId, tenantId)).thenThrow(new RuntimeException("db down"));
        String header = "<fail-" + UUID.randomUUID() + "@ext.example>";

        pollOnce(tenant, mime(header, parentHeader));

        verify(routing).route(any(), any());
        verifyNoInteractions(broadcaster);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_message WHERE message_id_header = ?",
                Integer.class, header)).isEqualTo(1);
    }

    @Test
    @DisplayName("ponowny polling tej samej wiadomosci: bez podwojnego dolaczenia i zdarzenia")
    void rePollDoesNotJoinOrPublishTwice() throws Exception {
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(contact("ACTIVE", agentId)));
        MimeMessage message = mime("<again-" + UUID.randomUUID() + "@ext.example>", parentHeader);

        pollOnce(tenant, message);
        pollOnce(tenant, message);

        verify(broadcaster, times(1)).sendToUser(eq(agentId), any());
        verify(routing, never()).route(any(), any());
    }

    @Test
    @DisplayName("spoof display-name (\"victim@\" <attacker@evil>): liczy sie adres koperty, nie dolacza")
    void displayNameSpoofIsNotJoined() throws Exception {
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(contact("ACTIVE", agentId)));

        pollOnce(tenant, mime("<dn-" + UUID.randomUUID() + "@ext.example>", parentHeader,
                new InternetAddress("attacker@evil.example", "klient@example.com")));

        verify(routing).route(any(), any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("wlasna skrzynka tenanta jako From nie dolacza, nawet gdy jest w outbound 'to' watku")
    void ownMailboxFromIsNotJoinedEvenIfInThreadOutbound() throws Exception {
        Contact c = contact("ACTIVE", agentId);
        c.setRemoteAddress(null);
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(c));
        Instant outAt = Instant.parse("2026-09-01T08:30:00Z");
        repository.save(EmailMessage.builder().tenantId(tenantId).contactId(contactId).direction("OUTBOUND")
                .fromAddress("agent@elsewhere.example").toAddress("inbox@company.com").subject("Re")
                .bodyText("x").messageIdHeader("<out-" + UUID.randomUUID() + "@ext.example>")
                .receivedAt(outAt).sentAt(outAt).messageAt(outAt).build());

        pollOnce(tenant, mime("<own2-" + UUID.randomUUID() + "@ext.example>", parentHeader, "inbox@company.com"));

        verify(routing).route(any(), any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("agent przepiety miedzy odczytem a UPDATE: brak dolaczenia (routing), zdarzenie nie idzie do starego agenta")
    void reassignedAgentBetweenReadAndUpdateIsRouted() throws Exception {
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(contact("ACTIVE", agentId)));
        UUID newAgent = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status) "
                        + "VALUES (?, ?, 'AGENT', ?, ?, 'AVAILABLE')",
                newAgent, tenantId, "n-" + newAgent + "@test.local", "h".repeat(60));
        jdbc.update("UPDATE contact SET agent_id = ? WHERE contact_id = ?", newAgent, contactId);

        pollOnce(tenant, mime("<reasg-" + UUID.randomUUID() + "@ext.example>", parentHeader));

        verify(routing).route(any(), any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("From z kilkoma adresami (lista): fail-closed, nie dolacza")
    void multiAddressFromIsNotJoined() throws Exception {
        when(contactService.findContactEntity(contactId, tenantId)).thenReturn(Optional.of(contact("ACTIVE", agentId)));
        Instant at = Instant.parse("2026-09-01T09:00:00Z");
        EmailMessage saved = repository.save(EmailMessage.builder().tenantId(tenantId).direction("INBOUND")
                .fromAddress("klient@example.com, obcy@evil.example").toAddress("biuro@example.com")
                .subject("x").bodyText("x").messageIdHeader("<multi-" + UUID.randomUUID() + "@ext.example>")
                .inReplyTo(parentHeader).receivedAt(at).messageAt(at).build()).orElseThrow();

        assertThat(joiner.tryJoin(saved, tenantId, "inbox@company.com")).isFalse();

        verifyNoInteractions(broadcaster);
        assertThat(saved.getContactId()).isNull();
    }

    @Test
    @DisplayName("maskowanie adresu w logach: brak adresu jawnym tekstem")
    void maskDoesNotExposeAddress() {
        assertThat(EmailFollowUpJoiner.mask("Klient <klient@example.com>")).isEqualTo("k***@example.com");
        assertThat(EmailFollowUpJoiner.mask(null)).isEqualTo("***");
    }

    private void pollOnce(Tenant t, MimeMessage message) throws Exception {
        EmailPollingServiceImpl spied = spy(poller);
        Store store = mock(Store.class);
        Folder inbox = mock(Folder.class);
        doReturn(store).when(spied).connectImap(any(), anyString());
        when(store.getFolder("INBOX")).thenReturn(inbox);
        when(inbox.search(any())).thenReturn(new Message[]{message});
        spied.pollTenantInbox(t);
    }

    private static MimeMessage mime(String header, String inReplyTo) throws Exception {
        return mime(header, inReplyTo, "klient@example.com");
    }

    private static MimeMessage mime(String header, String inReplyTo, String from) throws Exception {
        return mime(header, inReplyTo, new InternetAddress(from));
    }

    private static MimeMessage mime(String header, String inReplyTo, InternetAddress from) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            public Date getReceivedDate() {
                return Date.from(INTERNAL_DATE);
            }
        };
        message.setFrom(from);
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse("biuro@example.com"));
        message.setSubject("Temat follow-up");
        message.setHeader("Message-ID", header);
        if (inReplyTo != null) {
            message.setHeader("In-Reply-To", inReplyTo);
        }
        message.setText("Tresc follow-up");
        message.setFlag(Flags.Flag.SEEN, false);
        return message;
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
        return Tenant.builder().id(id).name("T BE-154").status(Tenant.TenantStatus.ACTIVE).config(config).build();
    }
}
