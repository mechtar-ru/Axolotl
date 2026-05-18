package com.agent.orchestrator.graph.config;

import org.neo4j.driver.Config;
import org.neo4j.driver.NotificationConfig;
import org.neo4j.driver.NotificationCategory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;

import java.util.Set;

@Configuration
@EnableNeo4jRepositories(basePackages = "com.agent.orchestrator.graph.repository")
public class Neo4jConfig {

    /**
     * Suppress deprecated `id()` function notifications.
     * Spring Data Neo4j 6.3.x still generates `id()` in auto-queries;
     * this is cosmetic and the fix is to tell the driver not to log it.
     */
    @Bean
    Config neo4jDriverConfig() {
        return Config.builder()
                .withNotificationConfig(NotificationConfig.defaultConfig()
                        .disableCategories(Set.of(NotificationCategory.DEPRECATION)))
                .build();
    }
}