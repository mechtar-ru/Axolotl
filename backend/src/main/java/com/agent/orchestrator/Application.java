package com.agent.orchestrator;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
@EnableScheduling
public class Application {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        loadEnvFromParents();
        
        SpringApplication.run(Application.class, args);
        log.info("\n" +
            "╔══════════════════════════════════════╗\n" +
            "║   Axolotl Orchestrator запущен!     ║\n" +
            "║   http://localhost:8082/api/agents   ║\n" +
            "╚══════════════════════════════════════╝");
    }
    
    private static void loadEnvFromParents() {
        Path current = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            Path envPath = current.resolve(".env");
            if (envPath.toFile().exists()) {
                try {
                    Dotenv dotenv = Dotenv.configure()
                            .ignoreIfMissing()
                            .directory(envPath.getParent().toString())
                            .load();
                    dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
                    log.info("Loaded .env from: {}", envPath.getParent());
                    return;
                } catch (Exception e) {
                    log.debug("Failed to load .env from {}: {}", envPath, e.getMessage());
                }
            }
            current = current.getParent();
        }
        log.info("No .env file found, using system environment variables");
    }
}
