package com.jianli.jd.parser;

import com.jianli.common.BizException;
import com.jianli.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdImportParserTest {

    private final JdImportParser parser = new JdImportParser();

    @Test
    void parsesUserMarkdownFormatWithNumberedHeadingAndFence() {
        String raw = """
                # 岗位 JD 汇总

                ## 1. Java 后端开发工程师
                - 公司：某科技有限公司
                - 技能标签：Java / MySQL / Redis

                **岗位描述 / 任职资格**

                ```text
                岗位职责
                1. 负责核心业务系统开发

                任职要求
                1. 熟悉 Spring Boot 和 MySQL
                ```
                """;

        List<JdImportEntry> entries = parser.parse(raw);

        assertEquals(1, entries.size());
        JdImportEntry entry = entries.get(0);
        assertEquals(1, entry.index());
        assertEquals("Java 后端开发工程师", entry.title());
        assertEquals("某科技有限公司", entry.company());
        assertTrue(entry.content().contains("负责核心业务系统开发"));
        assertTrue(entry.content().contains("熟悉 Spring Boot 和 MySQL"));
        assertTrue(!entry.content().contains("技能标签"));
    }

    @Test
    void parsesUnnumberedHeadingAndPlainTextWithoutFence() {
        String raw = """
                ## 前端开发工程师
                - 公司：星河科技
                - 技能标签：Vue / TypeScript

                **岗位描述 / 任职资格**

                岗位职责
                负责管理后台和官网开发

                任职要求
                熟悉 Vue 3 和 TypeScript
                """;

        List<JdImportEntry> entries = parser.parse(raw);

        assertEquals(1, entries.size());
        assertEquals("前端开发工程师", entries.get(0).title());
        assertEquals("星河科技", entries.get(0).company());
        assertTrue(entries.get(0).content().contains("负责管理后台和官网开发"));
        assertTrue(entries.get(0).content().contains("熟悉 Vue 3 和 TypeScript"));
        assertTrue(!entries.get(0).content().contains("技能标签"));
    }

    @Test
    void parsesMultipleSectionsAndSkipsSeparator() {
        String raw = """
                ## 1. 数据分析师
                - 公司：甲公司
                负责业务数据分析。

                ---

                ## 2. 数据开发工程师
                - 公司：乙公司
                负责数据仓库建设。
                """;

        List<JdImportEntry> entries = parser.parse(raw);

        assertEquals(2, entries.size());
        assertEquals("数据分析师", entries.get(0).title());
        assertEquals("数据开发工程师", entries.get(1).title());
        assertEquals("甲公司", entries.get(0).company());
        assertEquals("乙公司", entries.get(1).company());
        assertTrue(!entries.get(0).content().contains("---"));
        assertTrue(!entries.get(1).content().contains("---"));
    }

    @Test
    void rejectsFileWithoutMarkdownHeadings() {
        BizException exception = assertThrows(BizException.class,
                () -> parser.parse("Java 后端开发工程师\n负责系统开发。"));

        assertEquals(ErrorCode.JD_IMPORT_FORMAT_INVALID, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("##"));
    }

    @Test
    void keepsBlankTitleForServiceLevelValidationAndAllowsMissingCompany() {
        String raw = """
                ##
                负责系统开发。

                ## 测试工程师
                负责质量保障。
                """;

        List<JdImportEntry> entries = parser.parse(raw);

        assertEquals(2, entries.size());
        assertEquals("", entries.get(0).title());
        assertNull(entries.get(0).company());
        assertEquals("测试工程师", entries.get(1).title());
        assertNull(entries.get(1).company());
    }
}
