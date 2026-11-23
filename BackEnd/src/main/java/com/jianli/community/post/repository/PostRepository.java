package com.jianli.community.post.repository;

import com.jianli.community.post.entity.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个接口是什么】
 *  帖子的数据访问。
 * ═══════════════════════════════════════════════════════════════════════
 */
public interface PostRepository extends JpaRepository<Post, Long> {

    /** 同一份报告只能发一次帖（防重复发布） */
    boolean existsByReportId(Long reportId);

    Optional<Post> findByIdAndStatus(Long id, String status);

    /** 我的帖子（含匿名帖 —— 自己看得见自己的；已删除的不再展示） */
    Page<Post> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status, Pageable pageable);

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【社区列表】支持筛选 + 搜索 + 排序
     * ═══════════════════════════════════════════════════════════════
     *
     * 参数全部可选（传 null 表示不过滤）。
     *
     * 【★ 为什么用 `:param is null or ...` 这种写法？】
     *
     *  这是"一个方法覆盖所有组合"的技巧：
     *      :position is null          → 不按岗位过滤
     *      :expMin is null            → 不限最低年限
     *      :kw is null                → 不搜索
     *
     *  ★ 避免写 8 个 @Query 方法覆盖所有组合。
     *    代价是优化器可能不走最优索引 ——
     *    但社区帖子是万级数据量，完全可接受。
     *
     * 【★ 为什么用 coalesce 包一层？】
     *    SQL 里 NULL LIKE 'x' 结果是 NULL（不是 false），
     *    会导致"岗位为空的帖子"被意外排除。
     *    coalesce(target_position, '') 把 null 变成空串。
     *
     * 【★ 排序】
     *    按传入的 pageable 的 sort 决定。
     *    Service 层会根据 sort 参数构造：
     *      "hot" → Sort.by(DESC, "hotScore", "createdAt")
     *      "new" → Sort.by(DESC, "createdAt")
     */
    @Query("""
            select p from Post p
            where p.status = 'PUBLISHED'
              and (:position is null or p.targetPosition = :position)
              and (:expMin is null or p.yearsOfExperience >= :expMin)
              and (:expMax is null or p.yearsOfExperience <= :expMax)
              and (:kw is null
                   or lower(p.title) like lower(concat('%', :kw, '%'))
                   or lower(coalesce(p.content, '')) like lower(concat('%', :kw, '%')))
            """)
    Page<Post> search(@Param("position") String position,
                      @Param("expMin") Integer expMin,
                      @Param("expMax") Integer expMax,
                      @Param("kw") String keyword,
                      Pageable pageable);

    /** 统计某个用户发过多少帖 */
    long countByUserIdAndStatus(Long userId, String status);

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【查最近 N 天的帖子】热度重建任务用
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 为什么按 createdAt 倒序，而不是按 hotScore？
     *   重建的目的是"重算衰减"，
     *   越新的帖子衰减越快、越需要重算。
     *   而且倒序 + LIMIT 能保证"超出上限时保留最新的"。
     *
     * ★ 索引：idx_post_status_time (status, created_at DESC)
     *   正好覆盖 WHERE status=? AND created_at > ? ORDER BY created_at DESC
     */
    List<Post> findByStatusAndCreatedAtAfterOrderByCreatedAtDesc(String status, java.time.LocalDateTime after);
}
