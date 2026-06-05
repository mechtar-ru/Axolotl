package com.agent.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum PlanStepStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    REJECTED,
    INCOMPLETE;

    @JsonValue
    public String getValue() {
        return name();
    }

    @JsonCreator
    public static PlanStepStatus fromValue(String value) {
        if (value == null) return PENDING;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
