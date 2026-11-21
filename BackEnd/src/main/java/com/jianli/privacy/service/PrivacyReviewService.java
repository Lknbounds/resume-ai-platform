package com.jianli.privacy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianli.privacy.PrivacyDetector;
import com.jianli.privacy.PrivacyHit;
import com.jianli.privacy.entity.PrivacyReview;
import com.jianli.privacy.repository.PrivacyReviewRepository;
import com.jianli.resume.service.ResumeTextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  审核的"编排"层：调检测器 + 落库。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么用 REQUIRES_NEW？这是本类最重要的设计】
 *
 *  发帖的流程：
 *      ① 校验报告归属
 *      ② 审核
 *      ③ 审核通过才插入帖子
 *
 *  ② 和 ③ 在同一个事务里。如果审核不通过抛异常：
 *      → 整个事务回滚
 *      → 审核记录（REJECTED）也一起没了
 *      → 审计留痕失败
 *
 *  ★ REQUIRES_NEW 让审核记录的写入【独立开一个事务】，
 *    外层回滚不影响它。这样"业务失败但审计成功"。
 *
 *  ★ 这是个很典型的场景：主流程要回滚，日志要保留。
 *    另一个常见例子是"操作日志表"——同理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrivacyReviewService {

    private final PrivacyDetector detector;
    private final PrivacyReviewRepository repository;
    private final ObjectMapper objectMapper;
    private final ResumeTextNormalizer normalizer;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【预检】只检测，不落库
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 用在哪：发布页的"检查一下"按钮。
     *   用户点发布前可以先查，有问题就地改，不用提交后被拒。
     *
     * ★ 为什么预检不落库？
     *   用户可能反复点"检查"，每次都记一条会污染审计数据。
     *   真正有意义的审计是"发布时的最终审核"。
     */
    public List<PrivacyHit> precheck(String content) {
        return detector.scan(content);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【检测 + 落库】★ REQUIRES_NEW 独立事务
     * ═══════════════════════════════════════════════════════════════
     *
     * @param userId  当前用户
     * @param scene   场景（POST_PUBLISH / COMMENT_PUBLISH）
     * @param content 要审核的文本（标题+正文+简历，拼好的）
     * @return 审核结果（含记录实体和命中列表）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReviewOutcome checkAndRecord(Long userId, String scene, String content) {

        long start = System.currentTimeMillis();
        List<PrivacyHit> hits = detector.scan(content);

        PrivacyReview review = new PrivacyReview();
        review.setUserId(userId);
        review.setScene(scene);
        review.setStatus(hits.isEmpty() ? "PASSED" : "REJECTED");
        review.setHitCount(hits.size());
        review.setHitDetail(toJson(hits));
        review.setRuleVersion(PrivacyDetector.RULE_VERSION);
        review.setAiChecked(false);
        review.setContentHash(normalizer.sha256(content));
        review.setDurationMs((int) (System.currentTimeMillis() - start));

        PrivacyReview saved = repository.save(review);

        log.debug("个人信息审核: userId={}, scene={}, status={}, 命中={}",
                userId, scene, saved.getStatus(), hits.size());

        return new ReviewOutcome(saved, hits);
    }

    /**
     * 命中列表 → JSON（★ 只存脱敏片段）。
     *
     * ★ 为什么不直接把 PrivacyHit 序列化？
     *   它是 record，序列化会带上 type 的枚举名（"PHONE"）——
     *   这其实可以。但显式构造 Map 能控制字段名，
     *   也避免将来给 PrivacyHit 加字段时意外写进库。
     */
    private String toJson(List<PrivacyHit> hits) {
        try {
            List<Map<String, Object>> list = hits.stream().map(h -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", h.type().name());
                m.put("start", h.start());
                m.put("end", h.end());
                m.put("maskedSnippet", h.maskedSnippet());
                return m;
            }).toList();

            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 审核结果：记录 + 命中列表 */
    public record ReviewOutcome(PrivacyReview review, List<PrivacyHit> hits) {

        public boolean passed() {
            return "PASSED".equals(review.getStatus());
        }
    }
}
