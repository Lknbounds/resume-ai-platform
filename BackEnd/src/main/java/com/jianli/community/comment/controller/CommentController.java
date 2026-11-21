package com.jianli.community.comment.controller;

import com.jianli.common.ApiResponse;
import com.jianli.common.CurrentUserId;
import com.jianli.community.comment.dto.CommentVO;
import com.jianli.community.comment.service.CommentService;
import com.jianli.community.vote.repository.VoteRepository;
import com.jianli.community.vote.entity.Vote;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  评论接口。
 *
 *      GET    /api/posts/{id}/comments   列表（公开）
 *      POST   /api/posts/{id}/comments   发表/回复（★ 审核）
 *      DELETE /api/comments/{id}         删除
 *      POST   /api/comments/{id}/votes   点赞/有用
 * ═══════════════════════════════════════════════════════════════════════
 */
@RestController
@RequestMapping("/api")
@Tag(name = "社区-评论")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final VoteRepository voteRepository;
    private final com.jianli.community.comment.repository.CommentRepository commentRepository;
    private final com.jianli.community.post.repository.PostRepository postRepository;

    /**
     * 评论列表（两级树）。
     *
     * @param section 可选。只看某个板块（SKILLS/PROJECT/…）
     * @param sort    useful（有用优先）/ newest（最新）
     */
    @GetMapping("/posts/{postId}/comments")
    @Operation(summary = "评论列表", description = "两级结构，可按板块筛选")
    public ApiResponse<List<CommentVO>> list(
            @CurrentUserId(required = false) Long userId,
            @PathVariable Long postId,
            @RequestParam(required = false) String section,
            @RequestParam(defaultValue = "newest") String sort) {

        return ApiResponse.ok(commentService.list(postId, section, sort, userId));
    }

    /**
     * 发表评论或回复。
     *
     * 请求：
     *      {"parentId": null, "section": "PROJECT", "content": "项目最好加上 QPS"}
     *      {"parentId": 88, "content": "同意"}          ← 回复
     *
     * 错误：
     *      400 + 43005  想回复一条回复（三级）
     *      ★ 422 + 43002  评论里含个人信息
     */
    @PostMapping("/posts/{postId}/comments")
    @Operation(summary = "发表评论", description = "parentId 为空是顶层评论；回复的父评论必须也是顶层")
    public ResponseEntity<ApiResponse<CommentVO>> create(
            @CurrentUserId Long userId,
            @PathVariable Long postId,
            @RequestBody @Valid CreateCommentRequest req) {

        CommentVO vo = commentService.create(
                userId, postId, req.parentId(), req.section(), req.content());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(vo));
    }

    @DeleteMapping("/comments/{id}")
    @Operation(summary = "删除评论", description = "评论作者或帖子作者可删")
    public ApiResponse<Void> delete(@CurrentUserId Long userId, @PathVariable Long id) {
        commentService.delete(userId, id);
        return ApiResponse.ok();
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【评论投票】POST /api/comments/{id}/votes
     * ═══════════════════════════════════════════════════════════════
     *
     * 请求：{"voteType": "UP"}  或  {"voteType": "USEFUL"}
     *
     * 【★ 为什么评论有两种票，而帖子只有一种？】
     *
     *  帖子：赞/踩表达"内容好不好"—— 互斥
     *  评论：赞 = 我认同；有用 = 这条建议可执行 —— 两个维度，可同时存在
     *
     *  ★ 在简历互评场景里，"有用"比"赞"更有信息量：
     *    一条建议让你真的改了简历，才叫"有用"。
     *    ★ 这也是为什么作者采纳建议时，能参考 usefulCount 排序。
     */
    @PostMapping("/comments/{id}/votes")
    @Operation(summary = "评论投票", description = "UP（点赞）/ USEFUL（有用），两者独立")
    public ApiResponse<CommentVoteResult> voteComment(
            @CurrentUserId Long userId,
            @PathVariable Long id,
            @RequestBody VoteRequest req) {

        return ApiResponse.ok(voteComment(userId, id, req.voteType()));
    }

    /**
     * 评论投票的实现。
     *
     * ★ 为什么放在 Controller 而不在 VoteService？
     *   本来应该放 VoteService。这里为了减少文件数先放这，
     *   如果要重构，把它移到 VoteService 里更合适。
     */
    private CommentVoteResult voteComment(Long userId, Long commentId, String voteType) {

        var c = commentRepository.findByIdAndStatus(commentId, "PUBLISHED")
                .orElseThrow(() -> new com.jianli.common.BizException(
                        com.jianli.common.ErrorCode.COMMENT_NOT_FOUND));

        if (c.getUserId().equals(userId)) {
            throw new com.jianli.common.BizException(
                    com.jianli.common.ErrorCode.SELF_VOTE_FORBIDDEN);
        }

        String type = voteType == null ? "" : voteType.trim().toUpperCase();
        if (!List.of("UP", "USEFUL").contains(type)) {
            throw new com.jianli.common.BizException(
                    com.jianli.common.ErrorCode.PARAM_INVALID, null, "投票类型不合法");
        }

        Optional<Vote> existing = voteRepository
                .findByUserIdAndTargetTypeAndTargetIdAndVoteType(userId, "COMMENT", commentId, type);

        if (existing.isPresent()) {
            // 已投同类型 → 取消
            voteRepository.delete(existing.get());
            adjust(c, type, -1);
        } else {
            Vote v = new Vote();
            v.setUserId(userId);
            v.setTargetType("COMMENT");
            v.setTargetId(commentId);
            v.setVoteType(type);
            voteRepository.save(v);
            adjust(c, type, +1);
        }

        commentRepository.save(c);

        // 查一下当前用户对这条评论的两种票
        boolean liked = voteRepository
                .findByUserIdAndTargetTypeAndTargetIdAndVoteType(userId, "COMMENT", commentId, "UP")
                .isPresent();
        boolean useful = voteRepository
                .findByUserIdAndTargetTypeAndTargetIdAndVoteType(userId, "COMMENT", commentId, "USEFUL")
                .isPresent();

        return new CommentVoteResult(commentId, c.getLikeCount(), c.getUsefulCount(), liked, useful);
    }

    private void adjust(com.jianli.community.comment.entity.Comment c, String type, int delta) {
        if ("UP".equals(type)) {
            c.setLikeCount(Math.max(0, c.getLikeCount() + delta));
        } else {
            c.setUsefulCount(Math.max(0, c.getUsefulCount() + delta));
        }
    }

    public record CreateCommentRequest(
            Long parentId,
            String section,
            @NotBlank(message = "评论内容不能为空")
            @Size(max = 1000, message = "评论最长 1000 字")
            String content) {}

    public record VoteRequest(String voteType) {}

    public record CommentVoteResult(Long commentId, Integer likeCount,
                                    Integer usefulCount, Boolean myLiked, Boolean myUseful) {}
}
