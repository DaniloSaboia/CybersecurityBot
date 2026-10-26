package com.sentinel.kms.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/** Loads the versioned, explicitly non-expert answer-evaluation fixture set. */
@Component
public class AnswerGoldenSet {

    private final AnswerEvalDataset dataset;

    public AnswerGoldenSet(@Value("classpath:eval/answer-eval-v1.json") Resource resource) {
        this.dataset = load(resource);
    }

    public AnswerEvalDataset dataset() {
        return dataset;
    }

    private static AnswerEvalDataset load(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            AnswerEvalDataset parsed = new ObjectMapper().readValue(in, AnswerEvalDataset.class);
            if (parsed == null) {
                throw new IllegalStateException("answer evaluation dataset is empty");
            }
            return parsed;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load eval/answer-eval-v1.json: " + e.getMessage(), e);
        }
    }
}
