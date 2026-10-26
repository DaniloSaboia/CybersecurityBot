package com.sentinel.kms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sentinel KMS backend — SecOps knowledge management with a RAG assistant and a
 * document ingestion pipeline backed by Supabase/pgvector.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class KmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(KmsApplication.class, args);
    }
}
