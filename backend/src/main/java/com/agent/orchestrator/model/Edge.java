// model/Edge.java
package com.agent.orchestrator.model;

import lombok.Data;

@Data
public class Edge {
    private String id;
    private String source;
    private String sourcePort;
    private String target;
    private String targetPort;
    private String type;  // "data", "control", "condition"
    private EdgeCondition condition;

    @Data
    public static class EdgeCondition {
        private String field;
        private String operator;  // "equals", "contains", "greater", "less"
        private Object value;
    }
}
