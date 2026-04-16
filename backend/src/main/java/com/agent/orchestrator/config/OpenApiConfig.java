package com.agent.orchestrator.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Axolotl API")
                        .version("1.0.0")
                        .description("""
                            Axolotl - Visual AI-agent orchestration app.

                            ## Features
                            - **Workflow Management**: Create and manage workflow schemas
                            - **Node Types**: Source, Agent, Condition, Loop, Output, Memory, Guardrail, Human, Fallback, Subagent
                            - **Execution Modes**: EXECUTE, ANALYZE (read-only), DRY_RUN (simulate)
                            - **Remote API**: API key authentication for external integrations
                            - **Skills**: Auto-learning skill system for agents

                            ## Authentication
                            - UI: JWT token (login via `/api/auth/login`)
                            - Remote API: API key via `X-API-Key` header
                        """)
                        .contact(new Contact()
                                .name("Axolotl Team")
                                .url("https://github.com/axolotl")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Development"),
                        new Server().url("https://api.axolotl.app").description("Production")
                ));
    }
}
