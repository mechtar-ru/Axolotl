package com.agent.orchestrator.model;

import lombok.Data;

@Data
public class Agent {
    private String id;
    private String name;
    private String emoji;
    private ConnectionInfo connection;
    
    @Data
    public static class ConnectionInfo {
        private String type;
        private String url;
        private String apiKey;
        private Integer timeout;
    }
}
