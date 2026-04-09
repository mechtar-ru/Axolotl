package com.agent.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app")
public class AppConfig {
    
    private AgentsConfig agents = new AgentsConfig();
    private ConnectionConfig connection = new ConnectionConfig();
    
    // Геттеры и сеттеры
    public AgentsConfig getAgents() { return agents; }
    public void setAgents(AgentsConfig agents) { this.agents = agents; }
    
    public ConnectionConfig getConnection() { return connection; }
    public void setConnection(ConnectionConfig connection) { this.connection = connection; }
    
    public static class AgentsConfig {
        private DefaultConfig defaultConfig = new DefaultConfig();
        
        public DefaultConfig getDefault() { return defaultConfig; }
        public void setDefault(DefaultConfig defaultConfig) { this.defaultConfig = defaultConfig; }
        
        public static class DefaultConfig {
            private int timeout = 30;
            private int retryCount = 3;
            
            public int getTimeout() { return timeout; }
            public void setTimeout(int timeout) { this.timeout = timeout; }
            public int getRetryCount() { return retryCount; }
            public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
        }
    }
    
    public static class ConnectionConfig {
        private EndpointConfig local = new EndpointConfig();
        private EndpointConfig remote = new EndpointConfig();
        
        public EndpointConfig getLocal() { return local; }
        public void setLocal(EndpointConfig local) { this.local = local; }
        public EndpointConfig getRemote() { return remote; }
        public void setRemote(EndpointConfig remote) { this.remote = remote; }
        
        public static class EndpointConfig {
            private String url;
            private String apiKey;
            
            public String getUrl() { return url; }
            public void setUrl(String url) { this.url = url; }
            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        }
    }
}
