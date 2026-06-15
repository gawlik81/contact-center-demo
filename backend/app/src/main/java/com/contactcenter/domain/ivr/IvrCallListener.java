package com.contactcenter.domain.ivr;

import com.contactcenter.domain.telephony.CallEvent;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Listener RabbitMQ dla przychodzących połączeń – startuje przepływ IVR.
 *
 * <p>Nasłuchuje na kolejce {@code cc.queue.ivr-handler} zbindowanej do exchange
 * {@code cc.events} z routing key {@code call.incoming}.
 *
 * <p>Logika:
 * <ol>
 *   <li>Odbierz {@link CallEvent} z routing key {@code call.incoming}</li>
 *   <li>Jeśli istnieje aktywne drzewo IVR dla tenanta → startuj sesję IVR</li>
 *   <li>Jeśli brak IVR → przekaż do routingu bezpośrednio (domyślna kolejka)</li>
 * </ol>
 *
 * <p>TenantContext w listenerze RabbitMQ:
 * Wątki RabbitMQ nie mają TenantContext z JWT. Kontekst ustawiamy ręcznie
 * z danych eventu (tenantId z CallEvent). Czyścimy w finally.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class IvrCallListener {

    private final IvrEngineService ivrEngineService;

    /**
     * Obsługuje przychodzące połączenie – startuje przepływ IVR.
     *
     * @param event event przychodzącego połączenia z danymi callId i tenantId
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_IVR_HANDLER)
    public void onCallIncoming(CallEvent event) {
        if (event == null || event.getCallId() == null) {
            log.warn("[IvrCallListener] Odebrano event bez callId – pomijam");
            return;
        }

        log.info("[IvrCallListener] Odebrano call.incoming: callId={}, tenantId={}",
                event.getCallId(), event.getTenantId());

        // Ustaw TenantContext dla wątku RabbitMQ
        UUID tenantId = event.getTenantId();
        if (tenantId != null) {
            TenantContext.setTenantId(tenantId);
        }

        try {
            // Startuj sesję IVR (ivrId=null → użyj aktywnego drzewa dla tenanta)
            ivrEngineService.startIvrSession(event.getCallId(), tenantId, null);

        } catch (Exception e) {
            log.error("[IvrCallListener] Błąd podczas obsługi call.incoming: callId={}, error={}",
                    event.getCallId(), e.getMessage(), e);
            // Nie rzucamy wyjątku – nie chcemy NACK i retry dla błędów IVR
            // (fallback jest obsługiwany wewnątrz IvrEngineService)
        } finally {
            TenantContext.clear();
        }
    }
}
