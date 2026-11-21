package com.jianli.community.comment.service;

import com.jianli.common.BizException;
import com.jianli.common.ErrorCode;
import com.jianli.community.comment.dto.CommentVO;
import com.jianli.community.comment.entity.Comment;
import com.jianli.community.comment.repository.CommentRepository;
import com.jianli.community.post.entity.Post;
import com.jianli.community.post.repository.PostRepository;
import com.jianli.community.vote.repository.VoteRepository;
import com.jianli.privacy.service.PrivacyReviewService;
import com.jianli.user.entity.AppUser;
import com.jianli.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  评论业务：发表、列表（两级树）、删除。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 评论也要过个人信息审核】
 *
 *  评论区同样可能写手机号、微信号。
 *  所以发表前走同一个检测器（scene=COMMENT_PUBLISH）。
 *
 *  ★ 这是"审核能力可复用"的体现 —— 不因为场景不同就重写一套规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private static final List<String> SECTIONS = List.of(
            "SUMMARY", "SKILLS", "EXPERIENCE", "PROJECT", "EDUCATION", "KEYWORDS", "OTHER");

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final VoteRepository voteRepository;
    private final UserRepository userRepository;
    private final PrivacyReviewService privacyReviewService;
    private final com.jianli.community.hot.HotScoreService hotScoreService;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【发表评论 / 回复】
     * ═══════════════════════════════════════════════════════════════
     *
     * @throws BizException COMMENT_DEPTH_EXCEEDED —— 想回复一条回复（三级）
     * @throws BizException POST_PRIVACY_REJECTED   —— 评论里含个人信息
     */
    @Transactional
    public CommentVO create(Long userId, Long postId, Long parentId,
                            String section, String content) {

        Post post = postRepository.findByIdAndStatus(postId, "PUBLISHED")
                .orElseThrow(() -> new BizException(ErrorCode.POST_NOT_VISIBLE));

        Long rootId = null;

        if (parentId != null) {
            Comment parent = commentRepository.findByIdAndStatus(parentId, "PUBLISHED")
                    .orElseThrow(() -> new BizException(ErrorCode.COMMENT_NOT_FOUND));

            if (!parent.getPostId().equals(postId)) {
                throw new BizException(ErrorCode.PARAM_INVALID, null, "评论不属于该帖子");
            }

            /*
             * ★ 只支持两级：
             *   父评论本身已经是"回复"（parentId 不为 null）→ 拒绝。
             */
            if (parent.getParentId() != null) {
                throw new BizException(ErrorCode.COMMENT_DEPTH_EXCEEDED);
            }

            rootId = parent.getId();
        }

        // ───── 个人信息审核 ─────
        var outcome = privacyReviewService.checkAndRecord(userId, "COMMENT_PUBLISH", content);

        if (!outcome.passed()) {
            throw new BizException(ErrorCode.POST_PRIVACY_REJECTED,
                    new com.jianli.community.post.dto.CreatePostRequest.PrivacyRejected(
                            outcome.review().getId(),
                            "COMMENT_PUBLISH",
                            com.jianli.privacy.PrivacyDetector.RULE_VERSION,
                            outcome.hits()));
        }

        // ───── 保存 ─────
        Comment c = new Comment();
        c.setPostId(postId);
        c.setUserId(userId);
        c.setParentId(parentId);
        c.setRootId(rootId);
        c.setSection(normalizeSection(section));
        c.setContent(content.trim());
        c.setStatus("PUBLISHED");

        c = commentRepository.save(c);

        // 帖子的评论计数 +1
        post.setCommentCount(post.getCommentCount() + 1);
        postRepository.save(post);

        /*
         * ★ 评论会显著影响热度（权重是赞的 2 倍）——
         *   因为写评论的成本远高于点赞。
         *   所以评论后必须重算。
         */
        double hot = hotScoreService.compute(post);
        post.setHotScore(hot);
        postRepository.save(post);
        hotScoreService.updateZset(post.getId(), hot);

        String nickname = userRepository.findById(userId)
                .map(AppUser::getNickname).orElse("用户");

        return toVO(c, nickname, userId, false, Set.of(), post.getUserId());
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【评论列表】两级树
     * ═══════════════════════════════════════════════════════════════
     *
     * @param section 可选。只看某个板块的顶层评论
     * @param sort    "useful" 有用优先 / "newest" 最新
     */
    @Transactional(readOnly = true)
    public List<CommentVO> list(Long postId, String section, String sort, Long currentUserId) {

        Post post = postRepository.findByIdAndStatus(postId, "PUBLISHED")
                .orElseThrow(() -> new BizException(ErrorCode.POST_NOT_VISIBLE));

        // ★ 一次取全部（顶层 + 回复），内存分组
        List<Comment> all = commentRepository
                .findByPostIdAndStatusOrderByCreatedAtAsc(postId, "PUBLISHED");

        // 顶层评论，按板块过滤
        List<Comment> tops = all.stream()
                .filter(c -> c.getParentId() == null)
                .filter(c -> section == null || section.isBlank()
                        || normalizeSection(section).equals(c.getSection()))
                .collect(Collectors.toList());

        // 回复，按 rootId 分组
        Map<Long, List<Comment>> repliesByRoot = all.stream()
                .filter(c -> c.getRootId() != null)
                .collect(Collectors.groupingBy(Comment::getRootId));

        // 排序：有用优先 / 最新
        if ("useful".equals(sort)) {
            tops.sort((a, b) -> {
                int cmp = Integer.compare(b.getUsefulCount(), a.getUsefulCount());
                return cmp != 0 ? cmp : b.getCreatedAt().compareTo(a.getCreatedAt());
            });
        } else {
            tops.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        }

        // 批量查作者昵称 + 我的投票（避免 N+1）
        Set<Long> userIds = all.stream().map(Comment::getUserId).collect(Collectors.toSet());
        Map<Long, String> nicknames = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getNickname));

        Map<Long, Set<String>> myVotes = loadMyVotes(currentUserId, all);

        List<CommentVO> result = new ArrayList<>();
        for (Comment top : tops) {
            List<CommentVO> replies = repliesByRoot.getOrDefault(top.getId(), List.of())
                    .stream()
                    .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                    .map(r -> toVO(r, nicknames.get(r.getUserId()), currentUserId, false,
                            myVotes.getOrDefault(r.getId(), Set.of()), post.getUserId()))
                    .toList();

            result.add(toVO(top, nicknames.get(top.getUserId()), currentUserId, true,
                    myVotes.getOrDefault(top.getId(), Set.of()), post.getUserId(), replies));
        }

        return result;
    }

    /**
     * 删除评论（软删除）。
     *
     * ★ 谁可以删？
     *   1. 评论作者（删自己的）
     *   2. 帖子作者（删自己帖子下的任何评论，便于治理）
     *
     * ★ 为什么给帖子作者这个权限？
     *   没有管理员后台的情况下，这是唯一的内容治理手段。
     *   别人在你帖子下发垃圾或泄露信息，你要能删。
     */
    @Transactional
    public void delete(Long userId, Long commentId) {
        Comment c = commentRepository.findByIdAndStatus(commentId, "PUBLISHED")
                .orElseThrow(() -> new BizException(ErrorCode.COMMENT_NOT_FOUND));

        Post post = postRepository.findById(c.getPostId()).orElse(null);

        boolean isCommentAuthor = c.getUserId().equals(userId);
        boolean isPostAuthor = post != null && post.getUserId().equals(userId);

        if (!isCommentAuthor && !isPostAuthor) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        c.setStatus("DELETED");

        if (post != null) {
            post.setCommentCount(Math.max(0, post.getCommentCount() - 1));
            postRepository.save(post);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════════

    /** 批量查"我对这批评论的投票"（可能同时有 UP 和 USEFUL） */
    private Map<Long, Set<String>> loadMyVotes(Long userId, List<Comment> comments) {
        if (userId == null || comments.isEmpty()) return Map.of();

        List<Long> ids = comments.stream().map(Comment::getId).toList();

        return voteRepository.findByUserIdAndTargetTypeAndTargetIdIn(userId, "COMMENT", ids)
                .stream()
                .collect(Collectors.groupingBy(
                        v -> v.getTargetId(),
                        Collectors.mapping(v -> v.getVoteType(), Collectors.toSet())));
    }

    private CommentVO toVO(Comment c, String nickname, Long currentUserId,
                           boolean withReplies, Set<String> myVoteTypes, Long postAuthorId) {
        return toVO(c, nickname, currentUserId, withReplies, myVoteTypes, postAuthorId, List.of());
    }

    private CommentVO toVO(Comment c, String nickname, Long currentUserId,
                           boolean withReplies, Set<String> myVoteTypes,
                           Long postAuthorId, List<CommentVO> replies) {

        boolean isOwner = currentUserId != null && currentUserId.equals(c.getUserId());
        boolean canDelete = isOwner
                || (currentUserId != null && currentUserId.equals(postAuthorId));

        return new CommentVO(
                c.getId(),
                c.getParentId(),
                c.getRootId(),
                c.getSection(),
                CommentVO.sectionLabelOf(c.getSection()),
                c.getContent(),
                new CommentVO.AuthorVO(c.getUserId(), nickname == null ? "用户" : nickname),
                c.getLikeCount(),
                c.getUsefulCount(),
                myVoteTypes.contains("UP"),
                myVoteTypes.contains("USEFUL"),
                c.getAdopted(),
                c.getAdoptedVersionId(),
                canDelete,
                isOwner,
                c.getCreatedAt(),
                withReplies ? replies : null
        );
    }

    private String normalizeSection(String s) {
        if (s == null) return "OTHER";
        String u = s.trim().toUpperCase();
        return SECTIONS.contains(u) ? u : "OTHER";
    }
}
