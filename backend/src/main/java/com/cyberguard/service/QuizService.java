package com.cyberguard.service;

import com.cyberguard.dto.*;
import com.cyberguard.model.QuizQuestion;
import com.cyberguard.model.QuizSession;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class QuizService {

    private List<QuizQuestion> quizQuestions = new ArrayList<>();
    private QuizSession currentSession;
    private final ObjectMapper objectMapper;

    public QuizService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            InputStream is = new ClassPathResource("data/quiz.json").getInputStream();
            quizQuestions = objectMapper.readValue(is, new TypeReference<List<QuizQuestion>>() {});
            log.info("Loaded {} quiz questions", quizQuestions.size());
        } catch (Exception e) {
            log.warn("Failed to load quiz.json, using defaults", e);
        }
    }

    public QuizStartResponse startQuiz(String category, String difficulty) {
        List<QuizQuestion> filtered = new ArrayList<>(quizQuestions);

        if (category != null && !category.isBlank()) {
            filtered = filtered.stream()
                    .filter(q -> q.getCategory().equalsIgnoreCase(category))
                    .collect(Collectors.toList());
        }
        if (difficulty != null && !difficulty.isBlank()) {
            filtered = filtered.stream()
                    .filter(q -> q.getDifficulty().equalsIgnoreCase(difficulty))
                    .collect(Collectors.toList());
        }

        Collections.shuffle(filtered);
        List<QuizQuestion> selected = filtered.stream().limit(10).collect(Collectors.toList());

        currentSession = new QuizSession();
        currentSession.setQuestions(selected);
        currentSession.setCurrentIndex(0);
        currentSession.setScore(0);
        currentSession.setStartTime(Instant.now());
        currentSession.setCategory(category);

        QuizQuestion first = selected.isEmpty() ? null : selected.get(0);
        return new QuizStartResponse(
                selected.size(),
                first != null ? toDto(first) : null,
                1
        );
    }

    public QuizAnswerResponse submitAnswer(int answerIndex) {
        if (currentSession == null) throw new IllegalStateException("No active quiz session");

        QuizQuestion current = currentSession.getQuestions().get(currentSession.getCurrentIndex());
        boolean correct = answerIndex == current.getCorrectAnswer();

        if (correct) currentSession.setScore(currentSession.getScore() + 1);

        currentSession.getAnswers().add(new QuizSession.QuizAnswer(current.getId(), answerIndex, correct));
        currentSession.setCurrentIndex(currentSession.getCurrentIndex() + 1);

        boolean isComplete = currentSession.getCurrentIndex() >= currentSession.getQuestions().size();
        QuizQuestion next = isComplete ? null : currentSession.getQuestions().get(currentSession.getCurrentIndex());

        return new QuizAnswerResponse(correct, current.getExplanation(), isComplete, next != null ? toDto(next) : null);
    }

    public QuizResultsResponse getResults() {
        if (currentSession == null) return null;

        Duration d = Duration.between(currentSession.getStartTime(), Instant.now());
        long mins = d.toMinutes();
        long secs = d.toSecondsPart();

        List<QuizResultsResponse.BreakdownItem> breakdown = new ArrayList<>();
        for (int i = 0; i < currentSession.getAnswers().size(); i++) {
            QuizSession.QuizAnswer ans = currentSession.getAnswers().get(i);
            QuizQuestion q = currentSession.getQuestions().get(i);
            breakdown.add(new QuizResultsResponse.BreakdownItem(
                    q.getQuestion(),
                    q.getOptions().get(ans.getSelectedAnswer()),
                    q.getOptions().get(q.getCorrectAnswer()),
                    ans.isCorrect(),
                    q.getExplanation()
            ));
        }

        int total = currentSession.getQuestions().size();
        int score = currentSession.getScore();
        return new QuizResultsResponse(
                score, total,
                total > 0 ? Math.round((float) score / total * 100) : 0,
                mins + "m " + secs + "s",
                breakdown
        );
    }

    public void endQuiz() {
        currentSession = null;
    }

    public List<String> getCategories() {
        return quizQuestions.stream()
                .map(QuizQuestion::getCategory)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private QuizStartResponse.QuestionDto toDto(QuizQuestion q) {
        return new QuizStartResponse.QuestionDto(q.getQuestion(), q.getOptions(), q.getCategory(), q.getDifficulty());
    }
}
