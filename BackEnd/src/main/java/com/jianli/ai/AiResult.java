package com.jianli.ai;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  AI 调用的"结果包装"。★ 用来说明"这次是 AI 算的还是降级到规则了"。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么需要 source 和 degraded 两个字段？】
 *
 *  source   = AI / RULE / TEMPLATE  → 数据【从哪来的】
 *  degraded = true/false            → 是否发生了【降级】
 *
 *  ★ 这两个不是重复的：
 *      AI 关闭         → source=TEMPLATE, degraded=true
 *      AI 调用超时      → source=TEMPLATE, degraded=true
 *      AI 正常返回      → source=AI,       degraded=false
 *
 *  ★ 为什么要区分？
 *    前端需要告诉用户"这次的题是 AI 生成还是模板生成的" ——
 *    用户有权知道结果的可靠程度。
 *
 *  ★ 这是"诚实降级"原则的具体体现：
 *    降级了就说降级了，不假装一切正常。
 */
public record AiResult<T>(

        /** 数据 */
        T data,

        /** 来源：AI / RULE / TEMPLATE / NONE */
        String source,

        /** 是否降级 */
        boolean degraded,

        /** 降级原因：AI_DISABLED / AI_TIMEOUT / AI_CALL_FAILED / AI_OUTPUT_INVALID */
        String degradedReason
) {

    public static <T> AiResult<T> ai(T data) {
        return new AiResult<>(data, "AI", false, null);
    }

    public static <T> AiResult<T> fallback(T data, String reason) {
        return new AiResult<>(data, "TEMPLATE", true, reason);
    }

    public boolean fromAi() {
        return "AI".equals(source);
    }
}
