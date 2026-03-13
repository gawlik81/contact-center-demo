package com.contactcenter.security;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Kontekst tenanta i użytkownika per wątek HTTP – multi-tenancy przez InheritableThreadLocal.
 *
 * <p>Wartości są ustawiane przez {@code TenantFilter} na podstawie JWT claims
 * (claim {@code tenant_id} i {@code user_id}) przy każdym żądaniu HTTP.
 *
 * <p><strong>InheritableThreadLocal</strong> zamiast ThreadLocal zapewnia propagację
 * kontekstu do wątków potomnych (np. przy {@code @Async}, virtual threads Java 21).
 * Dla jawnego przesyłania kontekstu między pulami wątków użyj metod {@code snapshot()}
 * i {@code restore()}.
 *
 * <p><strong>WAŻNE:</strong> TenantContext MUSI być czyszczony w bloku finally filtra HTTP,
 * aby zapobiec wyciekom kontekstu między żądaniami przy reużywaniu wątków z puli.
 *
 * <h3>Przykład użycia w repozytorium:</h3>
 * <pre>{@code
 * public List<Contact> findAll() {
 *     UUID tenantId = TenantContext.getTenantId();
 *     return jpaRepository.findAllByTenantId(tenantId);
 * }
 * }</pre>
 *
 * <h3>Przykład propagacji do @Async:</h3>
 * <pre>{@code
 * TenantContext.Snapshot snapshot = TenantContext.snapshot();
 * asyncExecutor.execute(() -> {
 *     TenantContext.restore(snapshot);
 *     try {
 *         doAsyncWork();
 *     } finally {
 *         TenantContext.clear();
 *     }
 * });
 * }</pre>
 */
@Slf4j
public final class TenantContext {

    /**
     * InheritableThreadLocal umożliwia dziedziczenie kontekstu przez wątki potomne.
     * Kluczowe dla Java 21 virtual threads i @Async.
     */
    private static final InheritableThreadLocal<UUID> TENANT_ID = new InheritableThreadLocal<>();
    private static final InheritableThreadLocal<UUID> USER_ID   = new InheritableThreadLocal<>();
    private static final InheritableThreadLocal<String> TENANT_NAME = new InheritableThreadLocal<>();
    private static final InheritableThreadLocal<String> USER_ROLE   = new InheritableThreadLocal<>();

    private TenantContext() {
        throw new UnsupportedOperationException("Klasa narzędziowa – nie instancjonować");
    }

    // =========================================================================
    // Settery
    // =========================================================================

