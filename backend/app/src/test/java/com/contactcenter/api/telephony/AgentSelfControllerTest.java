package com.contactcenter.api.telephony;

import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.queue.Queue;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.api.telephony.dto.AgentKpiResponse;
import com.contactcenter.security.JwtAuthFilter;
import com.contactcenter.security.JwtParser;
import com.contactcenter.security.JwtParser.JwtClaims;
import com.contactcenter.security.PasswordEncoderConfig;
import com.contactcenter.security.SecurityConfig;
import com.contactcenter.security.TenantContext;
import com.contactcenter.security.TenantFilter;
import com.contactcenter.security.TokenBlacklistService;
import com.contactcenter.security.UserDetailsServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testy {@link AgentSelfController} ({@code /api/agent/me/**}) przez MockMvc z PRAWDZIWYM
 * łańcuchem bezpieczeństwa ({@link SecurityConfig}, {@link JwtAuthFilter}, {@link TenantFilter}).
 *
 * <p>Wzorzec: ten sam co {@code PluginUiSdkStaticAssetIT} (lokalny {@code @SpringBootConfiguration},
 * bez {@code @EnableJpaRepositories}); {@link JwtParser}, blacklista i serwisy domenowe są mockowane.
 * Parser JWT zwraca claims przypisane do tokenu, a {@code UserDetailsServiceImpl} - użytkownika
 * z rolą z tych claims, więc {@code @PreAuthorize} i {@code TenantContext} są realnie uruchamiane.
 *
 * <p>Pokrycie: happy path, 204 przy braku kontaktu, role (AGENT/SUPERVISOR/ADMIN tak, SUPER_ADMIN nie),
 * 401 bez tokenu / z niepoprawnym tokenem, agentId i tenantId z JWT (nigdy z parametrów żądania),
 * izolacja tenantów, czyszczenie TenantContext po żądaniu.
 */
@WebMvcTest(controllers = AgentSelfController.class)
@Import({AgentSelfController.class, SecurityConfig.class, PasswordEncoderConfig.class, JwtAuthFilter.class, TenantFilter.class,
        UserDetailsServiceImpl.class, com.contactcenter.api.GlobalExceptionHandler.class})
@DisplayName("AgentSelfController - GET /api/agent/me/**")
class AgentSelfControllerTest {

    /** Minimalna klasa rozruchowa - bez {@code @EnableJpaRepositories}/{@code @EntityScan}. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class MinimalBootConfig {
    }

    private static final String ASSIGNED = "/api/agent/me/assigned-contact";
    private static final String KPI = "/api/agent/me/kpi";

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_AGENT_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Autowired
    private MockMvc mockMvc;

    @MockBean private JwtParser jwtParser;
    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private UserService userService;
    @MockBean private UserDetailsServiceImpl userDetailsService;
    @MockBean private ContactService contactService;
    @MockBean private QueueService queueService;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Rejestruje token "jwt-{role}" jako prawidłowy dla podanego użytkownika i roli; zwraca nagłówek. */
    private String bearerFor(String role, UUID tenantId, UUID userId) {
        String token = "jwt-" + role + "-" + userId;
        when(jwtParser.parse(token)).thenReturn(
                new JwtClaims(tenantId, userId, role, "user@example.com", Instant.now().plusSeconds(600)));
        when(tokenBlacklistService.isBlacklisted(token)).thenReturn(false);
        when(userDetailsService.loadUserByUsername(anyString())).thenReturn(
                new User("user@example.com", "x", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        return "Bearer " + token;
    }

    private Contact assignedContact(UUID queueId, UUID customerId) {
        return Contact.builder()
                .contactId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"))
                .tenantId(TENANT_ID)
                .agentId(AGENT_ID)
                .queueId(queueId)
                .customerId(customerId)
                .channel("PHONE")
                .status("ASSIGNED")
                .remoteAddress("+48501234567")
                .build();
    }

    /** Row shape of findAgentKpiToday after BE-158: [contacts_count, avg_handle_time, sla_percent]. */
    private static Object[] kpiRow(Object count, Object aht, Object sla) {
        return new Object[]{count, aht, sla};
    }


    // =========================================================================
    // GET /api/agent/me/assigned-contact
    // =========================================================================

    @Nested
    @DisplayName("GET /assigned-contact")
    class AssignedContact {

        @Test
        @DisplayName("200 z danymi kontaktu ASSIGNED, nazwą kolejki i customerId")
        void should_return200WithContact_when_agentHasAssignedContact() throws Exception {
            UUID queueId = UUID.randomUUID();
            UUID customerId = UUID.randomUUID();
            Contact contact = assignedContact(queueId, customerId);
            when(contactService.findAssignedContactForAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(queueService.findQueueEntity(queueId, TENANT_ID))
                    .thenReturn(Optional.of(Queue.builder().name("Sales PL").build()));

            mockMvc.perform(get(ASSIGNED).header("Authorization", bearerFor("AGENT", TENANT_ID, AGENT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.contactId").value(contact.getContactId().toString()))
                    .andExpect(jsonPath("$.channel").value("PHONE"))
                    .andExpect(jsonPath("$.customerIdentifier").value("+48501234567"))
                    .andExpect(jsonPath("$.queueName").value("Sales PL"))
                    .andExpect(jsonPath("$.customerId").value(customerId.toString()));
            // customerName celowo nieasertowane: obecnie fallback na remoteAddress, do zmiany w BE-157.
        }

        @Test
        @DisplayName("customerId jest null gdy klient nieznany")
        void should_returnNullCustomerId_when_customerUnknown() throws Exception {
            when(contactService.findAssignedContactForAgent(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.of(assignedContact(null, null)));

            mockMvc.perform(get(ASSIGNED).header("Authorization", bearerFor("AGENT", TENANT_ID, AGENT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customerId").doesNotExist());
        }

        @Test
        @DisplayName("queueName to pusty string gdy kontakt nie ma kolejki (bez wywołania QueueService)")
        void should_returnEmptyQueueName_when_contactHasNoQueue() throws Exception {
            when(contactService.findAssignedContactForAgent(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.of(assignedContact(null, null)));

            mockMvc.perform(get(ASSIGNED).header("Authorization", bearerFor("AGENT", TENANT_ID, AGENT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.queueName").value(""));
            verifyNoInteractions(queueService);
        }

        @Test
        @DisplayName("queueName to pusty string gdy kolejka nie istnieje")
        void should_returnEmptyQueueName_when_queueNotFound() throws Exception {
            UUID queueId = UUID.randomUUID();
            when(contactService.findAssignedContactForAgent(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.of(assignedContact(queueId, null)));
            when(queueService.findQueueEntity(queueId, TENANT_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get(ASSIGNED).header("Authorization", bearerFor("AGENT", TENANT_ID, AGENT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.queueName").value(""));
        }

        @Test
        @DisplayName("błąd QueueService nie psuje odpowiedzi: 200 z pustym queueName")
        void should_return200WithEmptyQueueName_when_queueLookupThrows() throws Exception {
            UUID queueId = UUID.randomUUID();
            when(contactService.findAssignedContactForAgent(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.of(assignedContact(queueId, null)));
            when(queueService.findQueueEntity(queueId, TENANT_ID)).thenThrow(new RuntimeException("db down"));

            mockMvc.perform(get(ASSIGNED).header("Authorization", bearerFor("AGENT", TENANT_ID, AGENT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.queueName").value(""))
                    .andExpect(jsonPath("$.contactId").exists());
        }

        @Test
        @DisplayName("204 bez body gdy agent nie ma kontaktu ASSIGNED")
        void should_return204_when_noAssignedContact() throws Exception {
            when(contactService.findAssignedContactForAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get(ASSIGNED).header("Authorization", bearerFor("AGENT", TENANT_ID, AGENT_ID)))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
        }

        @Test
        @DisplayName("agentId i tenantId pochodzą z JWT, parametry żądania agentId/tenantId są ignorowane")
        void should_useAgentAndTenantFromJwt_when_requestParamsTryToOverride() throws Exception {
            when(contactService.findAssignedContactForAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get(ASSIGNED)
                            .param("agentId", OTHER_AGENT_ID.toString())
                            .param("tenantId", OTHER_TENANT_ID.toString())
                            .header("X-Tenant-Id", OTHER_TENANT_ID.toString())
                            .header("Authorization", bearerFor("AGENT", TENANT_ID, AGENT_ID)))
                    .andExpect(status().isNoContent());

            verify(contactService).findAssignedContactForAgent(AGENT_ID, TENANT_ID);
            verify(contactService, never()).findAssignedContactForAgent(eq(OTHER_AGENT_ID), any());
            verify(contactService, never()).findAssignedContactForAgent(any(), eq(OTHER_TENANT_ID));
        }

        @Test
        @DisplayName("izolacja tenantów: ten sam agent w tenancie B nie widzi kontaktu z tenanta A")
        void should_scopeLookupToTenantFromJwt_when_sameAgentIdInDifferentTenant() throws Exception {
            when(contactService.findAssignedContactForAgent(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.of(assignedContact(null, null)));
            when(contactService.findAssignedContactForAgent(AGENT_ID, OTHER_TENANT_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get(ASSIGNED).header("Authorization", bearerFor("AGENT", OTHER_TENANT_ID, AGENT_ID)))
                    .andExpect(status().isNoContent());
        }

        @ParameterizedTest(name = "rola {0} ma dostęp (200)")
        @ValueSource(strings = {"AGENT", "SUPERVISOR", "ADMIN"})
        @DisplayName("role AGENT/SUPERVISOR/ADMIN są dopuszczone")
        void should_allowRole(String role) throws Exception {
            when(contactService.findAssignedContactForAgent(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.of(assignedContact(null, null)));

            mockMvc.perform(get(ASSIGNED).header("Authorization", bearerFor(role, TENANT_ID, AGENT_ID)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("SUPER_ADMIN (bez tenanta) dostaje 403 i serwis nie jest wywoływany")
        void should_return403_when_superAdmin() throws Exception {
            mockMvc.perform(get(ASSIGNED).header("Authorization", bearerFor("SUPER_ADMIN", null, AGENT_ID)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(contactService);
        }

        @Test
        @DisplayName("401 bez nagłówka Authorization")
        void should_return401_when_noToken() throws Exception {
            mockMvc.perform(get(ASSIGNED)).andExpect(status().isUnauthorized());

            verifyNoInteractions(contactService);
        }

        @Test
        @DisplayName("401 przy niepoprawnym tokenie JWT")
        void should_return401_when_invalidToken() throws Exception {
            when(jwtParser.parse("garbage")).thenThrow(new JwtParser.JwtValidationException("bad"));

            mockMvc.perform(get(ASSIGNED).header("Authorization", "Bearer garbage"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(contactService);
        }

        @Test
        @DisplayName("TenantContext jest czyszczony po żądaniu (brak wycieku przez pulę wątków)")
        void should_clearTenantContext_afterRequest() throws Exception {
            when(contactService.findAssignedContactForAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get(ASSIGNED).header("Authorization", bearerFor("AGENT", TENANT_ID, AGENT_ID)))
                    .andExpect(status().isNoContent());

            // getTenantId() rzuca IllegalStateException, gdy kontekst nie jest ustawiony.
            assertThatThrownBy(TenantContext::getTenantId).isInstanceOf(IllegalStateException.class);
        }
    }

    // =========================================================================
    // GET /api/agent/me/kpi
    // =========================================================================

    @Nested
    @DisplayName("GET /kpi")
    class Kpi {

        @Test
        @DisplayName("200 z zamapowanymi metrykami KPI; czas pracy od logowania")
        void should_return200WithMappedKpi_when_agentHandledContacts() throws Exception {
            // kolejność wiersza: [count, avgHandleTime, slaPercent] - różne typy Number jak z natywnego SQL
            when(contactService.findAgentKpiToday(eq(AGENT_ID), eq(TENANT_ID), any(), any()))
                    .thenReturn(kpiRow(7L, new java.math.BigDecimal("123.5"), 85.7d));
            when(userService.findLastLoginAt(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.of(Instant.now().minusSeconds(60)));

            mockMvc.perform(get(KPI).header("Authorization", bearerFor("AGENT", TENANT_ID, AGENT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.contactsHandledToday").value(7))
                    .andExpect(jsonPath("$.avgHandleTimeSeconds").value(123.5))
                    .andExpect(jsonPath("$.slaPercent").value(85.7))
                    // login 60 s temu (o ile test nie przekroczy północy UTC) - tolerancja na czas wykonania
                    .andExpect(jsonPath("$.workTimeSeconds").value(
                            org.hamcrest.Matchers.allOf(
                                    org.hamcrest.Matchers.greaterThanOrEqualTo(0),
                                    org.hamcrest.Matchers.lessThanOrEqualTo(65))));
        }

        @Test
        @DisplayName("200 z samymi zerami gdy agent nie obsłużył dziś kontaktu (stan pusty)")
        void should_return200WithZeros_when_noContactsToday() throws Exception {
            when(contactService.findAgentKpiToday(eq(AGENT_ID), eq(TENANT_ID), any(), any()))
                    .thenReturn(kpiRow(0L, 0, 0));

            mockMvc.perform(get(KPI).header("Authorization", bearerFor("AGENT", TENANT_ID, AGENT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.contactsHandledToday").value(0))
                    .andExpect(jsonPath("$.avgHandleTimeSeconds").value(0.0))
                    .andExpect(jsonPath("$.slaPercent").value(0.0))
                    .andExpect(jsonPath("$.workTimeSeconds").value(0));
        }

        @Test
        @DisplayName("zakres dnia to pełna doba UTC [00:00, +24h) obejmująca bieżącą chwilę")
        void should_queryFullUtcDay_when_calculatingKpi() throws Exception {
            when(contactService.findAgentKpiToday(any(), any(), any(), any())).thenReturn(kpiRow(0L, 0, 0));
            Instant before = Instant.now();

            mockMvc.perform(get(KPI).header("Authorization", bearerFor("AGENT", TENANT_ID, AGENT_ID)))
                    .andExpect(status().isOk());

            Instant after = Instant.now();
            ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
            verify(contactService).findAgentKpiToday(eq(AGENT_ID), eq(TENANT_ID), start.capture(), end.capture());

            assertThat(start.getValue()).isEqualTo(start.getValue().truncatedTo(ChronoUnit.DAYS));
            assertThat(end.getValue()).isEqualTo(start.getValue().plus(1, ChronoUnit.DAYS));
            // Test odporny na przekroczenie północy w trakcie wykonania.
            assertThat(start.getValue()).isBetween(before.truncatedTo(ChronoUnit.DAYS),
                    after.truncatedTo(ChronoUnit.DAYS));
        }

        @Test
        @DisplayName("agentId i tenantId pochodzą z JWT, parametry żądania są ignorowane")
        void should_useAgentAndTenantFromJwt_when_requestParamsTryToOverride() throws Exception {
            when(contactService.findAgentKpiToday(any(), any(), any(), any())).thenReturn(kpiRow(0L, 0, 0));

            mockMvc.perform(get(KPI)
                            .param("agentId", OTHER_AGENT_ID.toString())
                            .param("tenantId", OTHER_TENANT_ID.toString())
                            .header("Authorization", bearerFor("AGENT", TENANT_ID, AGENT_ID)))
                    .andExpect(status().isOk());

            verify(contactService).findAgentKpiToday(eq(AGENT_ID), eq(TENANT_ID), any(), any());
            verify(contactService, never()).findAgentKpiToday(eq(OTHER_AGENT_ID), any(), any(), any());
            verify(contactService, never()).findAgentKpiToday(any(), eq(OTHER_TENANT_ID), any(), any());
        }

        @ParameterizedTest(name = "rola {0} ma dostęp (200)")
        @ValueSource(strings = {"AGENT", "SUPERVISOR", "ADMIN"})
        @DisplayName("role AGENT/SUPERVISOR/ADMIN są dopuszczone")
        void should_allowRole(String role) throws Exception {
            when(contactService.findAgentKpiToday(any(), any(), any(), any())).thenReturn(kpiRow(0L, 0, 0));

            mockMvc.perform(get(KPI).header("Authorization", bearerFor(role, TENANT_ID, AGENT_ID)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("SUPER_ADMIN (bez tenanta) dostaje 403 i serwis nie jest wywoływany")
        void should_return403_when_superAdmin() throws Exception {
            mockMvc.perform(get(KPI).header("Authorization", bearerFor("SUPER_ADMIN", null, AGENT_ID)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(contactService);
        }

        @Test
        @DisplayName("401 bez nagłówka Authorization")
        void should_return401_when_noToken() throws Exception {
            mockMvc.perform(get(KPI)).andExpect(status().isUnauthorized());

            verifyNoInteractions(contactService);
        }

        // ---------------------------------------------------------------------
        // BE-158 (a): odporne mapowanie wiersza
        // ---------------------------------------------------------------------

        private AgentSelfController directController() {
            TenantContext.setTenantId(TENANT_ID);
            TenantContext.setUserId(AGENT_ID);
            return new AgentSelfController(contactService, queueService, userService);
        }

        @Test
        @DisplayName("null w dowolnej kolumnie wiersza daje 0 dla tej kolumny")
        void should_mapNullColumnsToZero() throws Exception {
            when(contactService.findAgentKpiToday(any(), any(), any(), any()))
                    .thenReturn(new Object[]{null, null, null});

            mockMvc.perform(get(KPI).header("Authorization", bearerFor("AGENT", TENANT_ID, AGENT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.contactsHandledToday").value(0))
                    .andExpect(jsonPath("$.avgHandleTimeSeconds").value(0.0))
                    .andExpect(jsonPath("$.slaPercent").value(0.0));
        }

        @Test
        @DisplayName("tylko jedna kolumna null: pozostałe wartości zachowane")
        void should_keepOtherColumns_when_oneColumnNull() {
            when(contactService.findAgentKpiToday(any(), any(), any(), any()))
                    .thenReturn(new Object[]{5L, null, 50.0d});

            AgentKpiResponse body = directController().getKpi().getBody();

            assertThat(body.contactsHandledToday()).isEqualTo(5);
            assertThat(body.avgHandleTimeSeconds()).isZero();
            assertThat(body.slaPercent()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("wiersz za krótki: IllegalStateException z opisem kształtu i agentId, nie AIOOBE")
        void should_throwIllegalState_when_rowTooShort() {
            when(contactService.findAgentKpiToday(any(), any(), any(), any()))
                    .thenReturn(new Object[]{1L, 2.0d});

            assertThatThrownBy(() -> directController().getKpi())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("row shape")
                    .hasMessageContaining("length=2")
                    .hasMessageContaining(AGENT_ID.toString());
        }

        @Test
        @DisplayName("wiersz null: IllegalStateException, nie NPE")
        void should_throwIllegalState_when_rowNull() {
            when(contactService.findAgentKpiToday(any(), any(), any(), any())).thenReturn(null);

            assertThatThrownBy(() -> directController().getKpi())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("null");
        }

        @Test
        @DisplayName("wartość nie-Number: IllegalStateException z klasą wartości, nie ClassCastException")
        void should_throwIllegalState_when_valueNotNumber() {
            when(contactService.findAgentKpiToday(any(), any(), any(), any()))
                    .thenReturn(new Object[]{1L, "abc", 2.0d});

            assertThatThrownBy(() -> directController().getKpi())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("column 1")
                    .hasMessageContaining("java.lang.String");
        }

        @Test
        @DisplayName("zły kształt wiersza przez HTTP kończy się 500, nie wyciekiem ClassCastException")
        void should_return500_when_rowMalformed() throws Exception {
            when(contactService.findAgentKpiToday(any(), any(), any(), any()))
                    .thenReturn(new Object[]{1L});

            mockMvc.perform(get(KPI).header("Authorization", bearerFor("AGENT", TENANT_ID, AGENT_ID)))
                    .andExpect(status().isInternalServerError());
        }

        // ---------------------------------------------------------------------
        // BE-158 (b): workTimeSeconds od logowania
        // ---------------------------------------------------------------------

        private final Instant dayStart = Instant.parse("2026-10-28T00:00:00Z");

        @Test
        @DisplayName("czas pracy = now - lastLoginAt gdy logowanie dziś")
        void workTime_fromLogin() {
            Instant now = Instant.parse("2026-10-28T10:00:00Z");
            assertThat(AgentSelfController.computeWorkTimeSeconds(
                    Instant.parse("2026-10-28T08:30:00Z"), dayStart, now)).isEqualTo(5400);
        }

        @Test
        @DisplayName("lastLoginAt == null -> 0")
        void workTime_nullLogin() {
            assertThat(AgentSelfController.computeWorkTimeSeconds(null, dayStart,
                    Instant.parse("2026-10-28T10:00:00Z"))).isZero();
        }

        @Test
        @DisplayName("logowanie przed północą UTC: liczone od północy")
        void workTime_loginBeforeMidnight() {
            Instant now = Instant.parse("2026-10-28T01:00:00Z");
            assertThat(AgentSelfController.computeWorkTimeSeconds(
                    Instant.parse("2026-10-27T22:00:00Z"), dayStart, now)).isEqualTo(3600);
        }

        @Test
        @DisplayName("lastLoginAt w przyszłości (rozjazd zegarów) -> 0, nigdy ujemny")
        void workTime_futureLogin() {
            assertThat(AgentSelfController.computeWorkTimeSeconds(
                    Instant.parse("2026-10-28T12:00:05Z"), dayStart,
                    Instant.parse("2026-10-28T12:00:00Z"))).isZero();
        }

        @Test
        @DisplayName("brak użytkownika / brak lastLoginAt: workTimeSeconds = 0, pozostałe KPI bez zmian")
        void workTime_zeroWithoutLogin_otherKpiUnchanged() {
            when(contactService.findAgentKpiToday(any(), any(), any(), any()))
                    .thenReturn(kpiRow(3L, 90.0d, 100.0d));
            when(userService.findLastLoginAt(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            AgentKpiResponse body = directController().getKpi().getBody();

            assertThat(body.workTimeSeconds()).isZero();
            assertThat(body.contactsHandledToday()).isEqualTo(3);
            assertThat(body.avgHandleTimeSeconds()).isEqualTo(90.0);
            assertThat(body.slaPercent()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("czas pracy nie zależy od liczby kontaktów: agent po logowaniu bez kontaktów ma > 0")
        void workTime_independentOfContacts() {
            when(contactService.findAgentKpiToday(any(), any(), any(), any()))
                    .thenReturn(kpiRow(0L, 0, 0));
            when(userService.findLastLoginAt(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.of(Instant.now().minusSeconds(30)));

            AgentKpiResponse body = directController().getKpi().getBody();

            // 0 tylko gdy test trafi dokładnie na północ UTC (login w poprzedniej dobie, 30 s temu)
            assertThat(body.workTimeSeconds()).isBetween(0L, 35L);
            assertThat(body.contactsHandledToday()).isZero();
        }
    }
}
