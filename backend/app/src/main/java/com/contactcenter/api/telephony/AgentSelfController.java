package com.contactcenter.api.telephony;

import com.contactcenter.api.contact.dto.AssignedContactResponse;
import com.contactcenter.api.telephony.dto.AgentKpiResponse;
import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactRepository;
import com.contactcenter.domain.repository.QueueRepository;
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

    private final ContactRepository contactRepository;
    private final QueueRepository queueRepository;

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

        return contactRepository.findAssignedContactForAgent(agentId, tenantId)
                .map(contact -> {
                    String queueName = resolveQueueName(contact, tenantId);
                    String customerId = contact.getCustomerId() != null
                            ? contact.getCustomerId().toString() : null;

                    AssignedContactResponse response = new AssignedContactResponse(
                            contact.getContactId().toString(),
                            contact.getChannel(),
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
                    - workTimeSeconds – sekundy od pierwszego kontaktu do teraz (0 gdy brak kontaktów)

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

        Object[] row = contactRepository.findAgentKpiToday(agentId, tenantId, dayStart, dayEnd);

        long contactsHandledToday = ((Number) row[0]).longValue();
        double avgHandleTimeSeconds = ((Number) row[1]).doubleValue();
        double slaPercent = ((Number) row[2]).doubleValue();
        long workTimeSeconds = ((Number) row[3]).longValue();

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
            return queueRepository.findByIdAndTenantId(contact.getQueueId(), tenantId)
                    .map(q -> q.getName())
                    .orElse("");
        } catch (Exception e) {
            log.warn("[AgentSelf] Nie udało się pobrać nazwy kolejki: queueId={}, error={}",
                    contact.getQueueId(), e.getMessage());
            return "";
        }
    }
}
