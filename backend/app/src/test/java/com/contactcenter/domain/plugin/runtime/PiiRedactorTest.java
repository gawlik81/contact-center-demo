package com.contactcenter.domain.plugin.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testy {@link PiiRedactor} — redakcja rekurencyjna pól PII przed zapisem do
 * {@code plugin_invocation_log.request_payload_redacted} (BE-105, kryterium akceptacji:
 * "request_payload_redacted nie zawiera PII surowego klienta").
 */
@DisplayName("PiiRedactor – redakcja PII w payloadach pluginu")
class PiiRedactorTest {

    @Nested
    @DisplayName("null / typy proste")
    class NullAndScalarTests {

        @Test
        @DisplayName("null payload -> null wynik")
        void nullPayload_returnsNull() {
            assertThat(PiiRedactor.redact(null)).isNull();
        }

        @Test
        @DisplayName("String/Number/Boolean (nie Map/List) -> zwracane bez zmian")
        void scalarValue_returnedUnchanged() {
            assertThat(PiiRedactor.redact("plain string")).isEqualTo("plain string");
            assertThat(PiiRedactor.redact(42)).isEqualTo(42);
            assertThat(PiiRedactor.redact(true)).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("redakcja top-level")
    class TopLevelRedactionTests {

        @Test
        @DisplayName("phoneNumber jest redagowany")
        void phoneNumber_isRedacted() {
            Map<String, Object> payload = Map.of("phoneNumber", "+48123456789", "note", "test");

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) PiiRedactor.redact(payload);

            assertThat(result.get("phoneNumber")).isEqualTo(PiiRedactor.REDACTED_PLACEHOLDER);
            assertThat(result.get("note")).isEqualTo("test");
        }

        @Test
        @DisplayName("email jest redagowany")
        void email_isRedacted() {
            Map<String, Object> payload = Map.of("email", "jan.kowalski@example.com", "actionId", "open-ticket");

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) PiiRedactor.redact(payload);

            assertThat(result.get("email")).isEqualTo(PiiRedactor.REDACTED_PLACEHOLDER);
            assertThat(result.get("actionId")).isEqualTo("open-ticket");
        }

        @Test
        @DisplayName("firstName/lastName/address są redagowane, klucze niebędące PII pozostają niezmienione")
        void multiplePiiKeys_allRedacted_nonPiiKeysUnchanged() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("firstName", "Jan");
            payload.put("lastName", "Kowalski");
            payload.put("address", "ul. Testowa 1");
            payload.put("contactId", "11111111-1111-1111-1111-111111111111");
            payload.put("dispositionCode", "RESOLVED");

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) PiiRedactor.redact(payload);

            assertThat(result.get("firstName")).isEqualTo(PiiRedactor.REDACTED_PLACEHOLDER);
            assertThat(result.get("lastName")).isEqualTo(PiiRedactor.REDACTED_PLACEHOLDER);
            assertThat(result.get("address")).isEqualTo(PiiRedactor.REDACTED_PLACEHOLDER);
            assertThat(result.get("contactId")).isEqualTo("11111111-1111-1111-1111-111111111111");
            assertThat(result.get("dispositionCode")).isEqualTo("RESOLVED");
        }

        @Test
        @DisplayName("normalizacja case-insensitive i _/- : 'phone_number'/'PhoneNumber'/'phone-number' wszystkie redagowane")
        void caseAndSeparatorInsensitiveMatching() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("phone_number", "111");
            payload.put("PhoneNumber", "222");
            payload.put("phone-number", "333");

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) PiiRedactor.redact(payload);

            assertThat(result.get("phone_number")).isEqualTo(PiiRedactor.REDACTED_PLACEHOLDER);
            assertThat(result.get("PhoneNumber")).isEqualTo(PiiRedactor.REDACTED_PLACEHOLDER);
            assertThat(result.get("phone-number")).isEqualTo(PiiRedactor.REDACTED_PLACEHOLDER);
        }
    }

    @Nested
    @DisplayName("redakcja rekurencyjna")
    class RecursiveRedactionTests {

        @Test
        @DisplayName("klucz PII zagnieżdżony w mapie wewnątrz mapy jest redagowany")
        void nestedMap_piiKeyRedacted() {
            Map<String, Object> inner = Map.of("phoneNumber", "+48000000000", "label", "primary");
            Map<String, Object> outer = Map.of("customer", inner, "reason", "CUSTOMER_UPDATED");

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) PiiRedactor.redact(outer);

            @SuppressWarnings("unchecked")
            Map<String, Object> resultInner = (Map<String, Object>) result.get("customer");
            assertThat(resultInner.get("phoneNumber")).isEqualTo(PiiRedactor.REDACTED_PLACEHOLDER);
            assertThat(resultInner.get("label")).isEqualTo("primary");
            assertThat(result.get("reason")).isEqualTo("CUSTOMER_UPDATED");
        }

        @Test
        @DisplayName("klucz PII zagnieżdżony w mapie wewnątrz listy jest redagowany")
        void mapInsideList_piiKeyRedacted() {
            Map<String, Object> item1 = Map.of("email", "a@example.com");
            Map<String, Object> item2 = Map.of("note", "no pii here");
            Map<String, Object> payload = Map.of("contacts", List.of(item1, item2));

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) PiiRedactor.redact(payload);

            @SuppressWarnings("unchecked")
            List<Object> contacts = (List<Object>) result.get("contacts");
            @SuppressWarnings("unchecked")
            Map<String, Object> redactedItem1 = (Map<String, Object>) contacts.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> unchangedItem2 = (Map<String, Object>) contacts.get(1);

            assertThat(redactedItem1.get("email")).isEqualTo(PiiRedactor.REDACTED_PLACEHOLDER);
            assertThat(unchangedItem2.get("note")).isEqualTo("no pii here");
        }
    }

    @Nested
    @DisplayName("struktury puste")
    class EmptyStructureTests {

        @Test
        @DisplayName("Map pusta -> Map pusta")
        void emptyMap_returnsEmptyMap() {
            Object result = PiiRedactor.redact(Map.of());

            assertThat(result).isInstanceOf(Map.class);
            assertThat((Map<?, ?>) result).isEmpty();
        }

        @Test
        @DisplayName("List pusta -> List pusta")
        void emptyList_returnsEmptyList() {
            Object result = PiiRedactor.redact(List.of());

            assertThat(result).isInstanceOf(List.class);
            assertThat((List<?>) result).isEmpty();
        }
    }
}
