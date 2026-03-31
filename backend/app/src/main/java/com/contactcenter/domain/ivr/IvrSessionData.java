package com.contactcenter.domain.ivr;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dane sesji IVR przechowywane w Redis.
 *
 * <p>Klucz Redis: {@code ivr:session:{callId}} z TTL 30 minut.
 * Sesja jest tworzona przy odebraniu połączenia i usuwana po zakończeniu
 * przepływu IVR (HANGUP lub QUEUE_TRANSFER).
 *
 * <p>Pola COLLECT_DTMF:
 * <ul>
 *   <li>{@code collectingDtmfForNodeId} – gdy != null, silnik oczekuje na cyfry dla tego węzła</li>
 *   <li>{@code dtmfBuffer} – akumulowane cyfry w trybie COLLECT_DTMF</li>
 *   <li>{@code variables} – słownik zebranych zmiennych IVR (np. {@code {"account_number": "12345"}})</li>
 * </ul>
 */
public class IvrSessionData {

    @JsonProperty("call_id")
    private String callId;

    @JsonProperty("ivr_id")
    private UUID ivrId;

    @JsonProperty("current_node_id")
    private String currentNodeId;

    @JsonProperty("tenant_id")
    private UUID tenantId;

    @JsonProperty("retry_count")
    private int retryCount;

    /** Gdy != null, silnik jest w trybie zbierania cyfr dla węzła COLLECT_DTMF. */
    @JsonProperty("collecting_dtmf_for_node_id")
    private String collectingDtmfForNodeId;

    /** Bufor akumulujący cyfry w trybie COLLECT_DTMF. Persystowany jako String (JSON-serializable). */
    @JsonProperty("dtmf_buffer")
    private String dtmfBuffer;

    /** Zmienne zebrane przez węzły COLLECT_DTMF, przekazywane dalej do routingu. */
    @JsonProperty("variables")
    private Map<String, String> variables;

    /**
     * UUID rekordu contact w DB – ustawiane przy starcie sesji IVR z webhookiem Twilio.
     * Null w trybie MockTelephonyAdapter (brak rekordu contact w DB).
     */
    @JsonProperty("contact_id")
    private UUID contactId;

    /**
     * Gdy true, sesja działa w trybie TwiML (Twilio webhook) – wewnętrzny timer DTMF nie jest
     * planowany; Twilio zarządza timeoutem przez {@code <Gather timeout="N">}.
     */
    @JsonProperty("twiml_mode")
    private boolean twimlMode;

    // Domyślny konstruktor dla Jackson
    public IvrSessionData() {}

    public IvrSessionData(String callId, UUID ivrId, String currentNodeId, UUID tenantId) {
        this.callId = callId;
        this.ivrId = ivrId;
        this.currentNodeId = currentNodeId;
        this.tenantId = tenantId;
        this.retryCount = 0;
        this.dtmfBuffer = "";
        this.variables = new HashMap<>();
    }

    // =========================================================================
    // Gettery / settery podstawowe
    // =========================================================================

    public String getCallId()        { return callId; }
    public UUID   getIvrId()         { return ivrId; }
    public String getCurrentNodeId() { return currentNodeId; }
    public UUID   getTenantId()      { return tenantId; }
    public int    getRetryCount()    { return retryCount; }

    public void setCallId(String callId)               { this.callId = callId; }
    public void setIvrId(UUID ivrId)                   { this.ivrId = ivrId; }
    public void setCurrentNodeId(String currentNodeId) { this.currentNodeId = currentNodeId; }
    public void setTenantId(UUID tenantId)             { this.tenantId = tenantId; }
    public void setRetryCount(int retryCount)          { this.retryCount = retryCount; }

    public UUID getContactId() { return contactId; }
    public void setContactId(UUID contactId) { this.contactId = contactId; }

    public boolean isTwimlMode() { return twimlMode; }
    public void setTwimlMode(boolean twimlMode) { this.twimlMode = twimlMode; }

    /** Inkrementuje licznik prób. */
    public void incrementRetryCount() {
        this.retryCount++;
    }

    // =========================================================================
    // Gettery / settery COLLECT_DTMF
    // =========================================================================

    public String getCollectingDtmfForNodeId() { return collectingDtmfForNodeId; }
    public void   setCollectingDtmfForNodeId(String nodeId) { this.collectingDtmfForNodeId = nodeId; }

    public String getDtmfBuffer() {
        return dtmfBuffer != null ? dtmfBuffer : "";
    }

    public void setDtmfBuffer(String dtmfBuffer) {
        this.dtmfBuffer = dtmfBuffer != null ? dtmfBuffer : "";
    }

    /** Dołącza cyfrę do bufora DTMF. */
    public void appendDtmfDigit(String digit) {
        if (this.dtmfBuffer == null) this.dtmfBuffer = "";
        this.dtmfBuffer += digit;
    }

    /** Czyści bufor DTMF i pole collectingDtmfForNodeId (koniec zbierania). */
    public void clearDtmfCollection() {
        this.dtmfBuffer = "";
        this.collectingDtmfForNodeId = null;
    }

    // =========================================================================
    // Zmienne IVR
    // =========================================================================

    /**
     * Zwraca null-safe kopię mapy zmiennych IVR.
     * Nigdy nie zwraca null – pusta mapa gdy brak zmiennych.
     */
    public Map<String, String> getVariables() {
        if (variables == null) variables = new HashMap<>();
        return variables;
    }

    public void setVariables(Map<String, String> variables) {
        this.variables = variables != null ? variables : new HashMap<>();
    }

    /**
     * Ustawia wartość zmiennej IVR pod podaną nazwą.
     *
     * @param name  nazwa zmiennej (np. {@code "account_number"})
     * @param value wartość do zapisania
     */
    public void setVariable(String name, String value) {
        if (variables == null) variables = new HashMap<>();
        variables.put(name, value);
    }

    /**
     * Pobiera wartość zmiennej IVR po nazwie.
     *
     * @param name nazwa zmiennej
     * @return wartość lub null gdy nie istnieje
     */
    public String getVariable(String name) {
        if (variables == null) return null;
        return variables.get(name);
    }
}
