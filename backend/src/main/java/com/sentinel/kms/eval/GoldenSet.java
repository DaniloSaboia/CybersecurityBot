package com.sentinel.kms.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * Loads the golden set ({@code classpath:eval/goldenset.json}) once at startup.
 * Cases are static expectations over the seeded knowledge base, so they travel
 * with the app and give every environment the same measuring stick.
 */
@Component
public class GoldenSet {

    private final List<EvalCase> cases;

    public GoldenSet(@Value("classpath:eval/goldenset.json") Resource resource) {
        this.cases = load(resource);
    }

    public List<EvalCase> cases() {
        return cases;
    }

    private static List<EvalCase> load(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            List<EvalCase> parsed = new ObjectMapper().readValue(in, new TypeReference<>() {
            });
            if (parsed == null || parsed.isEmpty()) {
                throw new IllegalStateException("golden set is empty");
            }
            return List.copyOf(parsed);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load eval/goldenset.json: " + e.getMessage(), e);
        }
    }
}
