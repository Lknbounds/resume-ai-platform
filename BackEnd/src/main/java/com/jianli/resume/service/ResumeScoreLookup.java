package com.jianli.resume.service;

import com.jianli.resume.dto.ResumeAnalysisSummary;

import java.util.List;
import java.util.Map;

/**
 * 查询简历版本最近一次分析摘要。
 *
 * 接口定义在 resume 包、实现在 analysis 包，避免两个业务模块互相
 * 依赖具体 Service。返回值按 versionId 索引，且没有分析记录的版本
 * 不会出现在 Map 中——前端据此区分“未分析”和“0 分”。
 */
public interface ResumeScoreLookup {

    Map<Long, ResumeAnalysisSummary> latestSummaries(
            Long userId, List<Long> versionIds);
}
