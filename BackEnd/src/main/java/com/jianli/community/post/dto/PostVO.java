package com.jianli.community.post.dto;

import com.jianli.analysis.dto.AnalysisReportVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  帖子详情。列表也用这个（只是不带简历正文）。
 *
 *  ★ @JsonInclude(ALWAYS)：强制输出 null 字段。
 *
 *    全局配置了 default-property-inclusion: non_null，
 *    会把 myVote=null（没投票）、resumeContent=null（列表不带正文）过滤掉。
 *
 *    ★ 但前端需要区分"字段不存在"和"字段是 null"：
 *        myVote 为 null → 我没投过票
 *        myVote 不存在  → 前端不知道你有没有投过
 *
 *    ★ 所以对这个 VO 单独覆盖，保证字段结构稳定。
 *      "接口返回的字段集合应该是固定的"——
 *      这是前端能写可靠代码的前提。
 * ═══════════════════════════════════════════════════════════════════════
 */
@com.fasterxml.jackson.annotation.JsonInclude(
        com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record PostVO(

        Long id,
        String title,
        String content,

        /**
         * 作者信息。
         *
         * ★ 匿名时：{anonymous: true, userId: null, nickname: "匿名用户"}
         *   非匿名：{anonymous: false, userId: 7, nickname: "Alice"}
         *
         * ★ 注意匿名时【userId 为 null】——
         *   不是"传了但前端不显示"，是根本不传。
         *   前端拿不到就没法泄露，这是最彻底的脱敏。
         */
        AuthorVO author,

        /** 简历正文快照（列表接口不返回，节省流量） */
        String resumeContent,

        /** 报告快照（列表不返回） */
        ReportSnapshot report,

        /** 冗余字段，列表展示用 */
        BigDecimal overallScore,
        String targetPosition,
        Integer yearsOfExperience,

        Integer upCount,
        Integer downCount,
        Integer commentCount,
        Integer viewCount,

        /** 当前用户投的票：UP / DOWN / null */
        String myVote,

        /** 当前用户是不是作者（决定是否显示删除按钮、能否采纳） */
        Boolean isOwner,

        Boolean isAnonymous,
        String status,
        LocalDateTime createdAt
) {

    /**
     * 作者信息。
     *
     * ★ 匿名时 userId 和 nickname 都是 null / "匿名用户"。
     */
    public record AuthorVO(Boolean anonymous, Long userId, String nickname) {

        public static AuthorVO anonymousAuthor() {
            return new AuthorVO(true, null, "匿名用户");
        }

        public static AuthorVO real(Long userId, String nickname) {
            return new AuthorVO(false, userId, nickname);
        }
    }

    /**
     * 报告快照（从 JSON 反序列化而来）。
     *
     * ★ 为什么不用 AnalysisReportVO？
     *   因为快照是发布当时的形态，可能缺少后来新增的字段。
     *   用一个独立的、字段更少的 record 表示，
     *   不会因为 VO 改结构而反序列化失败。
     *
     * ★ 这是"快照类型要独立于实时类型"的原则。
     */
    public record ReportSnapshot(
            Double overallScore,
            String grade,
            List<AnalysisReportVO.DimensionVO> dimensions,
            List<AnalysisReportVO.KeywordVO> hits,
            List<AnalysisReportVO.KeywordVO> partials,
            List<AnalysisReportVO.KeywordVO> missing,
            List<AnalysisReportVO.SuggestionVO> suggestions
    ) {}
}
