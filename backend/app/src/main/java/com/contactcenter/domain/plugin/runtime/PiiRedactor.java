package com.contactcenter.domain.plugin.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redaguje pola uznane za PII w payloadzie żądania pluginu przed zapisem do
 * {@code plugin_invocation_log.request_payload_redacted} (ARCHITECTURE.md §11.12, BE-105).
 *
 * <p>Payloady SDK ({@code ContactEvent}/{@code CustomerSyncRequest}/{@code DispositionEvent})
 * niosą tylko identyfikatory (UUID), kody i znaczniki czasu — nie zawierają PII na poziomie
 * własnych pól. Realne ryzyko PII leży w {@code ManualActionRequest.parameters()} — dowolna
 * {@code Map<String,Object>} dostarczona przez UI desktopu agenta, która może zawierać np.
 * numer telefonu klienta wpisany jako parametr akcji. Stąd redakcja jest rekurencyjna i
 * przeszukuje całą strukturę (mapy, listy, mapy zagnieżdżone w listach), nie tylko top-level.
 *
 * <p>Brak istniejącego mechanizmu redakcji PII gdzie indziej w projekcie (zweryfikowano —
 * audit_log przechowuje surowe {@code old_value}/{@code new_value}, bo dostęp do {@code
 * audit_log} jest ograniczony do ADMIN, inny model ryzyka) — ta klasa jest pierwszym takim
 * mechanizmem, nowa lista pól PII żyje wyłącznie tutaj.
 */
final class PiiRedactor {

    static final String REDACTED_PLACEHOLDER = "[REDACTED]";

    /**
     * Nazwy kluczy uznawane za PII, porównywane case-insensitive po normalizacji (usunięcie
     * {@code _}/{@code -}, lower-case) — żeby {@code phone_number}, {@code phoneNumber} i
     * {@code PhoneNumber} trafiały w tę samą regułę niezależnie od konwencji nazewniczej
     * dostawcy payloadu.
     */
    private static final Set<String> PII_KEYS = Set.of(
            "phonenumber", "phone", "msisdn",
            "email", "emailaddress",
            "firstname", "lastname", "fullname", "customername", "name",
            "address", "street", "city", "postalcode", "zipcode",
            "pesel", "nip", "ssn",
            "cardnumber", "creditcardnumber", "iban");

    private PiiRedactor() {
    }

    /**
     * Redaguje rekurencyjnie wartości kluczy z {@link #PII_KEYS} w {@code payload}, zachowując
     * strukturę (klucze i typy kontenerów) dla debugowania.
     *
     * @param payload payload jako {@code Map<String,Object>}/{@code List<Object>} (wynik
     *                 {@code ObjectMapper.convertValue}) lub {@code null}
     * @return nowa struktura z wartościami PII zamienionymi na {@value #REDACTED_PLACEHOLDER},
     *         lub {@code null} gdy {@code payload} był {@code null}
     */
    static Object redact(Object payload) {
        if (payload == null) {
            return null;
        }
        if (payload instanceof Map<?, ?> map) {
            return redactMap(map);
        }
        if (payload instanceof List<?> list) {
            return redactList(list);
        }
        return payload;
    }

    private static Map<String, Object> redactMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (isPiiKey(key)) {
                result.put(key, REDACTED_PLACEHOLDER);
            } else {
                result.put(key, redact(value));
            }
        }
        return result;
    }

    private static List<Object> redactList(List<?> source) {
        List<Object> result = new ArrayList<>(source.size());
        for (Object element : source) {
            result.add(redact(element));
        }
        return result;
    }

    private static boolean isPiiKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase()
                .replace("_", "")
                .replace("-", "");
        return PII_KEYS.contains(normalized);
    }
}
