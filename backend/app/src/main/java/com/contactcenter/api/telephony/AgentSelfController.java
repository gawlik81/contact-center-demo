package com.contactcenter.api.telephony;

import com.contactcenter.api.contact.dto.AssignedContactResponse;
import com.contactcenter.api.telephony.dto.AgentKpiResponse;
import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactChannels;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Endpointy self-service dla zalogowanego agenta.
 *
 * <p>Wszystkie endpointy wymagają ważnego JWT z rolą AGENT (lub SUPERVISOR/ADMIN).
 * Używają TenantContext.getUserId() do identyfikacji agenta, więc nie wymagają
 * parametru agentId w URL – są bezpośrednio self-service.
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/me")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Agent Self",
        description = "Endpointy self-service dla zalogowanego agenta (recovery po utracie WebSocket, stan bieżącej sesji).")
public class AgentSelfController {

    private final ContactService contactService;
    private final QueueService queueService;
    private final UserService userService;

    /**
     * Zwraca kontakt aktualnie przypisany do agenta ze statusem ASSIGNED
     * (przydzielony przez routing, czeka na odebranie przez agenta).
     *
     * <p>Używany przez frontend po reconnect WebSocket do recovery: jeśli CONTACT_ASSIGNED
     * event nie dotarł do agenta podczas połączenia WebSocket, agent może pobrać dane
     * oczekującego kontaktu zaraz po ponownym połączeniu i wyświetlić dzwonek/softphone.
     *
     * <p>Zwraca 204 No Content gdy brak kontaktu ASSIGNED (stan normalny).
     *
     * @return kontakt ASSIGNED lub 204 gdy brak
     */
    @GetMapping("/assigned-contact")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    @Operation(
            summary = "Pobierz kontakt oczekujący na odebranie przez agenta",
            description = """
                    Zwraca kontakt ze statusem ASSIGNED przydzielony przez routing do zalogowanego agenta.
                    Status ASSIGNED oznacza że routing wybrał agenta, ale agent jeszcze nie odebrał połączenia.

                    Używany przez frontend po reconnect WebSocket do recovery: po ponownym połączeniu
                    frontend wywołuje ten endpoint żeby sprawdzić czy jest oczekujące połączenie,
                    które mogło zostać przydzielone gdy WS był rozłączony.

                    Zwraca 204 No Content gdy agent nie ma żadnego oczekującego kontaktu (stan normalny).
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Kontakt ASSIGNED znaleziony – agent powinien wyświetlić dzwonek"),
                    @ApiResponse(responseCode = "204", description = "Brak oczekującego kontaktu ASSIGNED"),
                    @ApiResponse(responseCode = "403", description = "JWT brakuje lub niewystarczająca rola")
            }
    )
    public ResponseEntity<AssignedContactResponse> getAssignedContact() {
        UUID agentId = TenantContext.getUserId();
        UUID tenantId = TenantContext.getTenantId();

        log.debug("[AgentSelf] getAssignedContact: agentId={}, tenantId={}", agentId, tenantId);

        return contactService.findAssignedContactForAgent(agentId, tenantId)
                .map(contact -> {
                    String queueName = resolveQueueName(contact, tenantId);
                    String customerId = contact.getCustomerId() != null
                            ? contact.getCustomerId().toString() : null;

                    AssignedContactResponse response = new AssignedContactResponse(
                            contact.getContactId().toString(),
                            ContactChannels.toAgentDesktopType(contact.getChannel()),
                            contact.getRemoteAddress(),
                            contact.getRemoteAddress(), // customerName – fallback na numer/adres
                            queueName,
                            customerId
                    );

                    log.info("[AgentSelf] Znaleziono kontakt ASSIGNED dla agenta: contactId={}, " +
                             "channel={}, agentId={}, tenantId={}",
                            contact.getContactId(), contact.getChannel(), agentId, tenantId);

                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> {
                    log.debug("[AgentSelf] Brak kontaktu ASSIGNED dla agenta: agentId={}", agentId);
                    return ResponseEntity.noContent().build();
                });
    }

    /**
     * Zwraca statystyki KPI zalogowanego agenta za bieżący dzień (UTC).
     *
     * <p>Oblicza zakres dnia jako {@code [dayStart, dayEnd)} w UTC i wykonuje
     * jedno zagregowane zapytanie SQL do tabeli {@code contact}. Zapytanie zawsze
     * zwraca wynik – gdy agent nie obsłużył dziś żadnego kontaktu, wszystkie wartości
     * wynoszą 0.
     *
     * <p>{@code workTimeSeconds} = {@code now - max(lastLoginAt, dayStartUtc)}, nie mniej niż 0;
     * {@code 0} gdy {@code lastLoginAt == null} (np. konto, które nie logowało się od wdrożenia BE-158,
     * albo logowanie przed zapisem znacznika). Decyzja właściciela 2026-10-28.
     *
     * <p>Dane używane przez frontend do wyświetlania paska KPI w top-navbar.
     *
     * @return {@link AgentKpiResponse} ze statystykami agenta za dziś
     */
    @GetMapping("/kpi")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    @Operation(
            summary = "Pobierz KPI agenta za bieżący dzień",
            description = """
                    Zwraca zagregowane statystyki KPI zalogowanego agenta za bieżący dzień UTC:
                    - contactsHandledToday – liczba zakończonych kontaktów
                    - slaPercent – % kontaktów z czasem oczekiwania < 30s (0.0 gdy brak danych)
                    - avgHandleTimeSeconds – średni czas obsługi w sekundach (0.0 gdy brak danych)
                    - workTimeSeconds – czas pracy w sekundach liczony od ostatniego logowania agenta (po pełnym
                      uwierzytelnieniu, z MFA jeśli włączone; refresh tokenu go nie zmienia) do teraz.
                      Gdy logowanie było przed dzisiejszą północą UTC, liczone od północy UTC (spójnie
                      z pozostałymi KPI liczonymi per dzień UTC). 0 gdy brak zapisanego logowania.

                    Używane przez frontend do wyświetlania paska KPI w top-navbar.
                    Zawsze zwraca 200 OK – wartości zerowe oznaczają brak danych na dziś.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Statystyki KPI agenta za bieżący dzień"),
                    @ApiResponse(responseCode = "403", description = "JWT brakuje lub niewystarczająca rola")
            }
    )
    public ResponseEntity<AgentKpiResponse> getKpi() {
        UUID agentId = TenantContext.getUserId();
        UUID tenantId = TenantContext.getTenantId();

        log.debug("[AgentSelf] kpi: agentId={}, tenantId={}", agentId, tenantId);

        Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);

        Object[] row = contactService.findAgentKpiToday(agentId, tenantId, dayStart, dayEnd);

        long contactsHandledToday = toLong(row, 0, agentId);
        double avgHandleTimeSeconds = toDouble(row, 1, agentId);
        double slaPercent = toDouble(row, 2, agentId);

        // BE-158: czas pracy od logowania (app_user.last_login_at), nie z danych kontaktów
        Instant lastLoginAt = userService.findLastLoginAt(agentId, tenantId).orElse(null);
        long workTimeSeconds = computeWorkTimeSeconds(lastLoginAt, dayStart, Instant.now());

        AgentKpiResponse response = new AgentKpiResponse(
                contactsHandledToday,
                slaPercent,
                avgHandleTimeSeconds,
                workTimeSeconds
        );

        log.debug("[AgentSelf] kpi wynik: agentId={}, contacts={}, sla={}, aht={}, workTime={}",
                agentId, contactsHandledToday, slaPercent, avgHandleTimeSeconds, workTimeSeconds);

        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /** Number of columns returned by {@code findAgentKpiToday}: contacts_count, avg_handle_time, sla_percent. */
    static final int KPI_ROW_COLUMNS = 3;

    /**
     * Work time in seconds: {@code now - max(lastLoginAt, dayStart)}, clamped to >= 0.
     * {@code lastLoginAt == null} gives 0; a login before today's UTC midnight counts from midnight.
     */
    static long computeWorkTimeSeconds(Instant lastLoginAt, Instant dayStart, Instant now) {
        if (lastLoginAt == null) {
            return 0L;
        }
        Instant from = lastLoginAt.isAfter(dayStart) ? lastLoginAt : dayStart;
        return Math.max(0L, ChronoUnit.SECONDS.between(from, now));
    }

    private static long toLong(Object[] row, int index, UUID agentId) {
        Number n = columnAsNumber(row, index, agentId);
        return n == null ? 0L : n.longValue();
    }

    private static double toDouble(Object[] row, int index, UUID agentId) {
        Number n = columnAsNumber(row, index, agentId);
        return n == null ? 0.0d : n.doubleValue();
    }

    /**
     * Reads a numeric KPI column defensively: SQL NULL gives {@code null} (caller maps it to 0),
     * a missing row/column or a non-numeric value is a programming error (query changed) and fails
     * with a descriptive {@link IllegalStateException} instead of NPE/ClassCastException/AIOOBE.
     */
    private static Number columnAsNumber(Object[] row, int index, UUID agentId) {
        if (row == null || row.length < KPI_ROW_COLUMNS) {
            String shape = row == null ? "null" : "length=" + row.length;
            log.error("[AgentSelf] kpi: unexpected row shape ({}), expected >= {} columns, agentId={}",
                    shape, KPI_ROW_COLUMNS, agentId);
            throw new IllegalStateException("Unexpected agent KPI row shape (" + shape + ", expected at least "
                    + KPI_ROW_COLUMNS + " columns) for agentId=" + agentId);
        }
        Object value = row[index];
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            log.error("[AgentSelf] kpi: column {} is {} instead of a number, agentId={}",
                    index, value.getClass().getName(), agentId);
            throw new IllegalStateException("Agent KPI row column " + index + " is "
                    + value.getClass().getName() + ", expected a Number, agentId=" + agentId);
        }
        return number;
    }

    /**
     * Pobiera nazwę kolejki dla kontaktu – defensywnie, nie rzuca wyjątku.
     *
     * @param contact  kontakt z queueId (może być null)
     * @param tenantId UUID tenanta
     * @return nazwa kolejki lub pusty string gdy brak lub błąd
     */
    private String resolveQueueName(Contact contact, UUID tenantId) {
        if (contact.getQueueId() == null) {
            return "";
        }
        try {
            return queueService.findQueueEntity(contact.getQueueId(), tenantId)
                    .map(q -> q.getName())
                    .orElse("");
        } catch (Exception e) {
            log.warn("[AgentSelf] Nie udało się pobrać nazwy kolejki: queueId={}, error={}",
                    contact.getQueueId(), e.getMessage());
            return "";
        }
    }
}
