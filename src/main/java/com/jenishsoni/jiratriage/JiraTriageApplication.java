package com.jenishsoni.jiratriage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenishsoni.jiratriage.duplicate.DuplicateSettings;
import com.jenishsoni.jiratriage.duplicate.LshDuplicateIndex;
import com.jenishsoni.jiratriage.repository.JiraGateway;
import com.jenishsoni.jiratriage.repository.FixtureJiraGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class JiraTriageApplication {

    private static final Logger LOG = LoggerFactory.getLogger(JiraTriageApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(JiraTriageApplication.class, args);
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    DuplicateSettings duplicateSettings(
            @Value("${triage.duplicates.shingle-size}") int shingleSize,
            @Value("${triage.duplicates.signature-size}") int signatureSize,
            @Value("${triage.duplicates.bands}") int bands,
            @Value("${triage.duplicates.seed}") long seed,
            @Value("${triage.duplicates.minimum-similarity}") double minimumSimilarity) {
        return new DuplicateSettings(shingleSize, signatureSize, bands, seed, minimumSimilarity);
    }

    @Bean
    LshDuplicateIndex duplicateIndex(DuplicateSettings settings) {
        return new LshDuplicateIndex(settings);
    }

    @Bean
    ApplicationRunner reportLoadedFixtures(JiraGateway gateway) {
        return args -> {
            if (gateway instanceof FixtureJiraGateway) {
                LOG.info("Loaded {} synthetic issues from local fixtures.", gateway.findAll().size());
            } else {
                LOG.info("Jira mock adapter configured; startup makes no HTTP requests.");
            }
        };
    }
}
