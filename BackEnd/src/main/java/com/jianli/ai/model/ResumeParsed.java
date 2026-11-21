package com.jianli.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  简历解析结果。同样是"契约" —— 规则解析和 AI 解析都产出这个结构。
 * ═══════════════════════════════════════════════════════════════════════
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResumeParsed(
        List<ResumeSkill> skills,
        List<ExperienceItem> experiences,
        List<ProjectItem> projects,
        Education education,
        List<String> sectionsPresent,
        Double totalYears
) {
    public ResumeParsed {
        skills = skills == null ? List.of() : List.copyOf(skills);
        experiences = experiences == null ? List.of() : List.copyOf(experiences);
        projects = projects == null ? List.of() : List.copyOf(projects);
        sectionsPresent = sectionsPresent == null ? List.of() : List.copyOf(sectionsPresent);
        education = education == null ? new Education("NONE", null, null) : education;
        totalYears = totalYears == null ? 0.0 : totalYears;
    }

    /**
     * 简历里出现的技能。
     *
     * ★ evidence 是"证据句"—— 技能出现的原文行。
     *   前端展示命中项时能显示"你在这一行提到了 Java"，
     *   比单纯打个勾有说服力得多。
     *
     * ★ 必须逐字来自原文，不能编造。
     *   这是"可解释"的基础。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResumeSkill(String name, String evidence) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExperienceItem(String title, String period, Integer totalMonths,
                                 List<Bullet> bullets) {
        public ExperienceItem {
            bullets = bullets == null ? List.of() : List.copyOf(bullets);
            totalMonths = totalMonths == null ? 0 : totalMonths;
        }

        /** 一条工作描述。quantified = 是否含数字结果 */
        public record Bullet(String text, boolean quantified) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectItem(String name, List<String> techStack,
                              String description, boolean quantified) {
        public ProjectItem {
            techStack = techStack == null ? List.of() : List.copyOf(techStack);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Education(String degree, String school, String major) {}
}
