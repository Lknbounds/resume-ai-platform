package com.jianli.analysis.calculator;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  一条技能的匹配结果。
 * ═══════════════════════════════════════════════════════════════════════
 *
 * 【★ matchValue 为什么只有 1.0 / 0.5 / 0.0 三档？】
 *
 *  看起来可以有更细的分数（0.7、0.8...），但没必要：
 *    1.0 = 完全命中（简历里明确写了这个技能）
 *    0.5 = 部分命中（写了相近的技能，或用了别名）
 *    0.0 = 缺失
 *
 *  ★ 三档的好处是【可解释】：
 *    用户问"为什么是 70.6 分"，你能说"7 项技能里 4 项完全命中、
 *    1 项部分命中、2 项缺失"。这比"平均相似度 0.73"好懂得多。
 */
public record SkillMatch(
        String skill,
        String importance,
        double matchValue,
        String matchedBy,
        String evidence
) {

    public boolean isMissing() {
        return matchValue <= 0;
    }

    public boolean isPartial() {
        return matchValue > 0 && matchValue < 1;
    }

    /** 重要度权重：HIGH=3, MEDIUM=2, LOW=1 */
    public int weight() {
        return switch (importance == null ? "MEDIUM" : importance) {
            case "HIGH" -> 3;
            case "LOW" -> 1;
            default -> 2;
        };
    }
}
