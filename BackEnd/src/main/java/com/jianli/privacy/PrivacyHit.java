package com.jianli.privacy;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这条记录是什么】
 *  一处命中的敏感信息。
 * ═══════════════════════════════════════════════════════════════════════
 *
 * @param type          类型（手机号/身份证/…）
 * @param start         在【原文】中的起始下标
 * @param end           在原文中的结束下标（不含）
 * @param maskedSnippet 脱敏后的片段，如 138****5678
 * @param advice        修改建议
 */
public record PrivacyHit(
        SensitiveType type,
        int start,
        int end,
        String maskedSnippet,
        String advice
) {

    /**
     * 【★ 为什么 start/end 是"原文下标"而不是"归一化文本下标"？】
     *
     *  因为前端要在 textarea 里高亮定位。
     *  而 textarea 里是用户看到的【原文】。
     *
     *  ★ 但正则是在【归一化文本】上匹配的（吞掉了 138-0013-8000 里的短横线）。
     *    所以匹配后必须把下标映射回原文 —— 见 NormalizedText。
     */
}
