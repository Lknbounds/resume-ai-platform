package com.jianli.analysis.service;

import com.jianli.analysis.entity.AnalysisReport;
import com.jianli.analysis.repository.AnalysisReportRepository;
import com.jianli.jd.entity.JobDescription;
import com.jianli.jd.repository.JobDescriptionRepository;
import com.jianli.resume.dto.ResumeAnalysisSummary;
import com.jianli.resume.service.ResumeScoreLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ResumeScoreLookup 的实现。放在 analysis 包，因为只有它知道报告怎么查。
 */
@Component
@RequiredArgsConstructor
public class ResumeScoreLookupImpl implements ResumeScoreLookup {

    private final AnalysisReportRepository reportRepository;
    private final JobDescriptionRepository jobDescriptionRepository;

    @Override
    public Map<Long, ResumeAnalysisSummary> latestSummaries(
            Long userId, List<Long> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) {
            return Map.of();
        }

        List<AnalysisReport> reports =
                reportRepository.findCompletedByVersionIdsOrderByIdDesc(userId, versionIds);
        if (reports.isEmpty()) {
            return Map.of();
        }

        // 查询已按 id 倒序，putIfAbsent 保留下来的就是每个版本的最新报告。
        Map<Long, AnalysisReport> latestByVersion = new LinkedHashMap<>();
        Map<Long, Set<Long>> analyzedJdsByVersion = new HashMap<>();
        for (AnalysisReport report : reports) {
            Long versionId = report.getResumeVersionId();
            if (versionId == null) {
                continue;
            }
            latestByVersion.putIfAbsent(versionId, report);
            if (report.getJdId() != null) {
                analyzedJdsByVersion
                        .computeIfAbsent(versionId, ignored -> new HashSet<>())
                        .add(report.getJdId());
            }
        }

        Set<Long> jdIds = analyzedJdsByVersion.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
        Map<Long, JobDescription> jdsById = jdIds.isEmpty()
                ? Map.of()
                : jobDescriptionRepository.findAllById(jdIds).stream()
                        .collect(Collectors.toMap(JobDescription::getId, jd -> jd));

        Map<Long, ResumeAnalysisSummary> result = new LinkedHashMap<>();
        for (Map.Entry<Long, AnalysisReport> entry : latestByVersion.entrySet()) {
            Long versionId = entry.getKey();
            AnalysisReport report = entry.getValue();
            JobDescription jd = report.getJdId() == null ? null : jdsById.get(report.getJdId());

            result.put(versionId, new ResumeAnalysisSummary(
                    report.getId(),
                    report.getJdId(),
                    jd == null ? null : jd.getTitle(),
                    jd == null ? null : jd.getCompany(),
                    report.getOverallScore() == null ? null : report.getOverallScore().doubleValue(),
                    report.getCreatedAt(),
                    analyzedJdsByVersion.getOrDefault(versionId, Set.of()).size()
            ));
        }
        return result;
    }
}
