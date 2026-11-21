package com.jianli.analysis.calculator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  权重的"实际生效版本"。
 *
 *  两套权重：
 *      configured → 配置里写的原始权重
 *      effective  → 剔除不适用维度后，重新归一化的权重
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么需要重新归一化？举例说明】
 *
 *  场景：JD 没写学历要求
 *
 *  原始权重：
 *      技能覆盖 0.35 / 经验 0.15 / 项目 0.20 / 量化 0.10 / 结构 0.10 / 学历 0.10
 *  总和 = 1.00
 *
 *  但学历维度"不适用"（JD 没要求）。
 *  如果把学历按 0 分算：
 *      总分 = 0.35×70 + 0.15×100 + 0.20×70 + 0.10×50 + 0.10×80 + 0.10×0
 *           = 24.5 + 15 + 14 + 5 + 8 + 0
 *           = 66.5
 *      ★ 凭空少了 10 分！因为这个维度权重按 0.10 但分数是 0。
 *
 *  正确做法：把学历的权重按比例分给其余五个维度。
 *      其余五个权重和 = 0.90
 *      技能覆盖 → 0.35 / 0.90 = 0.3889
 *      经验     → 0.15 / 0.90 = 0.1667
 *      ...
 *      总和重新 = 1.00
 *
 *      总分 = 0.3889×70 + 0.1667×100 + 0.2222×70 + 0.1111×50 + 0.1111×80
 *           = 27.2 + 16.7 + 15.6 + 5.6 + 8.9
 *           = 74.0
 *      ★ 这才是"在 JD 提到的维度里，你的平均表现"。
 *
 *  ★ 这就是为什么要存 both：
 *    报告里两个都存，用户问"为什么学历权重变成 0 了"能解释清楚。
 */
public record EffectiveWeights(
        Map<String, Double> configured,
        Map<String, Double> effective,
        List<String> notApplicable
) {

    public double of(String key) {
        return effective.getOrDefault(key, 0.0);
    }

    /**
     * 计算生效权重。
     *
     * @param configured 配置的原始权重
     * @param dims       所有维度（含不适用的）
     */
    public static EffectiveWeights normalize(Map<String, Double> configured,
                                             List<DimensionScore> dims) {

        // 1. 挑出不适用的维度
        List<String> na = dims.stream()
                .filter(d -> !d.applicable())
                .map(DimensionScore::key)
                .toList();

        // 2. 算"适用维度"的权重和
        double sum = dims.stream()
                .filter(DimensionScore::applicable)
                .mapToDouble(d -> configured.getOrDefault(d.key(), 0.0))
                .sum();

        // 3. 按比例放大
        Map<String, Double> eff = new LinkedHashMap<>();
        for (DimensionScore d : dims) {
            if (!d.applicable()) {
                eff.put(d.key(), 0.0);
                continue;
            }
            double w = configured.getOrDefault(d.key(), 0.0);
            eff.put(d.key(), sum <= 0 ? 0.0 : w / sum);
        }

        return new EffectiveWeights(Map.copyOf(configured), Map.copyOf(eff), na);
    }
}
