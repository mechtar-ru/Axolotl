// model/Edge.java
package com.agent.orchestrator.model;

public class Edge {
    private String id;
    private String source;
    private String sourcePort;
    private String target;
    private String targetPort;
    private String type;  // "data", "control", "condition"
    private EdgeCondition condition;
    
    // Геттеры и сеттеры
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    
    public String getSourcePort() { return sourcePort; }
    public void setSourcePort(String sourcePort) { this.sourcePort = sourcePort; }
    
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    
    public String getTargetPort() { return targetPort; }
    public void setTargetPort(String targetPort) { this.targetPort = targetPort; }
    
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    
    public EdgeCondition getCondition() { return condition; }
    public void setCondition(EdgeCondition condition) { this.condition = condition; }
    
    public static class EdgeCondition {
        private String field;
        private String operator;  // "equals", "contains", "greater", "less"
        private Object value;
        
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator; }
        public Object getValue() { return value; }
        public void setValue(Object value) { this.value = value; }
    }
}