package com.contactcenter.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Autoryzacja ramek STOMP {@code SUBSCRIBE} i {@code SEND} (BE-229, wymaganie R1 z ADR-14).
 *
 * <p>{@link WebSocketAuthInterceptor} uwierzytelnia wyłącznie ramkę CONNECT. Bez tego interceptora
 * zalogowany klient mógłby subskrybować dowolną destynację {@code SimpleBroker} (w tym wzorce Ant,
 * np. {@code /topic/**}) i odbierać zdarzenia innych tenantów i użytkowników, a także publikować
 * wprost na {@code /topic/...} (broker rozsyłałby to do cudzych sesji).
 *
 * <p>Tożsamość pochodzi wyłącznie z {@link StompPrincipal} sesji (ustawianego z zweryfikowanego JWT przy
 * CONNECT/STOMP przez {@link WebSocketAuthInterceptor}) – nagłówki klienta nie są brane pod uwagę. Kolejność
 * rejestracji (po auth) jest konwencją czytelności, a nie warunkiem poprawności: ramki CONNECT/STOMP są tu
 * ignorowane, a principal dla późniejszych ramek dostarcza sesja.
 *
 * <h2>Default-deny komend</h2>
 * Dozwolone komendy klienta (jawny zbiór): CONNECT, STOMP (uwierzytelnia {@link WebSocketAuthInterceptor}),
 * SUBSCRIBE i SEND (reguły poniżej), UNSUBSCRIBE, ACK, NACK, DISCONNECT (zakotwiczone w sesji, nie dotyczą
 * cudzych subskrypcji; SimpleBroker ignoruje ACK/NACK). Odrzucane: BEGIN/COMMIT/ABORT (transakcje STOMP nie są
 * wspierane przez Spring ani używane przez FE) oraz komendy serwerowe. Wiadomość bez komendy przechodzi tylko,
 * jeśli jest heartbeatem.
 *
 * <h2>Allow-list SUBSCRIBE (dokładne dopasowanie regex, UUID w kanonicznej małej pisowni)</h2>
 * <ul>
 *   <li>{@code /topic/user/{ownUserId}/events} – każda rola</li>
 *   <li>{@code /topic/tenant/{ownTenantId}/agents} – każda rola z tenantem (także AGENT; zakres to decyzja
 *       produktowa, patrz CR BE-229)</li>
 *   <li>{@code /topic/tenant/{ownTenantId}/supervisor} – tylko SUPERVISOR i ADMIN
 *       (metryki KPI idą tym samym topikiem jako event {@code SUPERVISOR_METRICS};
 *       {@code .../supervisor/metrics} nie jest nigdzie publikowany ani subskrybowany)</li>
 * </ul>
 * Wszystko inne (wildcardy {@code * ** ? # {}}, cudzy user/tenant, {@code /user/**}, {@code /app/**},
 * nieznane prefiksy, brak destynacji lub principala) jest odrzucane.
 *
 * <h2>Allow-list SEND</h2>
 * <ul>
 *   <li>{@code /app/ping} – heartbeat/obecność (każdy uwierzytelniony)</li>
 *   <li>{@code /app/agent/status} – legacy: FE nadal wysyła, backend nie ma handlera; ramka jest
 *       po cichu odrzucana (bez zamykania sesji), by nie powodować pętli reconnectów</li>
 * </ul>
 * SEND na {@code /topic/**}, {@code /user/**} i każdą inną destynację jest odrzucany. Porzucenie legacy
 * SEND liczy {@code ws.stomp.dropped}; klient nie dostaje wtedy RECEIPT (FE nie używa receiptów).
 *
 * <h2>Odrzucenie</h2>
 * Rzuca {@link MessagingException} – {@code StompSubProtocolHandler} odpowiada ramką ERROR i sesja jest
 * zamykana (tak samo jak przy błędzie CONNECT). Treść błędu nie zawiera destynacji ani identyfikatorów.
 * Log WARN (userId, tenantId, rola z zaufanego principala; dla {@code foreign_*} także zsanityzowana
 * destynacja), ograniczony do 1 wpisu / 10 s na powód + licznik Micrometer {@value #METRIC_REJECTED}
 * (licznik liczy wszystko, także wpisy pominięte w logu).
 *
 * <p>Subskrypcje {@code /user/...} (np. {@code @SendToUser} dla PONG) nie są dozwolone – FE ich nie
 * używa; unicast realizuje {@code /topic/user/{id}/events} (ADR-14).
 */
@Slf4j
@Component
public class StompDestinationAuthorizationInterceptor implements ChannelInterceptor {

    /** Licznik odrzuconych ramek; tagi: {@code command} (SUBSCRIBE/SEND/OTHER) i {@code reason}. */
    public static final String METRIC_REJECTED = "ws.stomp.rejected";

    /** Licznik cicho porzuconych ramek (legacy {@code /app/agent/status}); tag {@code reason}. */
    public static final String METRIC_DROPPED = "ws.stomp.dropped";

    private static final long WARN_INTERVAL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    private static final int MAX_LOGGED_DESTINATION = 100;

    /** Jawny zbiór komend klienta, które w ogóle wolno przepuścić (reszta = default-deny). */
    private static final Set<StompCommand> ALLOWED_CLIENT_COMMANDS = Set.of(
            StompCommand.CONNECT, StompCommand.STOMP, StompCommand.SUBSCRIBE, StompCommand.UNSUBSCRIBE,
            StompCommand.SEND, StompCommand.ACK, StompCommand.NACK, StompCommand.DISCONNECT);

    private static final String UUID_REGEX =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final Pattern USER_EVENTS =
            Pattern.compile("/topic/user/(" + UUID_REGEX + ")/events");
    private static final Pattern TENANT_TOPIC =
            Pattern.compile("/topic/tenant/(" + UUID_REGEX + ")/(agents|supervisor)");

    private static final String DEST_PING = "/app/ping";
    private static final String DEST_LEGACY_AGENT_STATUS = "/app/agent/status";

    private static final Set<String> SUPERVISOR_ROLES = Set.of("SUPERVISOR", "ADMIN");

    /** Komendy zarezerwowane dla serwera – klient nie ma prawa ich wysyłać. */
    private static final Set<StompCommand> SERVER_ONLY_COMMANDS =
            Set.of(StompCommand.MESSAGE, StompCommand.CONNECTED, StompCommand.RECEIPT, StompCommand.ERROR);

    private final MeterRegistry meterRegistry;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastWarn = new ConcurrentHashMap<>();

    @Autowired
    public StompDestinationAuthorizationInterceptor(MeterRegistry meterRegistry) {
        this(meterRegistry, System::nanoTime);
    }

    StompDestinationAuthorizationInterceptor(MeterRegistry meterRegistry, LongSupplier nanoClock) {
        this.meterRegistry = meterRegistry;
        this.nanoClock = nanoClock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        StompCommand command = accessor != null ? accessor.getCommand() : null;

        if (command == null) {
            // Jedyna wiadomość bez komendy dopuszczona z kanału klienta to heartbeat.
            if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.HEARTBEAT) {
                return message;
            }
            throw reject("OTHER", "unsupported_command", accessor);
        }
        if (!ALLOWED_CLIENT_COMMANDS.contains(command)) {
            String reason = SERVER_ONLY_COMMANDS.contains(command) ? "server_only_command" : "unsupported_command";
            throw reject("OTHER", reason, accessor);
        }
        if (StompCommand.SUBSCRIBE.equals(command)) {
            authorizeSubscribe(accessor);
            return message;
        }
        if (StompCommand.SEND.equals(command)) {
            return authorizeSend(accessor) ? message : null;
        }
        return message; // CONNECT/STOMP (auth gdzie indziej), DISCONNECT, UNSUBSCRIBE, ACK, NACK
    }

    // =========================================================================
    // SUBSCRIBE
    // =========================================================================

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        StompPrincipal principal = principalOf(accessor);
        if (principal == null) {
            throw reject("SUBSCRIBE", "no_principal", accessor);
        }
        String destination = accessor.getDestination();
        if (destination == null || destination.isEmpty()) {
            throw reject("SUBSCRIBE", "no_destination", accessor);
        }

        Matcher userMatcher = USER_EVENTS.matcher(destination);
        if (userMatcher.matches()) {
            if (!userMatcher.group(1).equals(principal.getName())) {
                throw reject("SUBSCRIBE", "foreign_user", accessor);
            }
            return;
        }

        Matcher tenantMatcher = TENANT_TOPIC.matcher(destination);
        if (tenantMatcher.matches()) {
            if (principal.getTenantId() == null
                    || !tenantMatcher.group(1).equals(principal.getTenantId().toString())) {
                throw reject("SUBSCRIBE", "foreign_tenant", accessor);
            }
            if (!"agents".equals(tenantMatcher.group(2)) && !SUPERVISOR_ROLES.contains(principal.getRole())) {
                throw reject("SUBSCRIBE", "insufficient_role", accessor);
            }
            return;
        }

        throw reject("SUBSCRIBE", "destination_not_allowed", accessor);
    }

    // =========================================================================
    // SEND
    // =========================================================================

    /** @return {@code true} – przepuść ramkę, {@code false} – po cichu porzuć (legacy no-op). */
    private boolean authorizeSend(StompHeaderAccessor accessor) {
        if (principalOf(accessor) == null) {
            throw reject("SEND", "no_principal", accessor);
        }
        String destination = accessor.getDestination();
        if (DEST_PING.equals(destination)) {
            return true;
        }
        if (DEST_LEGACY_AGENT_STATUS.equals(destination)) {
            counter(METRIC_DROPPED, "reason", "legacy_agent_status").increment();
            log.debug("[WS-Authz] Porzucono legacy SEND {} (brak handlera)", DEST_LEGACY_AGENT_STATUS);
            return false;
        }
        throw reject("SEND", "destination_not_allowed", accessor);
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    private static StompPrincipal principalOf(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        return user instanceof StompPrincipal sp ? sp : null;
    }

    private Counter counter(String name, String... tags) {
        return counters.computeIfAbsent(name + String.join("|", tags),
                k -> meterRegistry.counter(name, tags));
    }

    private MessagingException reject(String command, String reason, StompHeaderAccessor accessor) {
        counter(METRIC_REJECTED, "command", command, "reason", reason).increment();
        if (shouldWarn(reason)) {
            Principal user = accessor != null ? accessor.getUser() : null;
            String tenant = user instanceof StompPrincipal sp ? String.valueOf(sp.getTenantId()) : "n/a";
            String role = user instanceof StompPrincipal sp ? sp.getRole() : "n/a";
            // Destynacja jest kontrolowana przez klienta: logujemy ją wyłącznie zsanityzowaną i tylko dla
            // prób dostępu do cudzych zasobów (informacja, czyjego tenanta/usera próbowano).
            String dest = reason.startsWith("foreign_") && accessor != null
                    ? sanitize(accessor.getDestination()) : "-";
            log.warn("[WS-Authz] Odrzucono ramkę {}: reason={}, userId={}, tenantId={}, role={}, session={}, dest={}",
                    command, reason, user != null ? user.getName() : "n/a", tenant, role,
                    accessor != null ? accessor.getSessionId() : null, dest);
        }
        return new MessagingException("Brak uprawnień do wskazanej destynacji STOMP");
    }

    /** Max 1 wpis WARN na 10 s i powód (ochrona logów przed pętlą reconnectów). */
    private boolean shouldWarn(String reason) {
        long now = nanoClock.getAsLong();
        AtomicLong last = lastWarn.computeIfAbsent(reason, r -> new AtomicLong(now - WARN_INTERVAL_NANOS));
        long prev = last.get();
        return now - prev >= WARN_INTERVAL_NANOS && last.compareAndSet(prev, now);
    }

    /** Usuwa znaki sterujące (log injection) i ogranicza długość. */
    static String sanitize(String value) {
        if (value == null) {
            return "null";
        }
        String clean = value.replaceAll("\\p{Cntrl}", "?");
        return clean.length() > MAX_LOGGED_DESTINATION ? clean.substring(0, MAX_LOGGED_DESTINATION) + "..." : clean;
    }
}
