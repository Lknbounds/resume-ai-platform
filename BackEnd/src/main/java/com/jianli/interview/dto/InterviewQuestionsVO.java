package com.jianli.interview.dto;

import java.util.List;

/**
 * 面试预演题响应。当前固定返回 5 道本地题目，不调用外部 AI。
 */
public record InterviewQuestionsVO(
        String source,
        String mode,
        int count,
        List<InterviewQuestionVO> questions
) {
}