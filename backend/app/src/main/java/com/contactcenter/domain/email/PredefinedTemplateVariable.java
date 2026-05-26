package com.contactcenter.domain.email;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public enum PredefinedTemplateVariable {
    CUSTOMER_FIRST_NAME("customerFirstName", "customer", "Imię klienta",              "Jan"),
    CUSTOMER_LAST_NAME ("customerLastName",  "customer", "Nazwisko klienta",           "Kowalski"),
    CUSTOMER_FULL_NAME ("customerFullName",  "customer", "Imię i nazwisko klienta",    "Jan Kowalski"),
    CUSTOMER_EMAIL     ("customerEmail",     "customer", "Adres email klienta",        "jan.kowalski@example.com"),
    CUSTOMER_PHONE     ("customerPhone",     "customer", "Numer telefonu klienta",     "+48 123 456 789"),
    AGENT_FIRST_NAME   ("agentFirstName",    "agent",    "Imię agenta",                "Anna"),
    AGENT_LAST_NAME    ("agentLastName",     "agent",    "Nazwisko agenta",            "Nowak"),
    AGENT_FULL_NAME    ("agentFullName",     "agent",    "Imię i nazwisko agenta",     "Anna Nowak"),
    AGENT_EMAIL        ("agentEmail",        "agent",    "Adres email agenta",         "anna.nowak@firma.pl");

    private final String key;
    private final String category;
    private final String labelPl;
    private final String exampleValue;

    PredefinedTemplateVariable(String key, String category, String labelPl, String exampleValue) {
        this.key = key;
        this.category = category;
        this.labelPl = labelPl;
        this.exampleValue = exampleValue;
    }

    public String getKey() { return key; }
    public String getCategory() { return category; }
    public String getLabelPl() { return labelPl; }
    public String getExampleValue() { return exampleValue; }

    public static final Map<String, PredefinedTemplateVariable> BY_KEY =
            Arrays.stream(values()).collect(Collectors.toMap(PredefinedTemplateVariable::getKey, v -> v));
}
