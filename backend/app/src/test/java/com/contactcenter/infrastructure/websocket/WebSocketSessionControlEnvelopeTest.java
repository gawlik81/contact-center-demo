package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.SessionCloseReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WebSocketSessionControlEnvelope: zgodnosc wsteczna formatu (stary format, nieznany typ/powod, nieznane pola)")
class WebSocketSessionControlEnvelopeTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("stary format (BE-233: bez sid i sig) deserializuje sie: sid=null, sig=null, koperta poprawna")
    void oldFormatWithoutSidAndSigDeserializes() throws Exception {
        String old = "{\"v\":1,\"src\":\"old-inst\",\"type\":\"CLOSE_USER\",\"tenantId\":\"" + TENANT + "\",\"userId\":\""
                + USER + "\",\"reason\":\"LOGOUT\",\"ts\":1700000000000}";

        WebSocketSessionControlEnvelope e = WebSocketSessionControlEnvelope.fromJson(bytes(old));

        assertThat(e.sid()).isNull();
        assertThat(e.sig()).isNull();
        assertThat(e.type()).isEqualTo(WebSocketSessionControlEnvelope.Type.CLOSE_USER);
        assertThat(e.isWellFormed()).isTrue();
    }

    @Test
    @DisplayName("nieznane pola sa ignorowane (nowsza wersja nadawcy), nieznany TYP albo POWOD = wyjatek (odbiorca liczy 'malformed')")
    void unknownFieldsIgnoredButUnknownEnumValuesFail() throws Exception {
        String extra = "{\"v\":1,\"src\":\"x\",\"type\":\"CLOSE_TENANT\",\"tenantId\":\"" + TENANT
                + "\",\"reason\":\"TENANT_DEACTIVATED\",\"ts\":1,\"futureField\":{\"a\":1}}";
        assertThat(WebSocketSessionControlEnvelope.fromJson(bytes(extra)).isWellFormed()).isTrue();

        String unknownType = "{\"v\":1,\"src\":\"x\",\"type\":\"CLOSE_EVERYTHING\",\"reason\":\"LOGOUT\",\"ts\":1}";
        assertThatThrownBy(() -> WebSocketSessionControlEnvelope.fromJson(bytes(unknownType)))
                .isInstanceOf(java.io.IOException.class);
        String unknownReason = "{\"v\":1,\"src\":\"x\",\"type\":\"CLOSE_USER\",\"userId\":\"" + USER
                + "\",\"reason\":\"FROM_THE_FUTURE\",\"ts\":1}";
        assertThatThrownBy(() -> WebSocketSessionControlEnvelope.fromJson(bytes(unknownReason)))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    @DisplayName("round trip nowego formatu zachowuje sid i sig; niepodpisana koperta nie niesie pola sig")
    void roundTripKeepsSidAndSig() throws Exception {
        UUID sid = UUID.randomUUID();
        WebSocketSessionControlEnvelope signed = new WebSocketSessionControlEnvelope(1, "inst",
                WebSocketSessionControlEnvelope.Type.CLOSE_SESSION, TENANT, USER, sid, SessionCloseReason.LOGOUT, 5L, "abc");

        WebSocketSessionControlEnvelope back = WebSocketSessionControlEnvelope.fromJson(bytes(signed.toJson()));

        assertThat(back).isEqualTo(signed);
        assertThat(signed.withSignature(null).toJson()).doesNotContain("\"sig\"");
    }
}
