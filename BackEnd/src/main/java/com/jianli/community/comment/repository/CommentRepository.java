package com.jianli.community.comment.repository;

import com.jianli.community.comment.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个接口是什么】
 *  评论的数据访问。
 * ═══════════════════════════════════════════════════════════════════════
 */
public interface CommentRepository extends JpaRepository<Comment, Long> {

    Optional<Comment> findByIdAndStatus(Long id, String status);

    /**
     * ★ 一次查出整个帖子的评论（顶层 + 回复）。
     *
     * ★ 为什么不分两次查？
     *   一个帖子的评论通常 < 200 条，一次查完在内存里分组更简单，
     *   也避免"先查顶层（分页）再对每条查回复"的 N+1。
     */
    List<Comment> findByPostIdAndStatusOrderByCreatedAtAsc(Long postId, String status);

    long countByPostIdAndStatus(Long postId, String status);
}
