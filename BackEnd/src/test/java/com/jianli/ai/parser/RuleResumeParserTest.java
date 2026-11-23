package com.jianli.ai.parser;

import com.jianli.ai.model.ResumeParsed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleResumeParserTest {

    private RuleResumeParser parser;

    @BeforeEach
    void setUp() {
        SkillDictionary dictionary = mock(SkillDictionary.class);
        when(dictionary.findAll(anyString())).thenReturn(List.of());
        parser = new RuleResumeParser(dictionary);
    }

    @Test
    void extractsEducationFromContentWithoutSectionHeading() {
        ResumeParsed parsed = parser.parse("""
                张三
                13800138000
                求职意向：Java 后端开发

                北京大学
                计算机科学与技术
                2020.09-2024.06

                工作经历
                某公司 Java 开发 2024.07-至今
                """);

        assertFalse(parsed.sectionsPresent().contains("EDUCATION"));
        assertEquals("北京大学", parsed.education().school());
        assertEquals("计算机科学与技术", parsed.education().major());
        assertEquals("NONE", parsed.education().degree());
    }

    @Test
    void extractsEducationFromSchoolFirstLineUsedByRealResumeTemplates() {
        ResumeParsed parsed = parser.parse("""
                潘文杰
                男 | 23 岁 | 18830065725 | 320849237@qq.com
                内蒙古财经大学  硕士 · 电子信息  2025 - 2028
                邢台学院  本科 · 计算机科学与技术  2020 - 2024
                """);

        assertFalse(parsed.sectionsPresent().contains("EDUCATION"));
        assertEquals("内蒙古财经大学", parsed.education().school());
        assertEquals("MASTER", parsed.education().degree());
    }

    @Test
    void doesNotIncludeEducationHeadingInSchoolName() {
        ResumeParsed parsed = parser.parse("""
                李四
                教育背景：北京邮电大学
                本科 计算机科学与技术
                2019.09-2023.06
                """);

        assertEquals("北京邮电大学", parsed.education().school());
        assertEquals("BACHELOR", parsed.education().degree());
    }

    @Test
    void extractsEnglishSchoolAndDegree() {
        ResumeParsed parsed = parser.parse("""
                Alex Chen
                University of Melbourne
                Bachelor of Science in Computer Science
                2019 - 2023
                """);

        assertEquals("University of Melbourne", parsed.education().school());
        assertEquals("BACHELOR", parsed.education().degree());
        assertEquals("computer science", parsed.education().major().toLowerCase());
    }
}
