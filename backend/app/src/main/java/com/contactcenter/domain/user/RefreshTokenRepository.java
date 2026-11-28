package com.contactcenter.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium JPA dla encji {@link RefreshToken}.
 *
 * <p>Operacje wymagają transakcji dla metod @Modifying
 * (zarządzane przez warstwę serwisową z @Transactional).
 */
/**
 * Deliberately NOT a {@code TenantAwareRepository} (BE-249 review A1): {@code refresh_token} is a global technical table
 * without RLS, used by login/refresh BEFORE any tenant context exists, and {@code tenant_id} is NULL for SUPER_ADMIN.
 * Queries are scoped by {@code user_id} (a globally unique UUID taken from a signed token or the verified user row).
 */
@Repository
interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Znajdź token po jego wartości string (używane przy refresh i logout). */
    Optional<RefreshToken> findByToken(String token);

    /**
     * Unieważnij wszystkie aktywne tokeny użytkownika.
     * Wywoływane przy logout (unieważnia wszystkie sesje użytkownika na wszystkich urządzeniach).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.userId = :userId AND rt.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId);

    /** BE-249 (N-S3): revokes a token by value ONLY when it belongs to {@code userId} (logout must not revoke foreign tokens). */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.token = :token AND rt.userId = :userId")
    int revokeByTokenAndUserId(@Param("token") String token, @Param("userId") UUID userId);

    /**
     * Unieważnij konkretny token po jego wartości.
     * Wywoływane przy token rotation (stary token unieważniamy po wystawieniu nowego).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.token = :token")
    int revokeByToken(@Param("token") String token);

    /**
     * Usuń tokeny, które przestały być potrzebne (cleanup job, BE-122 -
     * {@link RefreshTokenCleanupJob}).
     *
     * <p>Semantyka z okresem karencji ({@code cutoff = now - grace-days}):
     * <ul>
     *   <li>wygasły token ({@code expires_at < cutoff}) - usuwany niezależnie od flagi revoked,</li>
     *   <li>unieważniony token ({@code is_revoked = true}) - usuwany dopiero gdy został wystawiony
     *       przed {@code cutoff} ({@code created_at < cutoff}); świeżo unieważnione tokeny (rotacja,
     *       logout) zostają, aby ponowne użycie starego tokenu (replay) było nadal rozpoznawane
     *       w {@code AuthServiceImpl#refresh} jako "unieważniony", a nie "nieznany".</li>
     * </ul>
     * Tabela jest globalna (bez RLS) - zapytanie obejmuje też tokeny z {@code tenant_id IS NULL}
     * (SUPER_ADMIN).
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :cutoff "
            + "OR (rt.revoked = true AND rt.createdAt < :cutoff)")
    int deleteExpiredAndRevoked(@Param("cutoff") Instant cutoff);

    /** BE-249: revokes the active tokens of one login session (device logout). */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.userId = :userId "
            + "AND COALESCE(rt.sessionId, rt.id) = :sessionId AND rt.revoked = false")
    int revokeBySessionId(@Param("userId") UUID userId, @Param("sessionId") UUID sessionId);

    /**
     * BE-249: distinct login sessions of the user with a still-active refresh token other than
     * {@code keepSessionId} - the sessions whose access tokens get a per-session revocation marker.
     */
    @Query("SELECT DISTINCT COALESCE(rt.sessionId, rt.id) FROM RefreshToken rt WHERE rt.userId = :userId "
            + "AND rt.revoked = false AND rt.expiresAt > :now AND COALESCE(rt.sessionId, rt.id) <> :keepSessionId")
    java.util.List<UUID> findActiveSessionIdsExcept(@Param("userId") UUID userId,
                                                    @Param("keepSessionId") UUID keepSessionId,
                                                    @Param("now") Instant now);

    /** Policz aktywne tokeny użytkownika (do monitoringu/audit). */
    @Query("SELECT COUNT(rt) FROM RefreshToken rt WHERE rt.userId = :userId AND rt.revoked = false AND rt.expiresAt > :now")
    long countActiveByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
