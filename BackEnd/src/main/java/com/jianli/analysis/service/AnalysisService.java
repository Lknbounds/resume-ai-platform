package com.jianli.analysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianli.ai.model.JdParsed;
import com.jianli.ai.model.ResumeParsed;
import com.jianli.ai.parser.RuleResumeParser;
import com.jianli.analysis.calculator.DimensionScore;
import com.jianli.analysis.calculator.ScoreCalculator;
import com.jianli.analysis.calculator.ScoreResult;
import com.jianli.analysis.calculator.SkillMatch;
import com.jianli.analysis.dto.AnalysisReportVO;
import com.jianli.analysis.dto.AnalysisReportVO.DimensionVO;
import com.jianli.analysis.dto.AnalysisReportVO.KeywordVO;
import com.jianli.analysis.entity.AnalysisReport;
import com.jianli.analysis.repository.AnalysisReportRepository;
import com.jianli.common.BizException;
import com.jianli.common.ErrorCode;
import com.jianli.common.PageResult;
import com.jianli.jd.entity.JobDescription;
import com.jianli.jd.repository.JobDescriptionRepository;
import com.jianli.jd.service.JdService;
import com.jianli.resume.entity.Resume;
import com.jianli.resume.entity.ResumeVersion;
import com.jianli.resume.repository.ResumeRepository;
import com.jianli.resume.service.ResumeService;
import com.jianli.resume.service.ResumeTextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  分析业务。★ 整个产品的主链路：
 *      选简历版本 + 选 JD → 解析 → 评分 → 建议 → 存报告
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：一次分析的完整过程】
 *
 *      用户点"开始分析"
 *         ↓
 *      POST /api/analyses {resumeVersionId, jdId}
 *         ↓
 *      ① 校验归属（简历版本 + JD 都必须是当前用户的）
 *         ↓
 *      ② 查缓存：同 cacheKey 的报告存在吗？
 *         存在 → 直接返回（cached=true）
 *         ↓
 *      ③ 解析简历（规则版，复用 JD 的技能词典）
 *         解析 JD（JdService 已存好结果，直接读）
 *         ↓
 *      ④ ScoreCalculator 算分（六维度加权）
 *         ↓
 *      ⑤ SuggestionService 生成建议
 *         ↓
 *      ⑥ 全部存进 analysis_report（含四份 JSON 快照）
 *         ↓
 *      返回报告
 *
 *  【★★ 为什么现在是同步的？】
 *
 *  规则解析只要几毫秒，同步完全不影响体验。
 *  等接入 AI（一次 5-10 秒）时，必须改成异步 + SSE，
 *  但那时【表结构不用改】—— 状态字段早在 V1 就设计好了。
 *
 *  ★ 这是"先跑通、后增强"的具体体现：
 *    现在的同步版和将来的异步版共用同一张表、同一个入口，
 *    只是"谁来执行"不同。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    /** 评分算法版本。改动算法必须升版本，否则历史分数无法区分 */
    public static final String SCORING_VERSION = "v1";

    /** "已完成"的状态集合 */
    private static final List<String> DONE = List.of("SUCCESS", "PARTIAL");

    private final AnalysisReportRepository reportRepository;
    private final JobDescriptionRepository jdRepository;
    private final ResumeRepository resumeRepository;
    private final com.jianli.resume.repository.ResumeVersionRepository versionRepository;
    private final ResumeService resumeService;
    private final JdService jdService;
    private final RuleResumeParser resumeParser;
    private final ScoreCalculator scoreCalculator;
    private final SuggestionService suggestionService;
    private final StrengthService strengthService;
    private final ResumeTextNormalizer normalizer;
    private final ObjectMapper objectMapper;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【创建分析】★ 主入口
     * ═══════════════════════════════════════════════════════════════
     */
    @Transactional
    public AnalysisReportVO create(Long userId, Long resumeVersionId,
                                    Long jdId, boolean forceRefresh) {
        long start = System.currentTimeMillis();

        // ───── ① 校验归属 ─────
        ResumeVersion version = resumeService.requireOwnedVersion(userId, resumeVersionId);

        JobDescription jd = jdRepository.findByIdAndUserId(jdId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.JD_NOT_FOUND));

        // ───── ② 算 cacheKey 并查缓存 ─────
        String cacheKey = buildCacheKey(version, jd);

        if (!forceRefresh) {
            Optional<AnalysisReport> cached = reportRepository
                    .findFirstByCacheKeyAndStatusInOrderByIdDesc(cacheKey, DONE);
            if (cached.isPresent()) {
                log.debug("分析命中缓存, reportId={}", cached.get().getId());
                return toVO(cached.get());
            }
        }

        // ───── ③ 解析 ─────
        JdParsed jdParsed = jdService.requireParsed(userId, jdId);
        ResumeParsed resumeParsed = resumeParser.parse(version.getContent());

        // ───── ④ 评分 ─────
        ScoreResult score = scoreCalculator.calculate(
                jdParsed, resumeParsed, version.getContent());

        // ───── ⑤ 建议 ─────
        List<AnalysisReportVO.SuggestionVO> suggestions =
                suggestionService.build(score, jdParsed, resumeParsed, version.getContent());

        // ───── ⑥ 存报告 ─────
        AnalysisReport report = new AnalysisReport();
        report.setUserId(userId);
        report.setResumeVersionId(resumeVersionId);
        report.setJdId(jdId);
        report.setStatus("SUCCESS");
        report.setOverallScore(BigDecimal.valueOf(score.overallScore()));
        report.setGrade(score.grade());
        report.setScoringVersion(SCORING_VERSION);
        report.setCacheKey(cacheKey);
        report.setAiEnabled(false);
        report.setDegraded(false);
        report.setSemanticJson("{\"source\":\"RULE\"}");

        // 四份快照
        report.setJdParsedJson(toJson(jdParsed));
        report.setResumeParsedJson(toJson(resumeParsed));
        report.setDimensionJson(toJson(buildDimensions(score)));
        report.setKeywordJson(toJson(buildKeywords(score)));
        report.setWeightsJson(toJson(Map.of(
                "configured", score.weights().configured(),
                "effective", score.weights().effective(),
                "notApplicable", score.weights().notApplicable()
        )));

        report.setDurationMs((int) (System.currentTimeMillis() - start));
        report = reportRepository.save(report);

        log.info("分析完成, userId={}, reportId={}, score={}, grade={}, 耗时={}ms",
                userId, report.getId(), score.overallScore(), score.grade(),
                report.getDurationMs());

        // ⑦ 组装返回（用内存里的建议，不用再查一次）
        return assemble(report, version, jd, jdParsed, suggestions);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【查报告详情】
     * ═══════════════════════════════════════════════════════════════
     */
    @Transactional(readOnly = true)
    public AnalysisReportVO detail(Long userId, Long reportId) {
        AnalysisReport report = reportRepository.findByIdAndUserId(reportId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.ANALYSIS_NOT_FOUND));
        return toVO(report);
    }

    /**
     * 我的分析历史。
     */
    @Transactional(readOnly = true)
    public PageResult<AnalysisReportVO> list(Long userId, Long resumeVersionId,
                                              int page, int size) {
        int safePage = Math.max(page - 1, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);

        Page<AnalysisReport> p = resumeVersionId != null
                ? reportRepository.findByResumeVersionIdAndUserIdOrderByCreatedAtDesc(
                        resumeVersionId, userId, PageRequest.of(safePage, safeSize))
                : reportRepository.findByUserIdOrderByCreatedAtDesc(
                        userId, PageRequest.of(safePage, safeSize));

        List<AnalysisReportVO> vos = p.getContent().stream().map(this::toVO).toList();

        return new PageResult<>(vos, p.getTotalElements(),
                safePage + 1, safeSize, p.getTotalPages());
    }

    @Transactional
    public void delete(Long userId, Long reportId) {
        AnalysisReport report = reportRepository.findByIdAndUserId(reportId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.ANALYSIS_NOT_FOUND));
        reportRepository.delete(report);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【给简历列表用：批量查最新分数】
     * ═══════════════════════════════════════════════════════════════
     *
     * @param versionIds 一页简历的 currentVersionId 列表
     * @return versionId → 最新报告
     *
     * ★ 为什么批量？避免 N+1。
     *   10 份简历如果逐个查，就是 10 次 SQL；
     *   批量查是 1 次。
     */
    @Transactional(readOnly = true)
    public Map<Long, AnalysisReport> latestByVersions(Long userId, List<Long> versionIds) {
        if (versionIds.isEmpty()) return Map.of();
        return reportRepository.findLatestByVersionIds(userId, versionIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        AnalysisReport::getResumeVersionId, r -> r, (a, b) -> a));
    }

    // ═══════════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════════

    /**
     * 构造缓存键。
     *
     * 【★★ 必须包含哪些因素？】
     *
     *   versionId      → 简历内容（版本不可变，所以 id 唯一确定内容）
     *   jdId           → 岗位要求
     *   scoringVersion → ★ 算法版本
     *   aiEnabled      → ★ AI 开关
     *
     * ★ 后两个绝不能漏：
     *   漏了 scoringVersion：算法升级后还返回旧分数
     *   漏了 aiEnabled：关了 AI 还能拿到 AI 算的结果
     *
     * ★ 为什么用简历版本 id 而不是 content_hash？
     *   因为版本内容不可变 —— id 已经唯一确定了内容，更直接。
     */
    private String buildCacheKey(ResumeVersion version, JobDescription jd) {
        return normalizer.sha256(String.join("|",
                String.valueOf(version.getId()),
                String.valueOf(jd.getId()),
                SCORING_VERSION,
                "false"                    // aiEnabled
        ));
    }

    /**
     * 把维度分数转成 JSON 结构。
     *
     * ★ 为什么要手动构造而不是直接序列化 DimensionScore？
     *   DimensionScore 只有 key/name/score/applicable/detail，
     *   但报告里还需要 weight 和 effectiveWeight。
     *   这两个在 EffectiveWeights 里，需要合并进来。
     */
    private List<Map<String, Object>> buildDimensions(ScoreResult score) {
        return score.dimensions().stream().map(d -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("key", d.key());
            m.put("name", d.name());
            m.put("score", d.score());
            m.put("weight", score.weights().configured().getOrDefault(d.key(), 0.0));
            m.put("effectiveWeight", score.weights().of(d.key()));
            m.put("applicable", d.applicable());
            m.put("detail", d.detail());
            return m;
        }).toList();
    }

    /**
     * 把技能匹配转成"命中 / 部分 / 缺失"三组。
     *
     * ★ 为什么分三组而不是一个大列表？
     *   用户最关心的是"缺什么"，其次是"写得不够准的"。
     *   分开展示能一眼看出结构。
     */
    private Map<String, Object> buildKeywords(ScoreResult score) {
        return Map.of(
                "hits", score.skillMatches().stream()
                        .filter(m -> m.matchValue() >= 1.0)
                        .map(this::toKeyword).toList(),
                "partials", score.skillMatches().stream()
                        .filter(SkillMatch::isPartial)
                        .map(this::toKeyword).toList(),
                "missing", score.skillMatches().stream()
                        .filter(SkillMatch::isMissing)
                        .map(this::toKeyword).toList()
        );
    }

    private Map<String, Object> toKeyword(SkillMatch m) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("name", m.skill());
        map.put("importance", m.importance());
        map.put("matchedBy", m.matchedBy());
        map.put("evidence", m.evidence());
        return map;
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【从实体转 VO】★ 反序列化各 JSON 列并组装
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 这里查了简历版本、简历、JD 三个实体，是为了让前端
     *   能直接显示"简历标题 · v3 × Java后端开发工程师"。
     *
     * ★ 为什么不做成 JOIN 一次查完？
     *   因为我们没有建 JPA 关联（只存 id），
     *   而且详情页只查一条报告，3 次主键查询完全可接受。
     *   ★ 只有【列表】才需要担心 N+1 —— 见 latestByVersions。
     */
    private AnalysisReportVO toVO(AnalysisReport r) {

        ResumeVersion version = versionRepository.findById(r.getResumeVersionId()).orElse(null);

        Long resumeId = null;
        String resumeTitle = null;
        Integer versionNo = null;

        if (version != null) {
            versionNo = version.getVersionNo();
            Resume resume = resumeRepository.findById(version.getResumeId()).orElse(null);
            if (resume != null) {
                resumeId = resume.getId();
                resumeTitle = resume.getTitle();
            }
        }

        JobDescription jd = jdRepository.findById(r.getJdId()).orElse(null);

        /*
         * ★ 为什么这里建议列表是空的？
         *   因为建议【没有单独建表】—— 它是由规则实时算出来的，
         *   存在报告的 JSON 里（见 create 方法）。
         *
         *   但 create 时我们手上有内存中的建议，直接传进来；
         *   而 detail 时只有数据库里的 JSON —— 所以这里需要
         *   从 dimension/keyword 快照重新生成建议。
         *
         *   ★ 重算 vs 存表，这是个取舍：
         *     存表 → 要新建表、要处理级联删除，但读的时候不用重算
         *     重算 → 省一张表，但每次详情页要跑一遍规则（几毫秒）
         *   本项目选重算 —— 规则建议是纯函数，重算成本极低。
         */
        List<AnalysisReportVO.SuggestionVO> suggestions =
                rebuildSuggestions(r, version, jd);

        return assemble(r, resumeId, resumeTitle, versionNo, jd,
                readJson(r.getJdParsedJson(), JdParsed.class),
                version == null ? null : version.getContent(),
                suggestions);
    }

    /**
     * 从报告快照重新生成建议。
     *
     * ★ 为什么能重算？
     *   因为评分是【确定性】的 —— 同样的简历 + 同样的 JD，
     *   重跑一遍得到完全相同的维度分数，于是建议也相同。
     *
     * ★ 这依赖一个前提：算法版本没变。
     *   如果 scoring_version 变了，重算的建议可能和当时不同。
     *   真要严格保证，就应该把建议也存下来。
     *   ★ 本项目接受这个不严格 —— 因为建议是"参考"，不是"审计证据"。
     *     报告里的分数和维度才是不可变的快照。
     */
    private List<AnalysisReportVO.SuggestionVO> rebuildSuggestions(
            AnalysisReport r, ResumeVersion version, JobDescription jd) {

        if (version == null || jd == null) return List.of();

        try {
            JdParsed jdParsed = readJson(r.getJdParsedJson(), JdParsed.class);
            ResumeParsed resumeParsed = readJson(r.getResumeParsedJson(), ResumeParsed.class);

            if (jdParsed == null || resumeParsed == null) return List.of();

            ScoreResult score = scoreCalculator.calculate(
                    jdParsed, resumeParsed, version.getContent());

            return suggestionService.build(score, jdParsed, resumeParsed, version.getContent());
        } catch (Exception e) {
            log.warn("建议重建失败, reportId={}", r.getId());
            return List.of();
        }
    }

    /** create 用的重载：手上有 version 实体 */
    private AnalysisReportVO assemble(AnalysisReport r, ResumeVersion version,
                                       JobDescription jd, JdParsed jdParsed,
                                       List<AnalysisReportVO.SuggestionVO> suggestions) {
        return assemble(r,
                version != null ? version.getResumeId() : null,
                null, version != null ? version.getVersionNo() : null,
                jd, jdParsed,
                // ★ 优势重算需要简历正文（structureCompleteness 维度要用字数）
                version != null ? version.getContent() : null,
                suggestions);
    }

    private AnalysisReportVO assemble(AnalysisReport r, Long resumeId, String resumeTitle,
                                       Integer versionNo, JobDescription jd,
                                       JdParsed jdParsed,
                                       String resumeContent,
                                       List<AnalysisReportVO.SuggestionVO> suggestions) {

        List<DimensionVO> dims = readJsonList(r.getDimensionJson()).stream()
                .map(m -> new DimensionVO(
                        str(m.get("key")),
                        str(m.get("name")),
                        num(m.get("score")),
                        num(m.get("effectiveWeight")),
                        str(m.get("detail")),
                        Boolean.TRUE.equals(m.get("applicable"))
                )).toList();

        Map<String, Object> kw = readJsonMap(r.getKeywordJson());
        List<KeywordVO> hits = toKeywords(kw.get("hits"));
        List<KeywordVO> partials = toKeywords(kw.get("partials"));
        List<KeywordVO> missing = toKeywords(kw.get("missing"));

        String summary = buildSummary(r, missing);

        /*
         * ★ 优势也在这里重算 —— 和"建议"同样的思路：
         *   评分是确定性的，同样输入必然得到同样结果，
         *   所以没必要单独建表存优势。
         *
         * ★ 但这依赖 resumeParsedJson / jdParsedJson 快照存在。
         *   历史报告的 JSON 可能缺字段 —— readJson 返回 null 时给空列表。
         */
        List<String> strengths = List.of();
        List<String> softSkills = jdParsed == null ? List.of() : jdParsed.softSkills();

        if (jdParsed != null && resumeContent != null) {
            try {
                ResumeParsed rp = readJson(r.getResumeParsedJson(), ResumeParsed.class);
                if (rp != null) {
                    ScoreResult sc = scoreCalculator.calculate(jdParsed, rp, resumeContent);
                    strengths = strengthService.build(sc, jdParsed, rp);
                }
            } catch (Exception e) {
                log.debug("优势重建失败, reportId={}", r.getId());
            }
        }

        return new AnalysisReportVO(
                r.getId(),
                r.getStatus(),
                r.getOverallScore() == null ? null : r.getOverallScore().doubleValue(),
                r.getGrade(),
                summary,
                resumeId,
                resumeTitle,
                versionNo,
                r.getJdId(),
                jd == null ? null : jd.getTitle(),
                dims,
                hits, partials, missing,
                strengths,
                softSkills,
                suggestions,
                Boolean.TRUE.equals(r.getAiEnabled()),
                Boolean.TRUE.equals(r.getDegraded()),
                r.getScoringVersion(),
                r.getDurationMs(),
                r.getCreatedAt() == null ? null : r.getCreatedAt().toString().replace('T', ' ')
        );
    }

    /**
     * 一句话总结。
     *
     * ★ 为什么不从数据库读？
     *   总结是根据分数和缺失项实时算的，不存库 ——
     *   这样改文案不用迁移数据。
     */
    private String buildSummary(AnalysisReport r, List<KeywordVO> missing) {
        if (r.getOverallScore() == null) return "分析进行中";
        double s = r.getOverallScore().doubleValue();
        long highMissing = missing.stream().filter(m -> "HIGH".equals(m.importance())).count();

        if (s >= 85) return "与目标岗位高度匹配。可以针对面试准备项目细节和量化数据。";
        if (s >= 70) return highMissing > 0
                ? "整体匹配良好，补齐 %d 项核心技能后更有竞争力。".formatted(highMissing)
                : "整体匹配良好，补齐细节后可以投递。";
        if (s >= 55) return "有一定基础，但技能缺口较明显，建议优先补齐核心技能。";
        return "与目标岗位差距较大，建议先对照 JD 补足技能，或考虑更匹配的方向。";
    }

    private List<KeywordVO> toKeywords(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(o -> o instanceof Map)
                .map(o -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    return new KeywordVO(
                            str(m.get("name")),
                            str(m.get("importance")),
                            str(m.get("matchedBy")),
                            str(m.get("evidence"))
                    );
                }).toList();
    }

    // ─── JSON 工具 ───

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("序列化失败", e);
            return "{}";
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> readJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    private Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
