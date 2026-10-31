package com.contactcenter.domain.email;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Klucze S3 załączników e-mail: jedno źródło prawdy dla ich budowania (zapis) oraz dla
 * odczytu i weryfikacji (retencja EPIC-30, BE-125; walidacja wysyłki/pobierania, BE-143).
 *
 * <p>Budowanie kluczy ({@link #inboundKey}, {@link #pendingKey}) i allow-lista prefiksu
 * ({@link #isOwnedByTenant}) żyją w jednej klasie celowo — allow-lista nie może się rozjechać
 * ze schematem kluczy zapisywanych przez {@link EmailAttachmentStorageServiceImpl}.
 *
 * <h3>Skąd brać klucze do usunięcia</h3>
 * Wyłącznie z {@code email_message.attachments[*].s3_key} (BE-124 §2 — komentarz V010 i javadoc
 * {@link EmailMessage} mówią {@code s3_url}, ale w danych jest {@code s3_key}). Klucze wiadomości
 * INBOUND wskazują na {@code email-attachments/{tenantId}/{messageId}/…}, wiadomości OUTBOUND na
 * {@code email-attachments/{tenantId}/pending/{uuid}/…} (klucz z uploadu agenta, bez przenoszenia).
 *
 * <h3>Klucz z bazy albo z żądania to dane niezaufane</h3>
 * Klucze OUTBOUND pochodzą od klienta ({@code EmailReplyRequest.PendingAttachment#s3Key}).
 * Wysyłka ({@code EmailSendServiceImpl}) i pobieranie ({@code EmailAttachmentController})
 * odrzucają całe żądanie, gdy klucz nie przejdzie {@link #isOwnedByTenant} (BE-143); purge
 * (BE-125) traktuje odrzucony klucz łagodniej — pomija sam obiekt S3, ale usuwa wiersz wiadomości.
 * Bez tej allow-listy każda z tych ścieżek mogłaby odsłonić/skasować cudzy obiekt S3
 * (inny tenant, nagranie, EML).
 *
 * <h3>Decyzja API BE-143 (pkt c) — allow-lista jako publiczny, reużywalny kontrakt</h3>
 * {@link #isOwnedByTenant(UUID, String)} jest publiczna od BE-143 właśnie po to, żeby wysyłka
 * ({@code domain.email}) i pobieranie ({@code api.email}) używały TEJ SAMEJ implementacji zamiast
 * własnych kopii {@code startsWith}. {@link #isRecordingKeyOwnedByTenant(UUID, String)} obsługuje
 * odrębny schemat kluczy {@code {tenantId}/…} (nagrania, EML) i jest przygotowana pod przyszłe
 * BE-126 (pkt b — purge {@code contact.recording_url}), BE-129 ({@code GdprServiceImpl}) i BE-131
 * (sweep bucketu), żeby te tickety nie tworzyły własnych, rozjeżdżających się allow-list. BE-143
 * SAMO jej nie używa (dotyczy wyłącznie schematu {@code email-attachments/{tenantId}/}).
 */
@Slf4j
public final class EmailAttachmentKeys {

    /** Wspólny korzeń wszystkich kluczy załączników e-mail w buckecie. */
    static final String ROOT_PREFIX = "email-attachments/";

    /** Nazwa pola w JSONB {@code attachments}, które niesie klucz S3 załącznika. */
    static final String S3_KEY_FIELD = "s3_key";

    /**
     * Stara nazwa pola z komentarza V010 / javadoc {@link EmailMessage}. Writery zapisują {@code s3_key},
     * więc ta nazwa służy wyłącznie do nazwania rodzaju anomalii w ostrzeżeniu ({@link #extractS3Keys}).
     */
    private static final String LEGACY_S3_URL_FIELD = "s3_url";

    /** Maksymalna długość klucza w logach (klucz z bazy jest niezaufany — nie zalewamy nim logów). */
    private static final int LOG_KEY_MAX_LENGTH = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EmailAttachmentKeys() {
        throw new UnsupportedOperationException("Klasa narzędziowa – nie instancjonować");
    }

    // =========================================================================
    // Budowanie kluczy (zapis)
    // =========================================================================

    /** Prefiks wszystkich kluczy załączników danego tenanta: {@code email-attachments/{tenantId}/}. */
    static String tenantPrefix(UUID tenantId) {
        return ROOT_PREFIX + tenantId + "/";
    }

    /** Klucz załącznika wiadomości INBOUND: {@code email-attachments/{tenantId}/{messageId}/{filename}}. */
    static String inboundKey(UUID tenantId, UUID messageId, String encodedFilename) {
        return tenantPrefix(tenantId) + messageId + "/" + encodedFilename;
    }

    /** Klucz załącznika z uploadu agenta: {@code email-attachments/{tenantId}/pending/{pendingId}/{filename}}. */
    static String pendingKey(UUID tenantId, UUID pendingId, String encodedFilename) {
        return tenantPrefix(tenantId) + "pending/" + pendingId + "/" + encodedFilename;
    }

    // =========================================================================
    // Allow-lista (odczyt/usuwanie)
    // =========================================================================

    /**
     * Sprawdza, czy klucz jest bezpieczny do użycia (usunięcia, pobrania, dołączenia do wysyłki)
     * w imieniu tenanta: zaczyna się od {@code email-attachments/{tenantId}/}, ma coś za
     * prefiksem, nie zawiera segmentów {@code .}/{@code ..} (próba wyjścia z prefiksu) ani
     * znaków sterujących.
     *
     * <p>Publiczna (BE-143) — jedyna implementacja allow-listy dla wysyłki
     * ({@code EmailSendServiceImpl}), pobierania ({@code EmailAttachmentController}, pakiet
     * {@code api.email}) i purge (BE-125, {@code EmailMessageServiceImpl}).
     *
     * @param tenantId UUID tenanta wykonującego operację
     * @param s3Key    klucz odczytany z bazy albo z żądania klienta (niezaufany)
     * @return {@code true} gdy klucz wolno przekazać do {@link EmailAttachmentStorageService}
     */
    public static boolean isOwnedByTenant(UUID tenantId, String s3Key) {
        if (tenantId == null) {
            return false;
        }
        return hasCleanPrefixedSuffix(tenantPrefix(tenantId), s3Key);
    }

    /**
     * Allow-lista dla kluczy S3 POZA schematem załączników e-mail: nagrania rozmów
     * ({@code RecordingServiceImpl#buildS3Key}) i pliki EML zapisane w
     * {@code contact.recording_url} ({@code EmailEmlService}) — schemat {@code {tenantId}/…},
     * BEZ korzenia {@link #ROOT_PREFIX} (inny root niż załączniki e-mail).
     *
     * <p>Te same reguły co {@link #isOwnedByTenant(UUID, String)}: niepusty sufiks za
     * prefiksem, bez segmentów {@code .}/{@code ..}, bez znaków sterujących.
     *
     * <p><strong>Decyzja API BE-143 (pkt c):</strong> przygotowana pod przyszłe BE-126 (pkt b),
     * BE-129 i BE-131 (zob. javadoc klasy) — BE-143 samo jej nie wywołuje.
     *
     * @param tenantId UUID tenanta wykonującego operację
     * @param s3Key    klucz odczytany z bazy ({@code contact.recording_url}), niezaufany
     * @return {@code true} gdy klucz należy do tenanta i nie próbuje wyjść z prefiksu
     */
    public static boolean isRecordingKeyOwnedByTenant(UUID tenantId, String s3Key) {
        if (tenantId == null) {
            return false;
        }
        return hasCleanPrefixedSuffix(tenantId + "/", s3Key);
    }

    /**
     * Wspólna logika obu allow-list: {@code s3Key} zaczyna się od {@code prefix}, ma coś za
     * prefiksem, i ten sufiks nie zawiera segmentów {@code .}/{@code ..} ani znaków sterujących.
     */
    private static boolean hasCleanPrefixedSuffix(String prefix, String s3Key) {
        if (s3Key == null) {
            return false;
        }
        if (!s3Key.startsWith(prefix) || s3Key.length() == prefix.length()) {
            return false;
        }
        if (s3Key.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        for (String segment : s3Key.substring(prefix.length()).split("/", -1)) {
            if (segment.equals("..") || segment.equals(".")) {
                return false;
            }
        }
        return true;
    }

    // =========================================================================
    // Odczyt kluczy z JSONB attachments
    // =========================================================================

    /**
     * Wyciąga klucze S3 z kolumny JSONB {@code attachments} (jako tekst). Odporna na dane, które nie
     * powinny wystąpić, ale w bazie mogą: {@code null}/pusty tekst, {@code []}, uszkodzony JSON,
     * korzeń niebędący tablicą, wpisy niebędące obiektami, brak pola {@code s3_key}, pole
     * niebędące tekstem oraz pusty klucz {@code ""} ({@code EmailSendServiceImpl#buildAttachmentsJson}
     * zapisuje {@code ""} przy {@code s3Key == null}). Nigdy nie rzuca wyjątku.
     *
     * <p>Wpis-obiekt, z którego nie da się odczytać klucza, bo NIE ma tekstowego {@code s3_key}
     * (stary format {@code s3_url}, {@code s3_key} niebędący tekstem, brak obu pól), jest pomijany
     * z jednym ostrzeżeniem {@code WARN} na wiadomość: {@code messageId} i liczba wpisów każdego rodzaju.
     * Wartości pól z bazy nie trafiają do logu. Pusty {@code s3_key} ({@code ""}) jest poprawny i
     * nie generuje ostrzeżenia.
     *
     * @param messageId       UUID wiadomości (wyłącznie do logów)
     * @param attachmentsJson zawartość kolumny {@code attachments} rzutowana na tekst
     * @return niepuste klucze, bez duplikatów, w kolejności występowania (nigdy {@code null})
     */
    static List<String> extractS3Keys(UUID messageId, String attachmentsJson) {
        if (attachmentsJson == null || attachmentsJson.isBlank()) {
            return List.of();
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(attachmentsJson);
        } catch (JsonProcessingException e) {
            log.warn("[EmailAttachmentKeys] Nie można sparsować attachments: messageId={}, error={}",
                    messageId, e.getOriginalMessage());
            return List.of();
        }
        if (root == null || !root.isArray()) {
            log.warn("[EmailAttachmentKeys] attachments nie jest tablicą JSON: messageId={}", messageId);
            return List.of();
        }

        Set<String> keys = new LinkedHashSet<>();
        int skippedEntries = 0;
        int legacyS3UrlEntries = 0;
        int nonTextKeyEntries = 0;
        int missingKeyEntries = 0;
        for (JsonNode entry : root) {
            if (!entry.isObject()) {
                skippedEntries++;
                continue;
            }
            JsonNode keyNode = entry.get(S3_KEY_FIELD);
            if (keyNode != null && keyNode.isTextual()) {
                if (!keyNode.asText().isBlank()) {
                    keys.add(keyNode.asText());
                }
                // Pusty/biały tekst to zapis zgodny z kontraktem (EmailSendServiceImpl#buildAttachmentsJson
                // pisze "" przy s3Key == null) — nic do usunięcia w S3 i nic do ostrzegania.
                continue;
            }
            // Wpis-obiekt bez tekstowego s3_key to anomalia: wiersz zostanie usunięty, a obiekt S3
            // (jeśli istnieje) zostanie osierocony. Rodzaje liczymy osobno, wartości NIE trafiają do logu.
            if (keyNode != null) {
                nonTextKeyEntries++;          // s3_key jest liczbą/obiektem/tablicą/bool/null
            } else if (entry.has(LEGACY_S3_URL_FIELD)) {
                legacyS3UrlEntries++;         // stary format z komentarza V010 (s3_url zamiast s3_key)
            } else {
                missingKeyEntries++;          // ani s3_key, ani s3_url
            }
        }
        if (skippedEntries > 0) {
            log.warn("[EmailAttachmentKeys] Pominięto {} wpis(ów) attachments niebędących obiektami: messageId={}",
                    skippedEntries, messageId);
        }
        if (legacyS3UrlEntries + nonTextKeyEntries + missingKeyEntries > 0) {
            log.warn("[EmailAttachmentKeys] Wpisy attachments bez tekstowego s3_key — powiązane obiekty S3 "
                            + "(jeśli istnieją) nie zostaną usunięte: messageId={}, staryFormatS3Url={}, "
                            + "nietekstowyS3Key={}, brakS3Key={}",
                    messageId, legacyS3UrlEntries, nonTextKeyEntries, missingKeyEntries);
        }
        return List.copyOf(keys);
    }

    // =========================================================================
    // Logowanie
    // =========================================================================

    /**
     * Wersja klucza bezpieczna do logów: znaki sterujące zastąpione {@code ?}, długość ograniczona
     * (klucze OUTBOUND to dane od klienta — bez tego możliwe wstrzyknięcie linii do logu).
     *
     * <p>Za „znak sterujący" uznajemy nie tylko {@link Character#isISOControl} (C0/C1, w tym
     * {@code \n}/{@code \r}), ale też separatory linii/akapitu U+2028/U+2029 (część odbiorców logów
     * traktuje je jak koniec linii) oraz znaki kierunku pisma (Unicode {@code Bidi_Control}:
     * U+061C, U+200E/U+200F, U+202A–U+202E, U+2066–U+2069), które pozwalają „odwrócić" fragment
     * wiersza logu i sfałszować jego treść.
     *
     * <p>Publiczna (BE-143) — używana też w {@code api.email.EmailAttachmentController} przy
     * logowaniu kluczy odrzuconych przez {@link #isOwnedByTenant}.
     */
    public static String forLog(String s3Key) {
        if (s3Key == null) {
            return "null";
        }
        String printable = s3Key.chars()
                .limit(LOG_KEY_MAX_LENGTH)
                .collect(StringBuilder::new,
                        (sb, c) -> sb.append(isUnsafeForLog(c) ? '?' : (char) c),
                        StringBuilder::append)
                .toString();
        return s3Key.length() > LOG_KEY_MAX_LENGTH ? printable + "…" : printable;
    }

    private static boolean isUnsafeForLog(int c) {
        return Character.isISOControl(c)
                || c == 0x2028 || c == 0x2029                    // separator linii / akapitu
                || c == 0x061C                                   // ALM
                || c == 0x200E || c == 0x200F                    // LRM, RLM
                || (c >= 0x202A && c <= 0x202E)                  // LRE, RLE, PDF, LRO, RLO
                || (c >= 0x2066 && c <= 0x2069);                 // LRI, RLI, FSI, PDI
    }
}
