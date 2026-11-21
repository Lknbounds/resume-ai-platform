package com.jianli.interview.controller;

import com.jianli.common.ApiResponse;
import com.jianli.interview.dto.InterviewQuestionsVO;
import com.jianli.interview.service.InterviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 面试预演题接口。当前只提供本地题库，不涉及外部 AI 调用。
 */
@RestController
@RequestMapping("/api/interview")
@Tag(name = "面试预演")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;

    @GetMapping("/questions")
    @Operation(summary = "获取 5 道面试题", description = "返回题目、参考答案和辅助信息")
    public ApiResponse<InterviewQuestionsVO> questions() {
        return ApiResponse.ok(interviewService.getPracticeQuestions());
    }
}