package com.agent.orchestrator.model;

import java.util.ArrayList;
import java.util.List;

public class SchemaValidationResult {

    private final List<ValidationIssue> errors = new ArrayList<>();
    private final List<ValidationIssue> warnings = new ArrayList<>();

    public boolean isValid() {
        return errors.isEmpty();
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    public List<ValidationIssue> getErrors() {
        return errors;
    }

    public List<ValidationIssue> getWarnings() {
        return warnings;
    }

    public void addError(String field, String message) {
        errors.add(new ValidationIssue(field, message, null));
    }

    public void addError(String field, String message, String nodeId) {
        errors.add(new ValidationIssue(field, message, nodeId));
    }

    public void addWarning(String field, String message) {
        warnings.add(new ValidationIssue(field, message, null));
    }

    public void addWarning(String field, String message, String nodeId) {
        warnings.add(new ValidationIssue(field, message, nodeId));
    }

    public static class ValidationIssue {
        private final String field;
        private final String message;
        private final String nodeId;

        ValidationIssue(String field, String message, String nodeId) {
            this.field = field;
            this.message = message;
            this.nodeId = nodeId;
        }

        public String getField() { return field; }
        public String getMessage() { return message; }
        public String getNodeId() { return nodeId; }
    }
}
