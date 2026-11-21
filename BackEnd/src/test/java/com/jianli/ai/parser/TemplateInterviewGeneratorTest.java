package com.jianli.ai.parser;

import com.jianli.ai.model.InterviewQ;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateInterviewGeneratorTest {

    private final TemplateInterviewGenerator generator = new TemplateInterviewGenerator();

    @Test
    void generatesExactlyFiveCompleteQuestions() {
        List<InterviewQ> questions = generator.generate(
                "Java 后端开发工程师",
                List.of("Java", "Spring Boot", "MySQL", "Redis", "并发与性能"),
                List.of(),
                List.of(),
                5
        );

        assertEquals(5, questions.size());
        assertTrue(questions.stream().allMatch(q -> !q.question().isBlank()));
        assertTrue(questions.stream().allMatch(q -> !q.answer().isBlank()));
        assertTrue(questions.stream().allMatch(q -> !q.intent().isBlank()));
        assertTrue(questions.stream().allMatch(q -> !q.keyPoints().isEmpty()));
    }

    @Test
    void handlesNullCollectionsAndNullKeyPoints() {
        List<InterviewQ> questions = generator.generate(
                null,
                null,
                null,
                null,
                5
        );

        assertEquals(5, questions.size());
        assertTrue(questions.stream().allMatch(q -> !q.answer().isBlank()));
        assertFalse(questions.stream().anyMatch(q -> q.keyPoints().contains(null)));
    }
}