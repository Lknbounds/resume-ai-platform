package com.jianli.analysis.service;

import com.jianli.analysis.entity.AnalysisReport;
import com.jianli.analysis.repository.AnalysisReportRepository;
import com.jianli.resume.service.ResumeScoreLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  ResumeScoreLookup 的实现。放在 analysis 包，因为只有它知道报告怎么查。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  ★ 这就是"接口在 resume 包、实现在 analysis 包"的依赖倒置：
 *    resume 包定义需求，analysis 包提供能力，两边都不直接依赖对方的具体类。
 */
@Component
@RequiredArgsConstructor
public class ResumeScoreLookupImpl implements ResumeScoreLookup {

    private final AnalysisReportRepository reportRepository;

    @Override
    public Map<Long, Double> latestScores(Long userId, List<Long> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) {
            return Map.of();
        }

        /*
         * ★ 一次 IN 查询拿到所有版本的最新报告。
         *   如果用 for 循环逐个查，10 份简历就是 10 次 SQL（N+1）。
         */
        List<AnalysisReport> reports =
                reportRepository.findLatestByVersionIds(userId, versionIds);

        Map<Long, Double> result = new LinkedHashMap<>();
        for (AnalysisReport r : reports) {
            if (r.getOverallScore() != null) {
                result.put(r.getResumeVersionId(), r.getOverallScore().doubleValue());
            }
        }
        return result;
    }
}