    /**
     * Ustawia tenant_id dla bieżącego wątku.
     *
     * @param tenantId UUID tenanta – nie może być null
     * @throws IllegalArgumentException gdy tenantId jest null
     */
    public static void setTenantId(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId nie może być null");
        }
        TENANT_ID.set(tenantId);
        log.trace("[TenantContext] Ustawiono tenantId: {}", tenantId);
    }

    /**
     * Ustawia user_id dla bieżącego wątku.
     *
     * @param userId UUID użytkownika – nie może być null
     * @throws IllegalArgumentException gdy userId jest null
     */
    public static void setUserId(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId nie może być null");
        }
        USER_ID.set(userId);
        log.trace("[TenantContext] Ustawiono userId: {}", userId);
    }

    /**
     * Ustawia nazwę tenanta (opcjonalnie – do logowania i MDC).
     */
    public static void setTenantName(String tenantName) {
        TENANT_NAME.set(tenantName);
    }

    /**
     * Ustawia rolę użytkownika z JWT claim (np. ADMIN, SUPERVISOR, AGENT).
     */
    public static void setUserRole(String role) {
        USER_ROLE.set(role);
    }

    // =========================================================================
    // Gettery
    // =========================================================================

    /**
     * Zwraca tenant_id dla bieżącego wątku.
     *
     * @return UUID tenanta
     * @throws IllegalStateException gdy tenantId nie jest ustawiony
     */
    public static UUID getTenantId() {
        UUID tenantId = TENANT_ID.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext.tenantId nie jest ustawiony dla bieżącego wątku. " +
                    "Sprawdź czy TenantFilter jest poprawnie skonfigurowany " +
                    "i żądanie zawiera prawidłowy token JWT."
            );
        }
        return tenantId;
    }

    /**
     * Zwraca user_id dla bieżącego wątku.
     *
     * @return UUID użytkownika
     * @throws IllegalStateException gdy userId nie jest ustawiony
     */
    public static UUID getUserId() {
        UUID userId = USER_ID.get();
        if (userId == null) {
            throw new IllegalStateException(
                    "TenantContext.userId nie jest ustawiony dla bieżącego wątku. " +
                    "Sprawdź czy TenantFilter jest poprawnie skonfigurowany."
            );
        }
        return userId;
    }

    /**
     * Zwraca tenant_id lub null gdy nie jest ustawiony.
     *
     * <p>Użyj tej metody w miejscach, gdzie żądanie może być anonimowe
     * (np. webhook, publiczny health check).
     */
    public static UUID getTenantIdOrNull() {
        return TENANT_ID.get();
    }

    /**
     * Zwraca user_id lub null gdy nie jest ustawiony.
     */
    public static UUID getUserIdOrNull() {
        return USER_ID.get();
    }

    /**
     * Zwraca nazwę tenanta (może być null jeśli nie ustawiona).
     */
    public static String getTenantName() {
        return TENANT_NAME.get();
    }

    /**
     * Zwraca rolę użytkownika z JWT (może być null dla żądań anonimowych).
     */
    public static String getUserRole() {
        return USER_ROLE.get();
    }

    /**
     * Sprawdza czy kontekst tenanta jest ustawiony.
     */
    public static boolean isSet() {
        return TENANT_ID.get() != null;
    }

    // =========================================================================
    // Lifecycle – snapshot/restore dla async propagacji
    // =========================================================================

    /**
     * Tworzy niemutowalny snapshot aktualnego kontekstu.
     *
     * <p>Używaj do propagacji kontekstu do wątków asynchronicznych (np. {@code @Async},
     * {@code CompletableFuture}, virtual threads). Wartości są kopiowane w momencie wywołania.
     *
     * @return snapshot kontekstu – bezpieczny do przekazania między wątkami
     */
    public static Snapshot snapshot() {
        return new Snapshot(
                TENANT_ID.get(),
                USER_ID.get(),
                TENANT_NAME.get(),
                USER_ROLE.get()
        );
    }

    /**
     * Przywraca kontekst z wcześniej utworzonego snapshotu.
     *
     * <p>Wywoływane na początku pracy wątku asynchronicznego.
     * MUSI być sparowane z wywołaniem {@link #clear()} w bloku finally.
     *
     * @param snapshot snapshot do przywrócenia – nie może być null
     */
    public static void restore(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot nie może być null");
        }
        if (snapshot.tenantId() != null) {
            TENANT_ID.set(snapshot.tenantId());
        }
        if (snapshot.userId() != null) {
            USER_ID.set(snapshot.userId());
        }
        if (snapshot.tenantName() != null) {
            TENANT_NAME.set(snapshot.tenantName());
        }
        if (snapshot.userRole() != null) {
            USER_ROLE.set(snapshot.userRole());
        }
        log.trace("[TenantContext] Przywrócono kontekst z snapshotu: tenantId={}", snapshot.tenantId());
    }

    /**
     * Czyści kontekst tenanta po zakończeniu żądania HTTP.
     *
     * <p><strong>MUSI</strong> być wywoływane w bloku finally filtra HTTP,
     * żeby zapobiec wyciekom kontekstu między żądaniami (thread pool reuse).
     *
     * <p>Brak wywołania {@code clear()} może skutkować cross-tenant data leakage!
     */
    public static void clear() {
        UUID tenantId = TENANT_ID.get();
        TENANT_ID.remove();
        USER_ID.remove();
        TENANT_NAME.remove();
        USER_ROLE.remove();
        log.trace("[TenantContext] Wyczyszczono kontekst tenanta: {}", tenantId);
    }

    // =========================================================================
    // Snapshot record – niemutowalny, bezpieczny między wątkami
    // =========================================================================

    /**
     * Niemutowalny snapshot kontekstu tenanta do propagacji między wątkami.
     *
     * @param tenantId   UUID tenanta
     * @param userId     UUID użytkownika
     * @param tenantName nazwa tenanta (dla logowania)
     * @param userRole   rola użytkownika (np. ADMIN, SUPERVISOR, AGENT)
     */
    public record Snapshot(
            UUID tenantId,
            UUID userId,
            String tenantName,
            String userRole
    ) {}
}
