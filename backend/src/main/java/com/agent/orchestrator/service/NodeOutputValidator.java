package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates node outputs at each hop boundary.
 * Checks structural validity (non-null, non-blank) per node type.
 * Logs warnings but does not block execution — the failed validation
 * information is returned as part of the execution context for downstream
 * nodes to consume.
 */
@Component
public class NodeOutputValidator {

    private static final Logger log = LoggerFactory.getLogger(NodeOutputValidator.class);

    public static class ValidationResult {
        private final boolean valid;
        private final List<String> issues;

        ValidationResult(boolean valid, List<String> issues) {
            this.valid = valid;
            this.issues = issues;
        }

        public boolean isValid() { return valid; }
        public List<String> getIssues() { return issues; }
    }

    public ValidationResult validate(String nodeType, Map<String, Object> output, Node node) {
        List<String> issues = new ArrayList<>();

        if (output == null) {
            issues.add("Output is null");
            return new ValidationResult(false, issues);
        }

        Object result = output.get("result");
        if (result == null) {
            issues.add("Output missing 'result' key");
        }

        if (nodeType == null) {
            return new ValidationResult(issues.isEmpty(), issues);
        }

        switch (nodeType) {
            case "agent":
                validateAgentOutput(result, issues);
                break;
            case "verifier":
                validateVerifierOutput(result, issues);
                break;
            case "review":
                validateReviewOutput(result, issues);
                break;
            case "draft":
                validateDraftOutput(result, issues);
                break;
            case "source":
                validateSourceOutput(result, issues);
                break;
            case "output":
                // Output nodes accept any result — no strict type check
                break;
            default:
                break;
        }

        if (!issues.isEmpty()) {
            log.warn("NodeOutputValidator: {} issue(s) for nodeType={}, nodeId={}: {}",
                    issues.size(), nodeType, node != null ? node.getId() : "null", issues);
        }

        return new ValidationResult(issues.isEmpty(), issues);
    }

    private void validateAgentOutput(Object result, List<String> issues) {
        if (result == null) {
            issues.add("Agent output result is null");
        } else if (result instanceof String s && s.isBlank()) {
            issues.add("Agent output result is blank");
        }
    }

    private void validateVerifierOutput(Object result, List<String> issues) {
        if (result == null) {
            issues.add("Verifier output result is null");
            return;
        }
        if (result instanceof String text && text.startsWith("Error:")) {
            issues.add("Verifier output contains error message: " + text);
        }
    }

    private void validateReviewOutput(Object result, List<String> issues) {
        if (result == null) {
            issues.add("Review output result is null");
        }
    }

    private void validateDraftOutput(Object result, List<String> issues) {
        if (result == null) {
            issues.add("Draft output result is null");
        } else if (result instanceof String s && s.isBlank()) {
            issues.add("Draft output result is blank");
        }
    }

    private void validateSourceOutput(Object result, List<String> issues) {
        if (result == null) {
            issues.add("Source output result is null");
        } else if (result instanceof String s && s.isBlank()) {
            issues.add("Source output result is blank");
        }
    }
}
