package com.jianli.jd.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianli.ai.model.JdParsed;
import com.jianli.ai.parser.RuleJdParser;
import com.jianli.common.BizException;
import com.jianli.common.ErrorCode;
import com.jianli.common.PageResult;
import com.jianli.jd.dto.ImportJdItemResult;
import com.jianli.jd.dto.ImportJdResult;
import com.jianli.jd.dto.JdVO;
import com.jianli.jd.dto.SaveJdRequest;
import com.jianli.jd.entity.JobDescription;
import com.jianli.jd.parser.JdImportEntry;
import com.jianli.jd.parser.JdImportParser;
import com.jianli.jd.repository.JobDescriptionRepository;
import com.jianli.resume.service.ResumeTextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /** JD 文本文件导入上限，避免误传超大文件。 */
    private static final long MAX_IMPORT_FILE_SIZE = 2 * 1024 * 1024;

    /** 单次文件最多导入的岗位数量。 */
    private static final int MAX_IMPORT_ITEMS = 200;

    /** 单页最大条数，避免列表接口一次拉出全部 JD。 */
    private static final int MAX_PAGE_SIZE = 50;

    private final JobDescriptionRepository repository;
    private final RuleJdParser parser;
    private final ResumeTextNormalizer normalizer;
    private final ObjectMapper objectMapper;
    private final JdImportParser importParser;

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
        JobDescription jd = buildJdEntity(userId, req.title(), req.company(), content);

        jd = repository.save(jd);
        log.info("保存 JD, userId={}, jdId={}, 技能数={}",
                userId, jd.getId(), readParsed(jd).requiredSkills().size());

        return JdVO.of(jd, readParsed(jd));
    }

    /**
     * 从 Markdown/TXT 文件批量导入 JD。
     *
     * 去重键：用户 + 岗位名 + 公司 + 归一化正文哈希。
     * 不只用标题去重，因为不同公司可能发布同名岗位；
     * 也不只用正文去重，因为同一份 JD 可能属于不同公司的招聘记录。
     */
    @Transactional
    public ImportJdResult importFile(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, null, "请选择要导入的文件");
        }
        if (file.getSize() > MAX_IMPORT_FILE_SIZE) {
            throw new BizException(ErrorCode.PAYLOAD_TOO_LARGE, null,
                    "JD 文件不能超过 2MB");
        }

        String fileName = displayFileName(file.getOriginalFilename());
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".md") && !lowerName.endsWith(".txt")) {
            throw new BizException(ErrorCode.JD_IMPORT_FORMAT_INVALID, null,
                    "仅支持 .md 和 .txt 文件");
        }

        String raw;
        try {
            raw = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取 JD 导入文件失败, userId={}, fileName={}", userId, fileName, e);
            throw new BizException(ErrorCode.PARAM_INVALID, null, "文件读取失败，请重试");
        }

        List<JdImportEntry> entries = importParser.parse(raw);
        if (entries.size() > MAX_IMPORT_ITEMS) {
            throw new BizException(ErrorCode.JD_IMPORT_FORMAT_INVALID, null,
                    "单个文件最多导入 " + MAX_IMPORT_ITEMS + " 个岗位");
        }

        Map<ImportFingerprint, JobDescription> existing = new LinkedHashMap<>();
        for (JobDescription jd : repository.findByUserId(userId)) {
            existing.putIfAbsent(fingerprint(jd.getTitle(), jd.getCompany(), jd.getContentHash()), jd);
        }

        int success = 0;
        int duplicate = 0;
        int failed = 0;
        List<ImportJdItemResult> items = new ArrayList<>(entries.size());

        for (JdImportEntry entry : entries) {
            String title = entry.title() == null ? "" : entry.title().trim();
            String company = entry.company() == null || entry.company().isBlank()
                    ? null : entry.company().trim();
            String displayTitle = title.isBlank() ? "未命名岗位" : title;

            try {
                if (title.isBlank()) {
                    throw new BizException(ErrorCode.JD_IMPORT_FORMAT_INVALID, null,
                            "缺少岗位标题，请写成“## 岗位名称”");
                }
                if (title.length() > 100) {
                    throw new BizException(ErrorCode.JD_IMPORT_FORMAT_INVALID, null,
                            "岗位名称超过 100 个字符");
                }
                if (company != null && company.length() > 100) {
                    throw new BizException(ErrorCode.JD_IMPORT_FORMAT_INVALID, null,
                            "公司名超过 100 个字符");
                }

                String content = normalizeChecked(entry.content());
                String contentHash = normalizer.sha256(content);
                ImportFingerprint key = fingerprint(title, company, contentHash);
                JobDescription old = existing.get(key);

                if (old != null) {
                    duplicate++;
                    items.add(new ImportJdItemResult(entry.index(), displayTitle, company,
                            "DUPLICATE", "已存在相同岗位，已跳过", old.getId()));
                    continue;
                }

                JobDescription jd = buildJdEntity(userId, title, company, content);
                jd.setContentHash(contentHash);
                jd = repository.save(jd);
                existing.put(key, jd);
                success++;
                items.add(new ImportJdItemResult(entry.index(), displayTitle, company,
                        "IMPORTED", "已导入并完成技能解析", jd.getId()));
            } catch (BizException e) {
                failed++;
                items.add(new ImportJdItemResult(entry.index(), displayTitle, company,
                        "FAILED", e.getMessage(), null));
            } catch (Exception e) {
                log.error("导入单条 JD 失败, userId={}, index={}, title={}",
                        userId, entry.index(), displayTitle, e);
                failed++;
                items.add(new ImportJdItemResult(entry.index(), displayTitle, company,
                        "FAILED", "保存失败，请检查该条内容", null));
            }
        }

        log.info("JD 文件导入完成, userId={}, fileName={}, total={}, success={}, duplicate={}, failed={}",
                userId, fileName, entries.size(), success, duplicate, failed);

        return new ImportJdResult(fileName, entries.size(), success, duplicate, failed, items);
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
    public PageResult<JdVO> list(Long userId, String keyword, int page, int size) {
        String kw = keyword == null ? "" : keyword.trim();
        int safePage = Math.max(page - 1, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        var pageable = PageRequest.of(safePage, safeSize);
        var result = kw.isEmpty()
                ? repository.findByUserIdOrderByUpdatedAtDesc(userId, pageable)
                : repository.searchByUserId(userId, kw, pageable);

        return PageResult.of(result.map(jd -> JdVO.of(jd, readParsed(jd))));
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

    /** 新建普通 JD 和导入 JD 共用的实体构造逻辑。 */
    private JobDescription buildJdEntity(Long userId, String title, String company, String content) {
        JobDescription jd = new JobDescription();
        jd.setUserId(userId);
        jd.setTitle(title.trim());
        jd.setCompany(company == null || company.isBlank() ? null : company.trim());
        jd.setContent(content);
        jd.setContentHash(normalizer.sha256(content));
        applyParsed(jd);
        return jd;
    }

    private ImportFingerprint fingerprint(String title, String company, String contentHash) {
        return new ImportFingerprint(
                title == null ? "" : title.trim().toLowerCase(Locale.ROOT),
                company == null ? "" : company.trim().toLowerCase(Locale.ROOT),
                contentHash == null ? "" : contentHash);
    }

    private String displayFileName(String originalName) {
        String name = originalName == null ? "" : originalName.trim();
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.isBlank() ? "未命名文件" : name;
    }

    private String normalizeChecked(String raw) {
        String content = normalizer.normalize(raw);
        if (normalizer.charCount(content) < MIN_CHARS) {
            throw new BizException(ErrorCode.JD_CONTENT_INVALID);
        }
        return content;
    }

    private record ImportFingerprint(String title, String company, String contentHash) {}
}
