package com.jianli.interview.dto;

import com.jianli.ai.model.InterviewQ;

import java.util.List;

/**
 * 面试题接口的单题响应。sortNo 从 1 开始，方便前端稳定编号。
 */
public record InterviewQuestionVO(
        int sortNo,
        String category,
        String question,
        String answer,
        String intent,
        List<String> keyPoints,
        String followUp,
        String refNote
) {
    public static InterviewQuestionVO of(InterviewQ question, int sortNo) {
        return new InterviewQuestionVO(
                sortNo,
                question.normalizedCategory(),
                question.question(),
                question.answer(),
                question.intent(),
                question.keyPoints(),
                question.followUp(),
                question.refNote()
        );
    }
}