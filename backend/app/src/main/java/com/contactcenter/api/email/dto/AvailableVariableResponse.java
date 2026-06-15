package com.contactcenter.api.email.dto;

import com.contactcenter.domain.email.PredefinedTemplateVariable;

public record AvailableVariableResponse(String key, String category, String labelPl, String exampleValue) {

    public static AvailableVariableResponse from(PredefinedTemplateVariable v) {
        return new AvailableVariableResponse(v.getKey(), v.getCategory(), v.getLabelPl(), v.getExampleValue());
    }
}
