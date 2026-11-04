package com.contactcenter.api.contact.dto;

/** Derived phase of an active contact on the agent desktop (BE-157). Serialized by name. */
public enum ContactPhase {
    /** Contact assigned by routing, waiting for the agent to answer (status ASSIGNED). */
    RINGING,
    /** Contact being handled (status ACTIVE or ON_HOLD). */
    IN_PROGRESS,
    /** After-call work: COMPLETED PHONE contact without disposition inside the wrap-up window. */
    WRAP_UP
}
