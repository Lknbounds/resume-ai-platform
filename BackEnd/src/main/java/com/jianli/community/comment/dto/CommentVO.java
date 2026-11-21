package com.jianli.community.comment.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  评论。顶层评论带 replies 列表（只有两级）。
 * ═══════════════════════════════════════════════════════════════════════
 */
public record CommentVO(
        Long id,
        Long parentId,
        Long rootId,
        String section,

        /** 板块中文名（前端直接显示，不用自己映射） */
        String sectionLabel,

        String content,
        AuthorVO author,

        Integer likeCount,
        Integer usefulCount,

        /** 我点赞了吗 */
        Boolean myLiked,

        /** 我投"有用"了吗 */
        Boolean myUseful,

        /** 是否已被采纳 */
        Boolean adopted,
        Long adoptedVersionId,

        /** 我能不能删（评论作者 or 帖子作者） */
        Boolean canDelete,

        /** 这条评论是不是我发的 */
        Boolean isOwner,

        LocalDateTime createdAt,

        /** 回复列表。只有顶层评论有值 */
        List<CommentVO> replies
) {

    public record AuthorVO(Long userId, String nickname) {}

    /** 板块 → 中文 */
    public static String sectionLabelOf(String section) {
        return switch (section == null ? "OTHER" : section) {
            case "SUMMARY" -> "自我评价";
            case "SKILLS" -> "技能";
            case "EXPERIENCE" -> "工作经历";
            case "PROJECT" -> "项目";
            case "EDUCATION" -> "教育";
            case "KEYWORDS" -> "关键词";
            default -> "其他";
        };
    }
}
