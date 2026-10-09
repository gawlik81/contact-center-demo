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

    /** Policz aktywne tokeny użytkownika (do monitoringu/audit). */
    @Query("SELECT COUNT(rt) FROM RefreshToken rt WHERE rt.userId = :userId AND rt.revoked = false AND rt.expiresAt > :now")
    long countActiveByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
