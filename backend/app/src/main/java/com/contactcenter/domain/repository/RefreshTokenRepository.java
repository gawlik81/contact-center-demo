package com.contactcenter.domain.repository;

import com.contactcenter.domain.model.RefreshToken;
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
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Znajdź token po jego wartości string (używane przy refresh i logout). */
    Optional<RefreshToken> findByToken(String token);

    /**
     * Unieważnij wszystkie aktywne tokeny użytkownika.
     * Wywoływane przy logout (unieważnia wszystkie sesje użytkownika na wszystkich urządzeniach).
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.userId = :userId AND rt.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId);

    /**
     * Unieważnij konkretny token po jego wartości.
     * Wywoływane przy token rotation (stary token unieważniamy po wystawieniu nowego).
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.token = :token")
    int revokeByToken(@Param("token") String token);

    /**
     * Usuń wygasłe tokeny (cleanup job).
     * Wywoływane przez scheduled task (nie zaimplementowany w BE-003, ale metoda gotowa).
     */
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :cutoff OR rt.revoked = true")
    int deleteExpiredAndRevoked(@Param("cutoff") Instant cutoff);

    /** Policz aktywne tokeny użytkownika (do monitoringu/audit). */
    @Query("SELECT COUNT(rt) FROM RefreshToken rt WHERE rt.userId = :userId AND rt.revoked = false AND rt.expiresAt > :now")
    long countActiveByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
