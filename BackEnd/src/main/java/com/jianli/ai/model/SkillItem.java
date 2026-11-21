package com.jianli.ai.model;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  一条技能。JD 和简历都用它。
 *
 *  importance 取值：HIGH / MEDIUM / LOW
 *      决定评分时的权重（HIGH=3, MEDIUM=2, LOW=1）
 *      "必须会 Java" 和 "了解 Docker 更好" 不可能同等分量
 *
 *  category 取值：LANGUAGE / FRAMEWORK / DATABASE / MIDDLEWARE / TOOL / DOMAIN / SOFT / OTHER
 *      暂时只用于前端展示分组，不参与评分
 * ═══════════════════════════════════════════════════════════════════════
 */
public record SkillItem(String name, String importance, String category) {

    public static final String HIGH = "HIGH";
    public static final String MEDIUM = "MEDIUM";
    public static final String LOW = "LOW";

    /** 按重要度给权重，评分用 */
    public int weight() {
        return switch (importance == null ? MEDIUM : importance) {
            case HIGH -> 3;
            case LOW -> 1;
            default -> 2;
        };
    }
}
