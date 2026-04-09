package com.agent.orchestrator.model;

public class Agent {
    private String id;
    private String name;
    private String emoji;
    private ConnectionInfo connection;
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getEmoji() { return emoji; }
    public void setEmoji(String emoji) { this.emoji = emoji; }
    
    public ConnectionInfo getConnection() { return connection; }
    public void setConnection(ConnectionInfo connection) { this.connection = connection; }
    
    public static class ConnectionInfo {
        private String type;
        private String url;
        private String apiKey;
        private Integer timeout;
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        
        public Integer getTimeout() { return timeout; }
        public void setTimeout(Integer timeout) { this.timeout = timeout; }
    }
}
