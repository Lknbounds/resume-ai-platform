package com.jianli.analysis.calculator;

import com.jianli.ai.model.JdParsed;
import com.jianli.ai.model.ResumeParsed;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ScoreCalculatorEducationTest {

    private final ScoreCalculator calculator = new ScoreCalculator(mock(SkillMatcher.class));

    @Test
    void structureScoreRecognizesEducationContentWithoutHeading() {
        ResumeParsed resume = resume("NONE", "北京大学", "计算机科学与技术",
                List.of("BASIC_INFO", "SKILLS", "EXPERIENCE", "PROJECT"));

        DimensionScore score = calculator.structureCompleteness(resume, longResumeText());

        assertEquals(100, score.score());
        assertTrue(score.detail().contains("教育背景按正文内容识别"));
    }

    @Test
    void educationMatchGivesPartialCreditWhenDegreeIsNotExplicit() {
        JdParsed jd = jd("BACHELOR");
        ResumeParsed resume = resume("NONE", "北京大学", "计算机科学与技术",
                List.of("BASIC_INFO", "SKILLS", "EXPERIENCE", "PROJECT"));

        DimensionScore score = calculator.educationMatch(jd, resume);

        assertEquals(40, score.score());
        assertTrue(score.detail().contains("北京大学"));
        assertTrue(score.detail().contains("未识别学历层次"));
    }

    @Test
    void educationMatchStillReportsNoEvidenceWhenNothingIsPresent() {
        JdParsed jd = jd("BACHELOR");
        ResumeParsed resume = resume("NONE", null, null,
                List.of("BASIC_INFO", "SKILLS", "EXPERIENCE", "PROJECT"));

        DimensionScore score = calculator.educationMatch(jd, resume);

        assertEquals(30, score.score());
        assertTrue(score.detail().contains("未识别到教育经历或学历层次"));
    }

    private ResumeParsed resume(String degree, String school, String major, List<String> sections) {
        return new ResumeParsed(
                List.of(),
                List.of(),
                List.of(),
                new ResumeParsed.Education(degree, school, major),
                sections,
                1.0
        );
    }

    private JdParsed jd(String educationRequirement) {
        return new JdParsed(
                "Java 后端开发",
                List.of(),
                List.of(),
                1,
                educationRequirement,
                List.of()
        );
    }

    private String longResumeText() {
        String filler = "x".repeat(320);
        return String.join("\n",
                "张三",
                "13800138000",
                "北京大学",
                "计算机科学与技术",
                "专业技能",
                "工作经历",
                "项目经历",
                filler);
    }
}
