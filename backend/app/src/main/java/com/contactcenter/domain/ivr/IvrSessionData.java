package com.contactcenter.domain.ivr;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Dane sesji IVR przechowywane w Redis.
 *
 * <p>Klucz Redis: {@code ivr:session:{callId}} z TTL 30 minut.
 * Sesja jest tworzona przy odebraniu połączenia i usuwana po zakończeniu
 * przepływu IVR (HANGUP lub QUEUE_TRANSFER).
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

    // Domyślny konstruktor dla Jackson
    public IvrSessionData() {}

    public IvrSessionData(String callId, UUID ivrId, String currentNodeId, UUID tenantId) {
        this.callId = callId;
        this.ivrId = ivrId;
        this.currentNodeId = currentNodeId;
        this.tenantId = tenantId;
        this.retryCount = 0;
    }

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

    /** Inkrementuje licznik prób. */
    public void incrementRetryCount() {
        this.retryCount++;
    }
}
