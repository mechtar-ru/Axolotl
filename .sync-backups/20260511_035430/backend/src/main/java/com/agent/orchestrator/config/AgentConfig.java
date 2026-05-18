package com.agent.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app")
public class AgentConfig {
    private List<AgentProperties> agents = new ArrayList<>();
    
    public List<AgentProperties> getAgents() { return agents; }
    public void setAgents(List<AgentProperties> agents) { this.agents = agents; }
    
    public static class AgentProperties {
        private String id;
        private String name;
        private String url;
        
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }
}
