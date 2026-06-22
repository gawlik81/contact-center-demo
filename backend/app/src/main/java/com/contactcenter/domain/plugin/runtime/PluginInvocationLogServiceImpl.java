package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.domain.plugin.PluginInvocationLog;
import com.contactcenter.domain.plugin.PluginInvocationLogRepository;
import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.dto.PluginInvocationLogDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Implementacja {@link PluginInvocationLogService} (ARCHITECTURE.md §11.9, §11.12, EPIC-28,
 * BE-105).
 *
 * <p><strong>Decyzja 404 vs 403 dla {@code installationId} innego tenanta
 * ({@link #findByInstallation}):</strong> {@code tenant_plugin_installation} ma RLS (V075) —
 * {@link PluginRegistrationService#getInstallation} nie zwraca wiersza innego tenanta, więc z
 * punktu widzenia backendu wygląda identycznie jak "nie istnieje wcale". Ten serwis świadomie
 * zwraca <strong>404 dla obu przypadków</strong> (nieistniejąca instalacja ORAZ instalacja
 * innego tenanta), kontynuując konwencję ustaloną w BE-103
 * ({@code PluginManualActionController}) i BE-100 ({@code PluginRegistrationService.enable}/
 * {@code disable}/{@code rollback}) — wszystkie cross-tenant w tym epiku mapują na
 * {@code ResourceNotFoundException}/404, nigdy na bypass RLS dla odróżnienia 403. To jest
 * świadome odejście od literalnego zapisu kryterium akceptacji BE-105 (które wymienia 403) na
 * rzecz konsekwencji z resztą epiku — brak w projekcie żadnego wzorca zapytania z bypassem RLS
 * (zweryfikowano), a dodanie go tylko na potrzeby rozróżnienia 403/404 tego jednego endpointu
 * byłoby nieproporcjonalnym ryzykiem bezpieczeństwa względem korzyści.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class PluginInvocationLogServiceImpl implements PluginInvocationLogService {

    private final PluginInvocationLogRepository repository;
    private final PluginRegistrationService pluginRegistrationService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void record(
            UUID tenantId,
            UUID installationId,
            ExtensionPoint extensionPoint,
            InvocationStatus status,
            long durationMs,
            String errorSummary,
            UUID relatedContactId,
            Object requestPayload) {

        String redactedJson = redactToJson(requestPayload);

        PluginInvocationLog entry = PluginInvocationLog.builder()
                .tenantId(tenantId)
                .tenantPluginInstallationId(installationId)
                .extensionPoint(extensionPoint.name())
                .relatedContactId(relatedContactId)
                .status(status.name())
                .durationMs((int) durationMs)
                .errorSummary(errorSummary)
                .requestPayloadRedacted(redactedJson)
                .build();

        try {
            repository.insert(entry);
        } catch (RuntimeException e) {
            // Logowanie wywołania pluginu jest best-effort względem przepływu wołającego —
            // identycznie jak placeholder SLF4J, który ta metoda zastępuje (BE-102/104):
            // błąd zapisu do plugin_invocation_log nie może zepsuć dispatchu pluginu, który
            // już się zakończył (sukcesem lub błędem) w momencie wołania record(...).
            log.error("[PluginInvocationLogService] Błąd zapisu wpisu dziennika: tenant={}, "
                            + "installation={}, extensionPoint={}, status={}: {}",
                    tenantId, installationId, extensionPoint, status, e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PluginInvocationLogDto> findByInstallation(
            UUID tenantId, UUID installationId, InvocationStatus status, Pageable pageable) {

        // Weryfikacja ownership PRZED odczytem historii — rzuca ResourceNotFoundException
        // (mapowane globalnie na 404), włącznie z przypadkiem "istnieje, ale dla innego
        // tenanta" (RLS, zob. Javadoc klasy).
        pluginRegistrationService.getInstallation(tenantId, installationId);

        String statusFilter = status != null ? status.name() : null;

        return repository.findByInstallation(installationId, tenantId, statusFilter, pageable)
                .map(PluginInvocationLogDto::from);
    }

    /**
     * Redaguje {@code requestPayload} ({@link PiiRedactor}) i serializuje wynik do JSON string
     * dla kolumny {@code request_payload_redacted} (JSONB).
     */
    private String redactToJson(Object requestPayload) {
        if (requestPayload == null) {
            return null;
        }
        try {
            Object asMap = objectMapper.convertValue(requestPayload, Object.class);
            Object redacted = PiiRedactor.redact(asMap);
            return objectMapper.writeValueAsString(redacted);
        } catch (Exception e) {
            log.warn("[PluginInvocationLogService] Błąd redakcji/serializacji payloadu — "
                    + "zapisuję null zamiast surowego payloadu: {}", e.getMessage());
            return null;
        }
    }
}
