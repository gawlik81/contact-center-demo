package com.contactcenter.security;

import dev.samstevens.totp.code.*;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

import static dev.samstevens.totp.util.Utils.getDataUriForImage;

/**
 * Serwis obsługujący MFA (Multi-Factor Authentication) oparty na TOTP (RFC 6238).
 *
 * <p>Używa biblioteki {@code dev.samstevens.totp} do:
 * <ul>
 *   <li>Generowania 20-bajtowego (160-bit) TOTP secret zakodowanego w Base32</li>
 *   <li>Generowania QR code URL (format {@code otpauth://}) dla aplikacji Google Authenticator</li>
 *   <li>Weryfikacji 6-cyfrowego kodu TOTP z oknem tolerancji ±1 krok (±30s)</li>
 * </ul>
 *
 * <p>Parametry TOTP zgodne z RFC 6238 i profilem Google Authenticator:
 * <ul>
 *   <li>Algorytm: SHA1 (domyślny w Google Authenticator)</li>
 *   <li>Długość kodu: 6 cyfr</li>
 *   <li>Okres: 30 sekund</li>
 *   <li>Okno tolerancji: ±1 krok = ±30 sekund (RFC 6238 §5.2 rekomenduje nie więcej niż 1 krok)</li>
 * </ul>
 *
 * <p><strong>Ochrona przed replay attack (single-use codes):</strong>
 * Ten sam kod TOTP jest matematycznie ważny przez 90s (okno t-1, t0, t+1).
 * Po udanej weryfikacji kod jest oznaczany jako użyty w Redis z kluczem
 * {@code mfa:used:{userId}:{code}} i TTL 90s. Ponowne użycie tego samego kodu
 * w oknie tolerancji jest odrzucane.
 */
@Slf4j
@Service
public class MfaService {

    /** Nazwa issuer widoczna w aplikacji Google Authenticator / Authy. */
    private static final String ISSUER = "Contact Center";

    /**
     * Długość generowanego secret w znakach Base32.
     * 32 znaki Base32 = 160 bitów (20 bajtów) – zgodne z RFC 6238 TOTP.
     * Biblioteka dev.samstevens.totp: parametr to długość w znakach Base32.
     */
    private static final int SECRET_LENGTH_CHARS = 32;

    /** Okno tolerancji czasu: 1 = ±1 krok (±30s). */
    private static final int TIME_WINDOW = 1;

    /**
     * TTL klucza Redis dla już użytych kodów TOTP.
     * Musi pokrywać całe okno tolerancji (t-1, t0, t+1) = 3 × 30s = 90s.
     */
    private static final Duration USED_CODE_TTL = Duration.ofSeconds(90);

    /** Prefix klucza Redis dla zużytych kodów TOTP: {@code mfa:used:{userId}:{code}}. */
    private static final String USED_CODE_KEY_PREFIX = "mfa:used:";

    private final StringRedisTemplate stringRedisTemplate;

    private final SecretGenerator secretGenerator;
    private final CodeVerifier codeVerifier;
    private final QrGenerator qrGenerator;

    /**
     * Konstruktor produkcyjny – inicjalizuje TOTP komponenty i wstrzykuje Redis.
     */
    @Autowired
    public MfaService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.secretGenerator = new DefaultSecretGenerator(SECRET_LENGTH_CHARS);

        TimeProvider timeProvider = new SystemTimeProvider();
        CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        this.codeVerifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        ((DefaultCodeVerifier) this.codeVerifier).setTimePeriod(30);
        ((DefaultCodeVerifier) this.codeVerifier).setAllowedTimePeriodDiscrepancy(TIME_WINDOW);

