package com.agent.orchestrator.graph.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;

@Configuration
@EnableNeo4jRepositories(basePackages = "com.agent.orchestrator.graph.repository")
public class Neo4jConfig {
    // Spring Boot auto-configures Neo4j from application.yml
}