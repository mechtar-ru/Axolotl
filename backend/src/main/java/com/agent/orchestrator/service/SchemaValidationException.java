package com.agent.orchestrator.service;

import com.agent.orchestrator.model.SchemaValidationResult;

/**
 * Thrown when schema validation fails before execution.
 * Carries the full ValidationResult for structured error responses.
 */
public class SchemaValidationException extends RuntimeException {

    private final SchemaValidationResult validationResult;

    public SchemaValidationException(SchemaValidationResult validationResult) {
        super("Schema validation failed with " + validationResult.getErrors().size() + " error(s)");
        this.validationResult = validationResult;
    }

    public SchemaValidationResult getValidationResult() {
        return validationResult;
    }
}
