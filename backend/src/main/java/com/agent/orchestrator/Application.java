package com.agent.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@SpringBootApplication
public class Application {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        log.info("\n" +
            "╔══════════════════════════════════════╗\n" +
            "║   Axolotl Orchestrator запущен!     ║\n" +
            "║   http://localhost:8080/api/agents   ║\n" +
            "╚══════════════════════════════════════╝\n");
    }
}
