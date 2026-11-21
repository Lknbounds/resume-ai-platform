package com.jianli.jd.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianli.ai.model.JdParsed;
import com.jianli.ai.parser.RuleJdParser;
import com.jianli.common.BizException;
import com.jianli.common.ErrorCode;
import com.jianli.jd.dto.JdVO;
import com.jianli.jd.dto.SaveJdRequest;
import com.jianli.jd.entity.JobDescription;
import com.jianli.jd.repository.JobDescriptionRepository;
import com.jianli.resume.service.ResumeTextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  JD 的业务逻辑：保存、解析、查询、删除。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向】
 *
 *      用户粘贴 JD → POST /api/jds
 *         ├─ 归一化内容
 *         ├─ 规则解析 → JdParsed（技能/年限/学历）
 *         ├─ 序列化成 JSON 存进 parsed_json
 *         └─ 返回 JdVO
 *              ↓
 *      发起分析时 → 用 parsed_json 里的技能列表做匹配
 * ═══════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JdService {

    /** JD 最小字数（去掉空白）。太短的 JD 抽不出有效信息 */
    private static final int MIN_CHARS = 30;

    private final JobDescriptionRepository repository;
    private final RuleJdParser parser;
    private final ResumeTextNormalizer normalizer;
    private final ObjectMapper objectMapper;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【保存 JD 并立即解析】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 为什么保存时就解析，不等分析时再解析？
     *   1. 用户体验：保存后立刻能看到"抽出了哪些技能"，
     *      如果解析不出来说明 JD 写得太简短，可以马上补
     *   2. 规则解析只要几毫秒，同步做完全不卡
     *
     * ★ 如果将来接入 AI 解析（几秒钟），就应该改成异步：
     *   保存返回 PENDING，后台跑完更新状态，前端轮询。
     *   现在的同步实现是"规则版够快"的红利。
     */
    @Transactional
    public JdVO create(Long userId, SaveJdRequest req) {

        String content = normalizeChecked(req.content());

        JobDescription jd = new JobDescription();
        jd.setUserId(userId);
        jd.setTitle(req.title().trim());
        jd.setCompany(req.company() == null || req.company().isBlank()
                ? null : req.company().trim());
        jd.setContent(content);
        jd.setContentHash(normalizer.sha256(content));

        // 立即解析
        applyParsed(jd);

        jd = repository.save(jd);
        log.info("保存 JD, userId={}, jdId={}, 技能数={}",
                userId, jd.getId(), readParsed(jd).requiredSkills().size());

        return JdVO.of(jd, readParsed(jd));
    }

    /**
     * 更新 JD。内容变了就重新解析。
     */
    @Transactional
    public JdVO update(Long userId, Long id, SaveJdRequest req) {

        JobDescription jd = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BizException(ErrorCode.JD_NOT_FOUND));

        String content = normalizeChecked(req.content());
        boolean contentChanged = !content.equals(jd.getContent());

        jd.setTitle(req.title().trim());
        jd.setCompany(req.company() == null || req.company().isBlank()
                ? null : req.company().trim());
        jd.setContent(content);

        if (contentChanged) {
            jd.setContentHash(normalizer.sha256(content));
            applyParsed(jd);
        }

        return JdVO.of(jd, readParsed(jd));
    }

    /**
     * 我的 JD 列表。
     *
     * ★ 列表也带上解析结果 ——
     *   前端列表可以直接显示"7 项技能要求"，不用再查一次。
     *   JD 解析结果只有几 KB，不是负担。
     */
    @Transactional(readOnly = true)
    public List<JdVO> list(Long userId, String keyword) {
        String kw = keyword == null ? "" : keyword.trim();

        var pageable = org.springframework.data.domain.PageRequest.of(0, 100);
        var page = kw.isEmpty()
                ? repository.findByUserIdOrderByUpdatedAtDesc(userId, pageable)
                : repository.searchByUserId(userId, kw, pageable);

        return page.getContent().stream()
                .map(jd -> JdVO.of(jd, readParsed(jd)))
                .toList();
    }

    @Transactional(readOnly = true)
    public JdVO detail(Long userId, Long id) {
        JobDescription jd = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BizException(ErrorCode.JD_NOT_FOUND));
        return JdVO.of(jd, readParsed(jd));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        JobDescription jd = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BizException(ErrorCode.JD_NOT_FOUND));
        repository.delete(jd);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【给其他模块用：拿到解析结果】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 谁会用：AnalysisService（评分时要用 JD 的技能要求）
     *
     * ★ 为什么不是直接返回实体？
     *   因为调用方需要的是"解析结果"，不是数据库行。
     *   在这里把 JSON 反序列化好，调用方拿到就能用。
     */
    @Transactional(readOnly = true)
    public JdParsed requireParsed(Long userId, Long jdId) {
        JobDescription jd = repository.findByIdAndUserId(jdId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.JD_NOT_FOUND));

        JdParsed parsed = readParsed(jd);

        // 兜底：如果历史数据没解析过，现场解析一次
        if (!parsed.hasAnySkill()) {
            JdParsed fresh = parser.parse(jd.getContent());
            if (!fresh.hasAnySkill()) {
                throw new BizException(ErrorCode.JD_CONTENT_INVALID, null,
                        "这份 JD 没有识别出任何技能要求，请补充技术要求部分");
            }
            return fresh;
        }
        return parsed;
    }

    // ═══════════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════════

    /** 解析并写入实体的解析字段 */
    private void applyParsed(JobDescription jd) {
        try {
            JdParsed parsed = parser.parse(jd.getContent());
            jd.setParsedJson(objectMapper.writeValueAsString(parsed));
            jd.setParsedSource("RULE");
            jd.setParseStatus("SUCCESS");
            jd.setParsedAt(LocalDateTime.now(ZoneOffset.UTC));
        } catch (Exception e) {
            log.error("JD 解析失败, id={}", jd.getId(), e);
            jd.setParseStatus("FAILED");
        }
    }

    /** 从实体读解析结果（反序列化 JSON 列） */
    private JdParsed readParsed(JobDescription jd) {
        if (jd.getParsedJson() == null || jd.getParsedJson().isBlank()) {
            return new JdParsed(jd.getTitle(), null, null, 0, "NONE", null);
        }
        try {
            return objectMapper.readValue(jd.getParsedJson(), JdParsed.class);
        } catch (Exception e) {
            log.warn("JD 解析结果反序列化失败, id={}", jd.getId());
            return new JdParsed(jd.getTitle(), null, null, 0, "NONE", null);
        }
    }

    private String normalizeChecked(String raw) {
        String content = normalizer.normalize(raw);
        if (normalizer.charCount(content) < MIN_CHARS) {
            throw new BizException(ErrorCode.JD_CONTENT_INVALID);
        }
        return content;
    }
}
