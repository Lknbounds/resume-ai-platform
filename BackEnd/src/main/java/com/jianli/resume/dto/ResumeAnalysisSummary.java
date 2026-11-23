package com.jianli.resume.dto;

import java.time.LocalDateTime;

/**
 * 简历列表使用的“最近一次岗位分析”摘要。
 *
 * 匹配分不是简历本身的属性，而是“简历版本 × JD”的结果。
 * 因此列表除了分数，还必须同时返回岗位、分析时间和已分析岗位数。
 */
public record ResumeAnalysisSummary(
        Long reportId,
        Long jdId,
        String jdTitle,
        String jdCompany,
        Double score,
        LocalDateTime analyzedAt,
        int jdCount
) {}
