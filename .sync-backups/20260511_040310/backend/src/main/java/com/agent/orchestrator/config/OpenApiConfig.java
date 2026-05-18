package com.agent.orchestrator.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI axolotlOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Axolotl API")
                        .description("AI-agent orchestration API for building visual workflow graphs")
                        .version("0.1.3")
                        .contact(new Contact()
                                .name("Axolotl Team")
                                .url("https://github.com/anomalyco/axolotl"))
                        .license(new License()
                                .name("MIT")
                                .url("https://github.com/anomalyco/axolotl/blob/main/LICENSE")));
    }
}