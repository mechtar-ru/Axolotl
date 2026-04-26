package com.agent.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskStatus {
    TODO,
    IN_PROGRESS,
    DONE,
    COMPLETED,
    BLOCKED;

    @JsonValue
    public String getValue() {
        return name();
    }

    @JsonCreator
    public static TaskStatus fromValue(String value) {
        if (value == null) return TODO;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TODO;
        }
    }
}