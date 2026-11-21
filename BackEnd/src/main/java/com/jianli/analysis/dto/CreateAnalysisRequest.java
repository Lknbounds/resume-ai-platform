package com.jianli.analysis.dto;

import jakarta.validation.constraints.NotNull;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  发起分析的请求体。
 *
 *      POST /api/analyses
 *      {"resumeVersionId":2,"jdId":1,"forceRefresh":false}
 * ═══════════════════════════════════════════════════════════════════════
 */
public record CreateAnalysisRequest(

        /** 用哪一版简历分析 */
        @NotNull(message = "请选择简历版本")
        Long resumeVersionId,

        /** 对标哪个岗位 */
        @NotNull(message = "请选择目标岗位")
        Long jdId,

        /**
         * 是否强制重新计算（忽略缓存）。
         *
         * ★ 什么时候用？
         *   用户改了词典？不会。用户改了权重？不会。
         *   实际上【几乎用不到】—— 正常流程走缓存更快。
         *   但保留这个参数，方便调试和"我就是想重算一次"的场景。
         *
         * ★ 为 null 时按 false 处理。
         */
        Boolean forceRefresh
) {}
