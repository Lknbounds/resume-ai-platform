package com.jianli.community.post.dto;

import com.jianli.privacy.PrivacyHit;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  发帖请求。
 *
 *      POST /api/posts
 *      {
 *        "reportId": 11,
 *        "title": "3 年 Java 后端，投了十几家没面试，求大佬看看",
 *        "content": "主要投中小厂，请帮忙看是不是简历问题",
 *        "resumeContent": "（已抹去手机号的简历正文）",
 *        "isAnonymous": true
 *      }
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么 resumeContent 要用户自己传，而不是后端从报告里取？】
 *
 *  1. 报告里的简历版本可能含手机号 —— 直接发布就泄露了
 *  2. 用户需要【自己抹掉】个人信息，然后提交抹过的版本
 *  3. 即使如此，后端还要再审一次（前端可以绕过）
 *
 *  ★ 两次检查：前端让用户主动处理，后端兜底。
 */
public record CreatePostRequest(

        /** 来源报告。★ 帖子内容从这份报告快照出来 */
        Long reportId,

        /**
         * 帖子标题。
         *
         * ★ 为什么要用户填，而不是自动生成"XX的简历分析"？
         *   标题决定了别人点不点进来。
         *   "求大佬看看"比"Java后端简历分析报告"更能吸引回复。
         */
        String title,

        /** 作者补充说明（选填）—— 比如"主要投中小厂"这种背景信息 */
        String content,

        /** 简历正文（用户已抹去敏感信息） */
        String resumeContent,

        /** 是否匿名展示 */
        Boolean isAnonymous
) {

    /**
     * 审核不通过时返回给前端的数据。
     *
     * ★ 为什么不直接返回 PrivacyHit 列表？
     *   前端还需要 reviewId 来关联"这次审核有记录"，
     *   以及 ruleVersion 用于显示"按规则 v1 检查"。
     */
    public record PrivacyRejected(
            Long reviewId,
            String scene,
            String ruleVersion,
            List<PrivacyHit> hits
    ) {}
}
