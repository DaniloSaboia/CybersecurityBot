package com.sentinel.kms.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the Swagger UI (served at /swagger-ui.html).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI sentinelKmsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Sentinel KMS API")
                        .description("SecOps knowledge management — RAG assistant, knowledge base, ingestion pipeline.")
                        .version("0.1.0"));
    }
}
