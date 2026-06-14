package com.contactcenter.domain.email;

import com.contactcenter.domain.model.EmailMessage;
import com.contactcenter.domain.model.EmailRoutingRule;
import com.contactcenter.domain.queue.Queue;
import com.contactcenter.domain.repository.EmailMessageRepository;
import com.contactcenter.domain.repository.EmailRoutingRuleRepository;
import com.contactcenter.domain.queue.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link EmailRoutingService}.
 *
 * <p>Weryfikuje logikę routingu wiadomości do kolejek:
 * <ul>
 *   <li>Dopasowanie reguły po subject (CONTAINS)</li>
 *   <li>Dopasowanie reguły po from_address (CONTAINS)</li>
 *   <li>Fallback na kolejkę przypisaną do adresu email (to_address)</li>
 *   <li>Fallback na kolejkę domyślną z tenant.config</li>
 *   <li>Brak reguł i brak kolejki domyślnej – brak publikacji queued</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailRoutingService – routing wiadomości do kolejek")
class EmailRoutingServiceTest {

    private static final UUID TENANT_ID      = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MESSAGE_ID     = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID QUEUE_VIP      = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID QUEUE_URGENT   = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID QUEUE_DEFAULT  = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID QUEUE_BY_EMAIL = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Mock
    private EmailMessageRepository emailMessageRepository;
    @Mock
    private EmailEventPublisher emailEventPublisher;
    @Mock
    private EmailRoutingRuleRepository routingRuleRepository;
    @Mock
    private QueueService queueService;

    private EmailRoutingService routingService;

    @BeforeEach
    void setUp() {
        routingService = new EmailRoutingService(
                emailMessageRepository, emailEventPublisher, routingRuleRepository, queueService);
    }

    // =========================================================================
    // Dopasowanie reguł
    // =========================================================================

    @Nested
    @DisplayName("matchesRule() – ewaluacja warunków")
    class MatchesRule {

        @Test
        @DisplayName("reguła CONTAINS na subject powinna dopasować tekst case-insensitive")
        void shouldMatchSubjectContains() {
            EmailMessage message = buildMessage("URGENT: Prośba klienta", "client@vip.com");
            EmailRoutingRule rule = buildRule(
                    """
                    [{"field": "subject", "operator": "CONTAINS", "value": "urgent"}]
                    """, QUEUE_URGENT);

            assertThat(routingService.matchesRule(message, rule)).isTrue();
        }

        @Test
        @DisplayName("reguła CONTAINS na from_address powinna dopasować domenę klienta VIP")
        void shouldMatchFromAddressContains() {
            EmailMessage message = buildMessage("Zapytanie", "jan.kowalski@vip-client.com");
            EmailRoutingRule rule = buildRule(
                    """
                    [{"field": "from_address", "operator": "CONTAINS", "value": "@vip-client.com"}]
                    """, QUEUE_VIP);

            assertThat(routingService.matchesRule(message, rule)).isTrue();
        }

        @Test
        @DisplayName("reguła EQUALS na to_address powinna dopasować dokładny adres docelowy")
        void shouldMatchToAddressEquals() {
            EmailMessage message = buildMessageWithTo("Temat", "sender@test.com", "support@mycompany.com");
            EmailRoutingRule rule = buildRule(
                    """
                    [{"field": "to_address", "operator": "EQUALS", "value": "support@mycompany.com"}]
                    """, QUEUE_DEFAULT);

            assertThat(routingService.matchesRule(message, rule)).isTrue();
        }

        @Test
        @DisplayName("reguła REGEX na subject powinna dopasować wyrażenie regularne")
        void shouldMatchSubjectRegex() {
            EmailMessage message = buildMessage("TICKET-12345: Problem", "user@example.com");
            EmailRoutingRule rule = buildRule(
                    """
                    [{"field": "subject", "operator": "REGEX", "value": "^TICKET-\\\\d+.*"}]
                    """, QUEUE_URGENT);

            assertThat(routingService.matchesRule(message, rule)).isTrue();
        }

        @Test
        @DisplayName("reguła z wieloma warunkami AND powinna wymagać spełnienia wszystkich")
        void shouldRequireAllConditionsToMatch() {
            EmailMessage messageMatch = buildMessageWithTo("URGENT ważne", "vip@vip.com", "support@co.com");
            EmailMessage messageNoMatch = buildMessage("URGENT ważne", "user@other.com");

            EmailRoutingRule rule = buildRule(
                    """
                    [
                      {"field": "subject", "operator": "CONTAINS", "value": "URGENT"},
                      {"field": "from_address", "operator": "CONTAINS", "value": "@vip.com"}
                    ]
                    """, QUEUE_VIP);

            assertThat(routingService.matchesRule(messageMatch, rule)).isTrue();
            assertThat(routingService.matchesRule(messageNoMatch, rule)).isFalse();
        }

