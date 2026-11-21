package com.jianli.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  JD 解析结果。规则解析器和将来的 AI 解析器都产出这个结构 —— 这就是"契约"。
 *
 *  ★ 注意：里面【没有 score 字段】。
 *    这是故意的：AI 就算输出 "score": 100 也会被忽略，
 *    因为评分由程序算（见 ScoreCalculator）。
 * ═══════════════════════════════════════════════════════════════════════
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JdParsed(
        String jobTitle,
        List<SkillItem> requiredSkills,
        List<SkillItem> niceToHaveSkills,
        Integer minYearsExperience,
        String educationRequirement,
        List<String> keywords,

        /**
         * 软技能（团队协作、沟通能力等）。
         *
         * 【★ 为什么单独一个字段，不放进 requiredSkills？】
         *
         *  软技能【不参与评分】，只在建议里提示。
         *
         *  理由：
         *    1. 简历无法可靠体现"团队协作"——
         *       写"我善于沟通"是空话，写"带过 5 人团队"才是证据，
         *       但后者靠规则识别不出来
         *    2. ATS 系统本身只做关键词匹配，不评估软技能
         *    3. 如果参与评分，用户会觉得"系统在挑我毛病"，
         *       而且是个无法证明的指控
         *
         *  ★ 原则：测不准的东西就不要测。
         */
        List<String> softSkills
) {

    public JdParsed {
        requiredSkills = requiredSkills == null ? List.of() : List.copyOf(requiredSkills);
        niceToHaveSkills = niceToHaveSkills == null ? List.of() : List.copyOf(niceToHaveSkills);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        softSkills = softSkills == null ? List.of() : List.copyOf(softSkills);
        minYearsExperience = minYearsExperience == null ? 0 : Math.max(0, minYearsExperience);
        educationRequirement = educationRequirement == null ? "NONE" : educationRequirement;
    }

    /**
     * 兼容旧构造器（6 个参数，没有 softSkills）。
     *
     * ★ 为什么要一个兼容构造器？
     *   因为反序列化历史数据时，softSkills 字段可能不存在（JSON 里没有）。
     *   虽然紧凑构造器会兜底成空列表，但显式提供一个 6 参构造器
     *   让其他代码调用时不用写第 7 个参数。
     */
    public JdParsed(String jobTitle, List<SkillItem> requiredSkills,
                    List<SkillItem> niceToHaveSkills, Integer minYearsExperience,
                    String educationRequirement, List<String> keywords) {
        this(jobTitle, requiredSkills, niceToHaveSkills, minYearsExperience,
                educationRequirement, keywords, null);
    }

    /** 带上软技能返回一个新实例（record 不可变，所以是"复制并替换一个字段"） */
    public JdParsed withSoftSkills(List<String> soft) {
        return new JdParsed(jobTitle, requiredSkills, niceToHaveSkills,
                minYearsExperience, educationRequirement, keywords, soft);
    }

    public boolean hasAnySkill() {
        return !requiredSkills.isEmpty();
    }
}
