package com.contactcenter.domain.email;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testy jednostkowe {@link EmailAttachmentKeys} (BE-125): budowanie kluczy, allow-lista prefiksu
 * tenanta i odporne wyciąganie {@code s3_key} z JSONB {@code attachments}.
 */
@DisplayName("EmailAttachmentKeys – klucze S3 załączników e-mail (BE-125)")
class EmailAttachmentKeysTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID MESSAGE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PENDING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    // =========================================================================
    // Budowanie kluczy — schemat nie może się zmienić bez świadomej decyzji (allow-lista od niego zależy)
    // =========================================================================

    @Nested
    @DisplayName("budowanie kluczy")
    class Building {

        @Test
        @DisplayName("inboundKey = email-attachments/{tenantId}/{messageId}/{filename}")
        void inboundKey_hasDocumentedLayout() {
            assertThat(EmailAttachmentKeys.inboundKey(TENANT_A, MESSAGE_ID, "umowa.pdf"))
                    .isEqualTo("email-attachments/" + TENANT_A + "/" + MESSAGE_ID + "/umowa.pdf");
        }

        @Test
        @DisplayName("pendingKey = email-attachments/{tenantId}/pending/{uuid}/{filename}")
        void pendingKey_hasDocumentedLayout() {
            assertThat(EmailAttachmentKeys.pendingKey(TENANT_A, PENDING_ID, "a.png"))
                    .isEqualTo("email-attachments/" + TENANT_A + "/pending/" + PENDING_ID + "/a.png");
        }

        @Test
        @DisplayName("klucze zbudowane przez writerów zawsze przechodzą własną allow-listę")
        void builtKeys_areOwnedByTheirTenant() {
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A,
                    EmailAttachmentKeys.inboundKey(TENANT_A, MESSAGE_ID, "f.pdf"))).isTrue();
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A,
                    EmailAttachmentKeys.pendingKey(TENANT_A, PENDING_ID, "f.pdf"))).isTrue();
        }
    }

    // =========================================================================
    // Allow-lista prefiksu
    // =========================================================================

    @Nested
    @DisplayName("isOwnedByTenant – allow-lista prefiksu email-attachments/{tenantId}/")
    class Allowlist {

        @Test
        @DisplayName("klucz INBOUND i OUTBOUND (pending/) własnego tenanta jest dozwolony")
        void ownKeys_allowed() {
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A,
                    "email-attachments/" + TENANT_A + "/" + MESSAGE_ID + "/a.pdf")).isTrue();
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A,
                    "email-attachments/" + TENANT_A + "/pending/" + PENDING_ID + "/a.pdf")).isTrue();
        }

        @Test
        @DisplayName("klucz innego tenanta jest odrzucony")
        void foreignTenantKey_rejected() {
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A,
                    "email-attachments/" + TENANT_B + "/" + MESSAGE_ID + "/a.pdf")).isFalse();
        }

        @Test
        @DisplayName("klucz z innego korzenia (nagranie mp3, EML kontaktu, plugin) jest odrzucony")
        void nonAttachmentKeys_rejected() {
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A, TENANT_A + "/2026/09/" + MESSAGE_ID + ".mp3")).isFalse();
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A, TENANT_A + "/2026/09/" + MESSAGE_ID + ".eml")).isFalse();
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A, "plugins/x/y.jar")).isFalse();
        }

        @Test
        @DisplayName("sam prefiks (bez nazwy obiektu), null i pusty klucz są odrzucone")
        void degenerateKeys_rejected() {
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A, "email-attachments/" + TENANT_A + "/")).isFalse();
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A, "email-attachments/" + TENANT_A)).isFalse();
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A, "")).isFalse();
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A, null)).isFalse();
            assertThat(EmailAttachmentKeys.isOwnedByTenant(null, "email-attachments/x/y")).isFalse();
        }

        @Test
        @DisplayName("prefiks tenanta jest sprawdzany ze slashem — UUID będący prefiksem innego napisu nie przechodzi")
        void prefixMustEndAtSegmentBoundary() {
            // ten sam początek co tenant A, ale dalszy ciąg znaków UUID — to już inny „tenant"
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A,
                    "email-attachments/" + TENANT_A + "0/file")).isFalse();
        }

        @ParameterizedTest(name = "próba wyjścia z prefiksu: {0}")
        @ValueSource(strings = {
                "email-attachments/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/../bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/x/a.pdf",
                "email-attachments/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/x/../../y",
                "email-attachments/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/./x",
                "email-attachments/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/x/.."
        })
        @DisplayName("segmenty . i .. są odrzucane")
        void pathTraversal_rejected(String key) {
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A, key)).isFalse();
        }

        @Test
        @DisplayName("nazwa pliku z kropkami w środku (np. archive..tar) nie jest segmentem '..'")
        void dotsInsideFilename_allowed() {
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A,
                    "email-attachments/" + TENANT_A + "/" + MESSAGE_ID + "/archive..tar.gz")).isTrue();
        }

        @Test
        @DisplayName("znaki sterujące w kluczu (wstrzyknięcie linii do logów) są odrzucane")
        void controlCharacters_rejected() {
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A,
                    "email-attachments/" + TENANT_A + "/x\nERROR fake")).isFalse();
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_A,
                    "email-attachments/" + TENANT_A + "/x\u0000y")).isFalse();
        }
    }

    // =========================================================================
    // isRecordingKeyOwnedByTenant – allow-lista schematu {tenantId}/… (przygotowanie API BE-143
    // pkt c dla BE-126/BE-129/BE-131; sama BE-143 tej metody nie używa)
    // =========================================================================

    @Nested
    @DisplayName("isRecordingKeyOwnedByTenant – allow-lista prefiksu {tenantId}/ (nagrania, EML)")
    class RecordingKeyAllowlist {

        @Test
        @DisplayName("klucz nagrania i EML własnego tenanta ({tenantId}/rrrr/mm/x.ext) jest dozwolony")
        void ownRecordingAndEmlKeys_allowed() {
            assertThat(EmailAttachmentKeys.isRecordingKeyOwnedByTenant(TENANT_A,
                    TENANT_A + "/2026/09/" + MESSAGE_ID + ".mp3")).isTrue();
            assertThat(EmailAttachmentKeys.isRecordingKeyOwnedByTenant(TENANT_A,
                    TENANT_A + "/2026/09/" + MESSAGE_ID + ".eml")).isTrue();
        }

        @Test
        @DisplayName("klucz innego tenanta jest odrzucony")
        void foreignTenantKey_rejected() {
            assertThat(EmailAttachmentKeys.isRecordingKeyOwnedByTenant(TENANT_A,
                    TENANT_B + "/2026/09/" + MESSAGE_ID + ".mp3")).isFalse();
        }

        @Test
        @DisplayName("klucz ze schematu załączników e-mail (email-attachments/{tenantId}/…) jest odrzucony — inny korzeń")
        void attachmentSchemeKey_rejected() {
            assertThat(EmailAttachmentKeys.isRecordingKeyOwnedByTenant(TENANT_A,
                    "email-attachments/" + TENANT_A + "/" + MESSAGE_ID + "/a.pdf")).isFalse();
        }

        @Test
        @DisplayName("sam prefiks, null i pusty klucz, null tenant są odrzucone")
        void degenerateKeys_rejected() {
            assertThat(EmailAttachmentKeys.isRecordingKeyOwnedByTenant(TENANT_A, TENANT_A + "/")).isFalse();
            assertThat(EmailAttachmentKeys.isRecordingKeyOwnedByTenant(TENANT_A, TENANT_A.toString())).isFalse();
            assertThat(EmailAttachmentKeys.isRecordingKeyOwnedByTenant(TENANT_A, "")).isFalse();
            assertThat(EmailAttachmentKeys.isRecordingKeyOwnedByTenant(TENANT_A, null)).isFalse();
            assertThat(EmailAttachmentKeys.isRecordingKeyOwnedByTenant(null, TENANT_A + "/x")).isFalse();
        }

        @ParameterizedTest(name = "próba wyjścia z prefiksu: {0}")
        @ValueSource(strings = {
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/../bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/x.mp3",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/./x.mp3"
        })
        @DisplayName("segmenty . i .. są odrzucane")
        void pathTraversal_rejected(String key) {
            assertThat(EmailAttachmentKeys.isRecordingKeyOwnedByTenant(TENANT_A, key)).isFalse();
        }

        @Test
        @DisplayName("znaki sterujące w kluczu są odrzucane")
        void controlCharacters_rejected() {
            assertThat(EmailAttachmentKeys.isRecordingKeyOwnedByTenant(TENANT_A,
                    TENANT_A + "/x\nERROR fake")).isFalse();
        }
    }

    // =========================================================================
    // Wyciąganie kluczy z JSONB
    // =========================================================================

    @Nested
    @DisplayName("extractS3Keys – odporność na dane z bazy")
    class Extraction {

        @Test
        @DisplayName("dwa załączniki → dwa klucze w kolejności występowania")
        void twoAttachments_returnsBothKeys() {
            String json = """
                    [{"filename":"a.pdf","content_type":"application/pdf","size_bytes":10,"s3_key":"k/a.pdf"},
                     {"filename":"b.png","content_type":"image/png","size_bytes":20,"s3_key":"k/b.png"}]
                    """;

            assertThat(EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, json)).containsExactly("k/a.pdf", "k/b.png");
        }

        @Test
        @DisplayName("ten sam klucz w tablicy dwa razy → jeden klucz")
        void duplicateKeys_areDeduplicated() {
            String json = "[{\"s3_key\":\"k/a\"},{\"s3_key\":\"k/a\"}]";

            assertThat(EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, json)).containsExactly("k/a");
        }

        @Test
        @DisplayName("null, pusty tekst, [] i {} → brak kluczy, bez wyjątku")
        void emptyInputs_returnNoKeys() {
            assertThat(EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, null)).isEmpty();
            assertThat(EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, "")).isEmpty();
            assertThat(EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, "   ")).isEmpty();
            assertThat(EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, "[]")).isEmpty();
            assertThat(EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, "{}")).isEmpty();
            assertThat(EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, "null")).isEmpty();
        }

        @Test
        @DisplayName("uszkodzony JSON → brak kluczy, bez wyjątku")
        void brokenJson_returnsNoKeys() {
            assertThat(EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, "[{\"s3_key\":")).isEmpty();
            assertThat(EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, "to nie jest json")).isEmpty();
            assertThat(EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, "[{'s3_key':'x'}]")).isEmpty();
        }

        @Test
        @DisplayName("JSON zagnieżdżony poza limit głębokości Jacksona → brak kluczy, bez wyjątku")
        void tooDeepJson_returnsNoKeys() {
            String deep = "[".repeat(5000) + "]".repeat(5000);

            assertThat(EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, deep)).isEmpty();
        }

        @Test
        @DisplayName("brak pola s3_key, pusty klucz, klucz nietekstowy i null → pominięte")
        void missingOrEmptyKey_isSkipped() {
            String json = """
                    [{"filename":"x"},
                     {"s3_key":""},
                     {"s3_key":"   "},
                     {"s3_key":5},
                     {"s3_key":null},
                     {"s3_key":{"nested":"obj"}},
                     {"s3_key":"k/ok"}]
                    """;

            assertThat(EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, json)).containsExactly("k/ok");
        }

        @Test
        @DisplayName("wpisy niebędące obiektami (liczba, tekst, null, tablica) → pominięte")
        void nonObjectEntries_areSkipped() {
            String json = "[1, \"s3_key\", null, [\"k/nested\"], {\"s3_key\":\"k/ok\"}]";

            assertThat(EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, json)).containsExactly("k/ok");
        }

        @Test
        @DisplayName("stare pole s3_url z komentarza V010 NIE jest traktowane jak klucz")
        void legacyS3UrlField_isIgnored() {
            assertThat(EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, "[{\"s3_url\":\"k/legacy\"}]")).isEmpty();
        }

        @Test
        @DisplayName("wynik jest niemodyfikowalny")
        void result_isImmutable() {
            List<String> keys = EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, "[{\"s3_key\":\"k/a\"}]");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> keys.add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =========================================================================
    // BE125-10: wpisy bez tekstowego s3_key nie znikają bez śladu
    // =========================================================================

    @Nested
    @DisplayName("extractS3Keys – ostrzeżenia o wpisach bez tekstowego s3_key (BE125-10)")
    class AnomalyWarnings {

        private ch.qos.logback.classic.Logger keysLogger;
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void attachAppender() {
            keysLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EmailAttachmentKeys.class);
            appender = new ListAppender<>();
            appender.start();
            keysLogger.addAppender(appender);
        }

        @AfterEach
        void detachAppender() {
            keysLogger.detachAppender(appender);
            appender.stop();
        }

        /** Sformatowane komunikaty WARN wyemitowane przez {@link EmailAttachmentKeys}. */
        private List<String> warnings() {
            return appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        @Test
        @DisplayName("stary format s3_url → klucza brak, jedno WARN z messageId i rodzajem; wartość URL NIE trafia do logu")
        void legacyS3Url_warnsWithoutLeakingValue() {
            List<String> keys = EmailAttachmentKeys.extractS3Keys(MESSAGE_ID,
                    "[{\"filename\":\"a.pdf\",\"s3_url\":\"https://sekret.example/tajny-obiekt.pdf\"}]");

            assertThat(keys).isEmpty();
            assertThat(warnings()).singleElement().satisfies(msg -> {
                assertThat(msg).contains("messageId=" + MESSAGE_ID)
                        .contains("staryFormatS3Url=1")
                        .contains("nietekstowyS3Key=0")
                        .contains("brakS3Key=0")
                        .doesNotContain("sekret.example")
                        .doesNotContain("tajny-obiekt");
            });
        }

        @Test
        @DisplayName("s3_key niebędący tekstem (liczba, obiekt, tablica, bool, null) → WARN z licznikiem nietekstowyS3Key; zawartość nie wycieka")
        void nonTextS3Key_warnsWithoutLeakingValue() {
            String json = """
                    [{"s3_key":5},
                     {"s3_key":{"nested":"tajna-wartosc-obiektu"}},
                     {"s3_key":["tajna-wartosc-tablicy"]},
                     {"s3_key":true},
                     {"s3_key":null}]
                    """;

            List<String> keys = EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, json);

            assertThat(keys).isEmpty();
            assertThat(warnings()).singleElement().satisfies(msg -> {
                assertThat(msg).contains("messageId=" + MESSAGE_ID)
                        .contains("nietekstowyS3Key=5")
                        .contains("staryFormatS3Url=0")
                        .contains("brakS3Key=0")
                        .doesNotContain("tajna-wartosc");
            });
        }

        @Test
        @DisplayName("obiekt bez s3_key i bez s3_url → WARN z licznikiem brakS3Key")
        void missingS3Key_warns() {
            List<String> keys = EmailAttachmentKeys.extractS3Keys(MESSAGE_ID,
                    "[{\"filename\":\"bez-klucza.pdf\",\"content_type\":\"application/pdf\",\"size_bytes\":1}]");

            assertThat(keys).isEmpty();
            assertThat(warnings()).singleElement().satisfies(msg -> {
                assertThat(msg).contains("messageId=" + MESSAGE_ID)
                        .contains("brakS3Key=1")
                        .contains("staryFormatS3Url=0")
                        .contains("nietekstowyS3Key=0")
                        .doesNotContain("bez-klucza.pdf");
            });
        }

        @Test
        @DisplayName("mieszanka anomalii + poprawny wpis → poprawny klucz zwrócony, JEDNO zbiorcze WARN z licznikami wszystkich rodzajów")
        void mixedEntries_returnValidKeyAndSingleAggregatedWarning() {
            String json = """
                    [{"s3_url":"legacy-1"},
                     {"s3_url":"legacy-2"},
                     {"s3_key":5},
                     {"s3_key":null},
                     {"filename":"bez-klucza"},
                     {"s3_key":"k/ok"}]
                    """;

            List<String> keys = EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, json);

            assertThat(keys).containsExactly("k/ok");
            assertThat(warnings()).singleElement().satisfies(msg -> {
                assertThat(msg).contains("messageId=" + MESSAGE_ID)
                        .contains("staryFormatS3Url=2")
                        .contains("nietekstowyS3Key=2")
                        .contains("brakS3Key=1");
            });
        }

        @Test
        @DisplayName("pusty i biały s3_key (zapis EmailSendServiceImpl przy s3Key == null) oraz poprawne klucze → BRAK ostrzeżeń")
        void emptyStringKey_isLegitimateAndSilent() {
            List<String> keys = EmailAttachmentKeys.extractS3Keys(MESSAGE_ID,
                    "[{\"s3_key\":\"\"},{\"s3_key\":\"   \"},{\"s3_key\":\"k/a\"},{\"s3_key\":\"k/b\"}]");

            assertThat(keys).containsExactly("k/a", "k/b");
            assertThat(warnings()).isEmpty();
        }

        @Test
        @DisplayName("null, pusty tekst i [] → BRAK ostrzeżeń o wpisach (nic do przetwarzania)")
        void nothingToProcess_isSilent() {
            EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, null);
            EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, "");
            EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, "[]");

            assertThat(warnings()).isEmpty();
        }

        @Test
        @DisplayName("wpis-nie-obiekt i wpis-obiekt bez s3_key to dwa niezależne ostrzeżenia (istniejące zachowanie zachowane)")
        void nonObjectEntryAndAnomalousObject_produceTwoWarnings() {
            List<String> keys = EmailAttachmentKeys.extractS3Keys(MESSAGE_ID, "[42, {\"s3_url\":\"x\"}, {\"s3_key\":\"k/ok\"}]");

            assertThat(keys).containsExactly("k/ok");
            assertThat(warnings()).hasSize(2)
                    .anySatisfy(msg -> assertThat(msg).contains("niebędących obiektami").contains("messageId=" + MESSAGE_ID))
                    .anySatisfy(msg -> assertThat(msg).contains("staryFormatS3Url=1").contains("messageId=" + MESSAGE_ID));
        }
    }

    // =========================================================================
    // Logowanie
    // =========================================================================

    @Nested
    @DisplayName("forLog – klucz bezpieczny do logów")
    class ForLog {

        @Test
        @DisplayName("znaki sterujące zastąpione '?', długi klucz skrócony, null → \"null\"")
        void sanitizesAndTruncates() {
            assertThat(EmailAttachmentKeys.forLog("a\nb\rc\td")).isEqualTo("a?b?c?d");
            assertThat(EmailAttachmentKeys.forLog(null)).isEqualTo("null");
            assertThat(EmailAttachmentKeys.forLog("x".repeat(500))).hasSize(201).endsWith("…");
            assertThat(EmailAttachmentKeys.forLog("krótki")).isEqualTo("krótki");
        }

        // BE125-11: separatory linii/akapitu i znaki kierunku pisma (Bidi_Control) to też „znaki sterujące" w logu
        @ParameterizedTest(name = "U+{0} → '?'")
        @ValueSource(ints = {
                0x2028, 0x2029,                                 // separator linii / akapitu
                0x200E, 0x200F,                                 // LRM, RLM
                0x202A, 0x202B, 0x202C, 0x202D, 0x202E,         // LRE, RLE, PDF, LRO, RLO
                0x2066, 0x2067, 0x2068, 0x2069,                 // LRI, RLI, FSI, PDI
                0x061C,                                         // ALM
                0x0000, 0x0085, 0x007F                          // regresja: ISO control (NUL, NEL, DEL)
        })
        @DisplayName("znaki sterujące, separatory linii/akapitu i znaki kierunku pisma są zastępowane '?'")
        void lineSeparatorsAndBidiControls_areReplaced(int codePoint) {
            String key = "a" + new String(Character.toChars(codePoint)) + "b";

            assertThat(EmailAttachmentKeys.forLog(key)).isEqualTo("a?b");
        }

        @Test
        @DisplayName("spoofing wiersza logu: RLO + U+2028 wewnątrz klucza nie przechodzi do wyniku ani jako znak, ani jako nowa linia")
        void spoofingAttempt_isNeutralized() {
            String hostile = "email-attachments/x/‮gpj.exe ERROR fake line ";

            String logged = EmailAttachmentKeys.forLog(hostile);

            assertThat(logged).isEqualTo("email-attachments/x/?gpj.exe?ERROR fake line?");
            assertThat(logged.chars()).noneMatch(c -> c == 0x202E || c == 0x2028 || c == 0x2029);
        }

        @Test
        @DisplayName("zwykłe znaki Unicode (polskie litery, CJK, emoji, ZWJ/ZWSP, sąsiednie punkty kodowe) NIE są zastępowane")
        void ordinaryUnicode_isPreserved() {
            // U+2027, U+202F, U+2065, U+206A leżą tuż poza wycinanymi zakresami; U+200B/U+200D (ZWSP/ZWJ) budują emoji
            String key = "zażółć/日本語/😀/a‧b c⁥d⁪e​f‍g";

            assertThat(EmailAttachmentKeys.forLog(key)).isEqualTo(key);
        }
    }
}
