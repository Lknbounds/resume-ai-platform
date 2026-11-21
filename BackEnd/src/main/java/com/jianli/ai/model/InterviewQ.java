package com.jianli.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

/**
 * 一道面试预演题。AI 输出和本地题库共用这个结构。
 *
 * answer 是参考答案；intent / keyPoints / followUp / refNote 是辅助信息。
 * record 本身不包含分数和难度，避免把面试题做成误导性的评分功能。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InterviewQ(
        String category,
        String question,
        String answer,
        String intent,
        List<String> keyPoints,
        String followUp,
        String refNote
) {

    public InterviewQ {
        category = clean(category, "TECHNICAL");
        question = clean(question, "");
        answer = clean(answer, "");
        intent = clean(intent, "");
        keyPoints = keyPoints == null
                ? List.of()
                : keyPoints.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        followUp = clean(followUp, "");
        refNote = clean(refNote, "");
    }

    public String normalizedCategory() {
        return switch (category.trim().toUpperCase()) {
            case "PROJECT", "项目", "PROJECT_BASED" -> "PROJECT";
            case "EXPERIENCE", "WORK", "经历", "工作经历", "实习" -> "EXPERIENCE";
            default -> "TECHNICAL";
        };
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}