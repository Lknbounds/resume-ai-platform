package com.jianli.analysis.calculator;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  一个维度的评分结果。
 *
 *  六个维度：技能覆盖 / 经验年限 / 项目相关度 / 量化成果 / 结构完整度 / 学历匹配
 * ═══════════════════════════════════════════════════════════════════════
 *
 * @param key      维度标识（英文，前端用来做样式映射）
 * @param name     中文名（前端直接显示）
 * @param score    得分 0-100
 * @param applicable 是否参与计算
 *                  ★ false 表示"这个维度不适用"（比如 JD 没要求学历），
 *                    会被排除出加权平均，权重分给其他维度。
 *                    不能当 0 分算 —— 那等于惩罚"没人提的要求"。
 * @param detail   一句话说明这个分是怎么来的（可解释性）
 */
public record DimensionScore(
        String key,
        String name,
        double score,
        boolean applicable,
        String detail
) {

    /** 构造一个"不适用"的维度 */
    public static DimensionScore notApplicable(String key, String name, String reason) {
        return new DimensionScore(key, name, 0, false, reason);
    }
}
