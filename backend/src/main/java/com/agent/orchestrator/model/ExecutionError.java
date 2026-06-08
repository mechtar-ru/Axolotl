package com.agent.orchestrator.model;

import lombok.Data;

/**
 * Structured error for user-facing error messages.
 * Replaces raw e.getMessage() propagation with categorized errors.
 */
@Data
public class ExecutionError {

    public enum ErrorType {
        CONFIG_ERROR,
        PROVIDER_ERROR,
        NETWORK_ERROR,
        VALIDATION_ERROR,
        INTERNAL_ERROR
    }

    private final String type;
    private final String message;
    private final String detail;

    public ExecutionError(ErrorType type, String message, String detail) {
        this.type = type.name();
        this.message = message;
        this.detail = detail != null ? detail : "";
    }

    public ExecutionError(ErrorType type, String message) {
        this(type, message, null);
    }

    public static ExecutionError config(String message, String detail) {
        return new ExecutionError(ErrorType.CONFIG_ERROR, message, detail);
    }

    public static ExecutionError provider(String message, String detail) {
        return new ExecutionError(ErrorType.PROVIDER_ERROR, message, detail);
    }

    public static ExecutionError network(String message, String detail) {
        return new ExecutionError(ErrorType.NETWORK_ERROR, message, detail);
    }

    public static ExecutionError internal(String message, String detail) {
        return new ExecutionError(ErrorType.INTERNAL_ERROR, message, detail);
    }

    public static ExecutionError fromException(Exception e, String fallbackMessage) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = fallbackMessage;
        }
        // Classify by exception type
        if (e instanceof java.net.ConnectException
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.UnknownHostException) {
            return new ExecutionError(ErrorType.NETWORK_ERROR, msg, e.getClass().getSimpleName());
        }
        if (e instanceof org.springframework.web.server.ResponseStatusException) {
            return new ExecutionError(ErrorType.CONFIG_ERROR, msg, "HTTP " + ((org.springframework.web.server.ResponseStatusException) e).getStatusCode());
        }
        return new ExecutionError(ErrorType.INTERNAL_ERROR, msg, e.getClass().getSimpleName());
    }
}
