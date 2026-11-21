package com.jianli.interview.service;

import com.jianli.ai.model.InterviewQ;
import com.jianli.ai.parser.TemplateInterviewGenerator;
import com.jianli.interview.dto.InterviewQuestionVO;
import com.jianli.interview.dto.InterviewQuestionsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 面试预演题服务。当前使用本地模板，固定返回 5 道题。
 *
 * 保留独立 Service 是为了以后接入 AI 时只替换生成来源，
 * Controller 和前端响应结构不需要改变。
 */
@Service
@RequiredArgsConstructor
public class InterviewService {

    private static final int QUESTION_COUNT = 5;
    private static final List<String> DEFAULT_SKILLS =
            List.of("Java", "Spring Boot", "MySQL", "Redis", "并发与性能");

    private final TemplateInterviewGenerator templateInterviewGenerator;

    public InterviewQuestionsVO getPracticeQuestions() {
        List<InterviewQ> generated = templateInterviewGenerator.generate(
                "Java 后端开发工程师",
                DEFAULT_SKILLS,
                List.of(),
                List.of(),
                QUESTION_COUNT
        );

        if (generated.size() != QUESTION_COUNT) {
            throw new IllegalStateException("面试题数量不正确：" + generated.size());
        }

        List<InterviewQuestionVO> questions = IntStream.range(0, generated.size())
                .mapToObj(index -> InterviewQuestionVO.of(generated.get(index), index + 1))
                .toList();

        return new InterviewQuestionsVO("TEMPLATE", "LOCAL", QUESTION_COUNT, questions);
    }
}