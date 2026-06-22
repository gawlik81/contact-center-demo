package com.contactcenter.api.plugin.dto;

import com.contactcenter.pluginsdk.model.ManualActionResult;

import java.util.Map;

/**
 * Response {@code POST /api/agent/plugins/{installationId}/manual-action/{actionId}}
 * (EPIC-28, BE-103).
 *
 * <p>Pole {@code error} (w przeciwieństwie do {@code ManualActionResult.message()}) jest
 * ustawiane wyłącznie dla odpowiedzi HTTP 504 — body 504 jest wciąż JSON zgodny z tym
 * rekordem (nigdy domyślną stroną błędu Spring), zgodnie z kryteriami akceptacji ticketu.
 * Dla 200 to pole jest zawsze {@code null}, a {@code message} niesie ewentualny komunikat
 * pluginu (sukces lub `unsupported`/błąd zwrócony przez sam plugin, nie przez timeout hosta).
 *
 * @param success    {@code ManualActionResult#success()} — {@code false} dla 504
 * @param resultData {@code ManualActionResult#resultData()} — pusta mapa dla 504
 * @param message    {@code ManualActionResult#message()} — {@code null} przy 504 (patrz {@code error})
 * @param error      ustawiane dla HTTP 504/403: opis przekroczenia budżetu czasowego pluginu lub
 *                    powodu odmowy wywołania (BE-107: instalacja disabled/REVOKED)
 */
public record ManualActionResponseDto(
        boolean success,
        Map<String, Object> resultData,
        String message,
        String error) {

    public static ManualActionResponseDto from(ManualActionResult result) {
        return new ManualActionResponseDto(result.success(), result.resultData(), result.message(), null);
    }

    public static ManualActionResponseDto timeout(long timeoutMs) {
        return new ManualActionResponseDto(
                false,
                Map.of(),
                null,
                "Plugin nie odpowiedział w przewidzianym czasie (" + timeoutMs + "ms)");
    }

    /**
     * Body HTTP 403 (BE-107) — instalacja {@code enabled=false}, {@code health_status=DISABLED_BY_ADMIN},
     * lub wersja pluginu {@code REVOKED}. Zwracane jako ciało JSON, nigdy domyślna strona błędu
     * Spring (analogicznie do {@link #timeout}).
     */
    public static ManualActionResponseDto forbidden(String reason) {
        return new ManualActionResponseDto(false, Map.of(), null, reason);
    }
}