        this.qrGenerator = new ZxingPngQrGenerator();
    }

    /**
     * Konstruktor dla testów – pozwala wstrzyknąć mock TimeProvider i mock Redis.
     */
    MfaService(StringRedisTemplate stringRedisTemplate, SecretGenerator secretGenerator,
               CodeVerifier codeVerifier, QrGenerator qrGenerator) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.secretGenerator = secretGenerator;
        this.codeVerifier = codeVerifier;
        this.qrGenerator = qrGenerator;
    }

    // =========================================================================
    // Publiczne API
    // =========================================================================

    /**
     * Generuje nowy TOTP secret zakodowany w Base32 (bez paddingu).
     *
     * <p>Secret jest 20-bajtowy (160 bitów), co odpowiada 32 znakom Base32 (bez '=').
     * Generowanie używa bezpiecznego generatora liczb losowych (SecureRandom).
     * Padding '=' jest usuwany – Google Authenticator i RFC 6238 nie wymagają go.
     *
     * @return secret zakodowany w Base32 bez paddingu (upper-case, 32 znaki A-Z2-7)
     */
    public String generateSecret() {
        // DefaultSecretGenerator może zwrócić padding '=' – usuwamy go
        // RFC 4648: padding jest opcjonalny przy Base32 i nie jest wymagany przez TOTP
        String secret = secretGenerator.generate().replace("=", "");
        log.debug("[MFA] Wygenerowano nowy TOTP secret");
        return secret;
    }

    /**
     * Generuje URI QR code (data URI: {@code data:image/png;base64,...}).
     *
     * <p>QR code zawiera URI w formacie:
     * {@code otpauth://totp/Contact%20Center:user@example.com?secret=SECRET&issuer=Contact+Center&algorithm=SHA1&digits=6&period=30}
     *
     * <p>URI jest skanowany przez Google Authenticator / Authy / 1Password.
     *
     * @param secret    TOTP secret (Base32) – wygenerowany przez {@link #generateSecret()}
     * @param userEmail email użytkownika (etykieta w aplikacji MFA)
     * @return data URI obrazu PNG z QR kodem (format: {@code data:image/png;base64,...})
     * @throws MfaException gdy nie udało się wygenerować QR code
     */
    public String generateQrCodeDataUri(String secret, String userEmail) {
        QrData qrData = new QrData.Builder()
                .label(userEmail)
                .secret(secret)
                .issuer(ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();

        try {
            byte[] imageData = qrGenerator.generate(qrData);
            String mimeType = qrGenerator.getImageMimeType();
            String dataUri = getDataUriForImage(imageData, mimeType);
            log.debug("[MFA] Wygenerowano QR code dla użytkownika: {}", userEmail);
            return dataUri;
        } catch (QrGenerationException e) {
            log.error("[MFA] Błąd generowania QR code dla {}: {}", userEmail, e.getMessage(), e);
            throw new MfaException("Nie udało się wygenerować QR code dla MFA", e);
        }
    }

    /**
     * Weryfikuje kod TOTP podany przez użytkownika z ochroną przed replay attack.
     *
     * <p>Weryfikacja z oknem tolerancji ±1 krok (±30s):
     * sprawdzany jest bieżący krok czasowy oraz jeden krok przed i jeden po.
     * Jest to zgodne z RFC 6238 §5.2 i standardową praktyką.
     *
     * <p><strong>Single-use enforcement:</strong> Po udanej weryfikacji kod jest
     * zapisywany w Redis ({@code mfa:used:{userId}:{code}}) z TTL 90s.
     * Ponowne użycie tego samego kodu w oknie tolerancji jest odrzucane,
     * co zapobiega replay attack gdy atakujący przechwyci kod podczas transmisji.
     *
     * @param secret TOTP secret użytkownika (Base32)
     * @param code   6-cyfrowy kod TOTP podany przez użytkownika
     * @param userId UUID użytkownika – wymagany do izolacji zużytych kodów per user
     * @return true gdy kod jest prawidłowy i nie był jeszcze użyty
     */
    public boolean verifyCode(String secret, String code, UUID userId) {
        if (secret == null || secret.isBlank() || code == null || code.isBlank()) {
            log.warn("[MFA] Weryfikacja TOTP: pusty secret lub kod");
            return false;
        }

        // Sanityzacja kodu – usuwa spacje (Google Authenticator czasem grupuje cyfry)
        String sanitizedCode = code.replaceAll("\\s+", "");

        // Sprawdź czy kod nie był już użyty (replay attack prevention)
        String usedCodeKey = USED_CODE_KEY_PREFIX + userId + ":" + sanitizedCode;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(usedCodeKey))) {
            log.warn("[MFA] Odmowa weryfikacji TOTP – kod już użyty (replay attack): userId={}", userId);
            return false;
        }

        boolean valid = codeVerifier.isValidCode(secret, sanitizedCode);
        log.debug("[MFA] Weryfikacja TOTP: {}", valid ? "sukces" : "nieprawidłowy kod");

        if (valid) {
            // Oznacz kod jako użyty – TTL = 90s (pełne okno tolerancji t-1, t0, t+1)
            try {
                stringRedisTemplate.opsForValue().set(usedCodeKey, "1", USED_CODE_TTL);
                log.debug("[MFA] Kod TOTP oznaczony jako użyty: userId={}", userId);
            } catch (Exception e) {
                // Błąd Redis nie blokuje operacji – logujemy warning.
                // W najgorszym przypadku ten sam kod może zostać użyty ponownie
                // w oknie 90s, co jest akceptowalnym ryzykiem vs. blokowanie użytkownika.
                log.warn("[MFA] Nie udało się zapisać użytego kodu w Redis: userId={}, error={}",
                        userId, e.getMessage());
            }
        }

        return valid;
    }

    // =========================================================================
    // Wyjątek domenowy
    // =========================================================================

    /** Wyjątek rzucany przy błędach MFA (generowanie QR, nieprawidłowa konfiguracja). */
    public static class MfaException extends RuntimeException {
        public MfaException(String message) {
            super(message);
        }
        public MfaException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