        @Test
        @DisplayName("pusta lista warunków powinna dopasować każdą wiadomość (catch-all)")
        void shouldMatchAllForEmptyConditions() {
            EmailMessage message = buildMessage("Dowolny temat", "anyone@anywhere.com");
            EmailRoutingRule rule = buildRule("[]", QUEUE_DEFAULT);

            assertThat(routingService.matchesRule(message, rule)).isTrue();
        }

        @Test
        @DisplayName("reguła nie dopasowana powinna zwrócić false")
        void shouldReturnFalseWhenNotMatching() {
            EmailMessage message = buildMessage("Zwykły temat", "normal@user.com");
            EmailRoutingRule rule = buildRule(
                    """
                    [{"field": "subject", "operator": "CONTAINS", "value": "VIP"}]
                    """, QUEUE_VIP);

            assertThat(routingService.matchesRule(message, rule)).isFalse();
        }
    }

    // =========================================================================
    // route() – pełny flow
    // =========================================================================

    @Nested
    @DisplayName("route() – publikacja eventu email.queued")
    class Route {

        @Test
        @DisplayName("powinien opublikować email.queued gdy reguła pasuje")
        void shouldPublishQueuedEventWhenRuleMatches() {
            EmailMessage message = buildMessage("URGENT: pomoc", "vip@vip.com");
            EmailRoutingRule urgentRule = buildRule(
                    """
                    [{"field": "subject", "operator": "CONTAINS", "value": "URGENT"}]
                    """, QUEUE_URGENT);

            when(routingRuleRepository.findActiveByTenantId(TENANT_ID))
                    .thenReturn(List.of(urgentRule));

            routingService.route(message, Map.of());

            ArgumentCaptor<UUID> queueCaptor = ArgumentCaptor.forClass(UUID.class);
            verify(emailEventPublisher).publishQueued(eq(message), queueCaptor.capture());
            assertThat(queueCaptor.getValue()).isEqualTo(QUEUE_URGENT);
        }

        @Test
        @DisplayName("powinna użyć kolejki domyślnej z tenant.config gdy brak pasującej reguły")
        void shouldFallbackToDefaultQueue() {
            EmailMessage message = buildMessage("Zwykła wiadomość", "user@example.com");

            when(routingRuleRepository.findActiveByTenantId(TENANT_ID))
                    .thenReturn(List.of()); // brak reguł

            Map<String, Object> tenantConfig = Map.of(
                    "email_default_queue_id", QUEUE_DEFAULT.toString());

            routingService.route(message, tenantConfig);

            ArgumentCaptor<UUID> queueCaptor = ArgumentCaptor.forClass(UUID.class);
            verify(emailEventPublisher).publishQueued(eq(message), queueCaptor.capture());
            assertThat(queueCaptor.getValue()).isEqualTo(QUEUE_DEFAULT);
        }

        @Test
        @DisplayName("pierwsza pasująca reguła (priorytet) powinna wygrać")
        void shouldUseFirstMatchingRuleByPriority() {
            EmailMessage message = buildMessage("URGENT VIP klient", "vip@vip.com");

            // Reguła 1 (priorytet 10) – subject CONTAINS "URGENT"
            EmailRoutingRule rule1 = buildRuleWithPriority(
                    """
                    [{"field": "subject", "operator": "CONTAINS", "value": "URGENT"}]
                    """, QUEUE_URGENT, 10);

            // Reguła 2 (priorytet 20) – from CONTAINS "@vip.com"
            EmailRoutingRule rule2 = buildRuleWithPriority(
                    """
                    [{"field": "from_address", "operator": "CONTAINS", "value": "@vip.com"}]
                    """, QUEUE_VIP, 20);

            // Repository zwraca posortowane po priorytecie (10, 20)
            when(routingRuleRepository.findActiveByTenantId(TENANT_ID))
                    .thenReturn(List.of(rule1, rule2));

            routingService.route(message, Map.of());

            // Pierwsza pasująca (niższy priorytet) powinna wygrać
            verify(emailEventPublisher).publishQueued(eq(message), eq(QUEUE_URGENT));
            verify(emailEventPublisher, never()).publishQueued(eq(message), eq(QUEUE_VIP));
        }

        @Test
        @DisplayName("powinien użyć kolejki przypisanej do adresu email gdy brak pasującej reguły")
        void shouldFallbackToQueueByEmailAddress() {
            EmailMessage message = buildMessageWithTo("Wiadomość na znany adres",
                    "user@external.com", "sales@mycompany.com");

            when(routingRuleRepository.findActiveByTenantId(TENANT_ID))
                    .thenReturn(List.of()); // brak reguł

            Queue salesQueue = Queue.builder()
                    .queueId(QUEUE_BY_EMAIL)
                    .tenantId(TENANT_ID)
                    .name("Sales Queue")
                    .emailAddress("sales@mycompany.com")
                    .routingStrategy("ROUND_ROBIN")
                    .active(true)
                    .build();
            when(queueService.findQueueByEmailAddress("sales@mycompany.com", TENANT_ID))
                    .thenReturn(java.util.Optional.of(salesQueue));

            routingService.route(message, Map.of());

            ArgumentCaptor<UUID> queueCaptor = ArgumentCaptor.forClass(UUID.class);
            verify(emailEventPublisher).publishQueued(eq(message), queueCaptor.capture());
            assertThat(queueCaptor.getValue()).isEqualTo(QUEUE_BY_EMAIL);
        }

        @Test
        @DisplayName("powinien wziąć pierwszy adres z listy to_address gdy wiele adresów rozdzielonych przecinkiem")
        void shouldUsePrimaryToAddressFromCommaList() {
            EmailMessage message = buildMessageWithTo("Temat", "sender@ext.com",
                    "sales@mycompany.com, info@mycompany.com");

            when(routingRuleRepository.findActiveByTenantId(TENANT_ID))
                    .thenReturn(List.of());

            Queue salesQueue = Queue.builder()
                    .queueId(QUEUE_BY_EMAIL)
                    .tenantId(TENANT_ID)
                    .name("Sales Queue")
                    .emailAddress("sales@mycompany.com")
                    .routingStrategy("ROUND_ROBIN")
                    .active(true)
                    .build();
            when(queueService.findQueueByEmailAddress("sales@mycompany.com", TENANT_ID))
                    .thenReturn(java.util.Optional.of(salesQueue));

            routingService.route(message, Map.of());

            verify(emailEventPublisher).publishQueued(eq(message), eq(QUEUE_BY_EMAIL));
        }

        @Test
        @DisplayName("reguły powinny mieć wyższy priorytet niż fallback po adresie email kolejki")
        void shouldPreferRulesOverEmailAddressFallback() {
            EmailMessage message = buildMessageWithTo("URGENT", "vip@vip.com", "sales@mycompany.com");

            EmailRoutingRule urgentRule = buildRule(
                    """
                    [{"field": "subject", "operator": "CONTAINS", "value": "URGENT"}]
                    """, QUEUE_URGENT);

            when(routingRuleRepository.findActiveByTenantId(TENANT_ID))
                    .thenReturn(List.of(urgentRule));

            routingService.route(message, Map.of());

            verify(emailEventPublisher).publishQueued(eq(message), eq(QUEUE_URGENT));
            // queueService nie powinno być odpytywane – reguła wygrała wcześniej
            verify(queueService, never()).findQueueByEmailAddress(any(), any());
        }

        @Test
        @DisplayName("brak reguł i brak kolejki domyślnej – publishQueued NIE powinien być wywołany")
        void shouldNotPublishQueuedWhenNoRulesAndNoDefault() {
            EmailMessage message = buildMessage("Wiadomość bez routingu", "user@example.com");

            when(routingRuleRepository.findActiveByTenantId(TENANT_ID))
                    .thenReturn(List.of());
            // queueService zwraca empty (domyślne zachowanie Mockito dla Optional)

            routingService.route(message, Map.of()); // brak email_default_queue_id

            verify(emailEventPublisher, never()).publishQueued(any(), any());
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private EmailMessage buildMessage(String subject, String fromAddress) {
        return EmailMessage.builder()
                .id(MESSAGE_ID)
                .tenantId(TENANT_ID)
                .direction("INBOUND")
                .fromAddress(fromAddress)
                .toAddress("support@company.com")
                .subject(subject)
                .receivedAt(Instant.now())
                .build();
    }

    private EmailMessage buildMessageWithTo(String subject, String fromAddress, String toAddress) {
        return EmailMessage.builder()
                .id(MESSAGE_ID)
                .tenantId(TENANT_ID)
                .direction("INBOUND")
                .fromAddress(fromAddress)
                .toAddress(toAddress)
                .subject(subject)
                .receivedAt(Instant.now())
                .build();
    }

    private EmailRoutingRule buildRule(String conditions, UUID queueId) {
        return buildRuleWithPriority(conditions, queueId, 100);
    }

    private EmailRoutingRule buildRuleWithPriority(String conditions, UUID queueId, int priority) {
        return EmailRoutingRule.builder()
                .ruleId(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .name("Test rule")
                .queueId(queueId)
                .conditions(conditions.strip())
                .priority(priority)
                .isActive(true)
                .build();
    }
}
