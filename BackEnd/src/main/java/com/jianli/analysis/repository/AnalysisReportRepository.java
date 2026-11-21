package com.jianli.analysis.repository;

import com.jianli.analysis.entity.AnalysisReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个接口是什么】
 *  分析报告的数据访问。
 * ═══════════════════════════════════════════════════════════════════════
 */
public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, Long> {

    /** 越权校验在 SQL 里：只查自己的报告 */
    Optional<AnalysisReport> findByIdAndUserId(Long id, Long userId);

    /** 我的分析历史，按时间倒序 */
    Page<AnalysisReport> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * 按简历版本查历史。
     *
     * ★ 用在哪：简历详情页要显示"这份简历分析过几次、分数分别是多少"。
     */
    Page<AnalysisReport> findByResumeVersionIdAndUserIdOrderByCreatedAtDesc(
            Long resumeVersionId, Long userId, Pageable pageable);

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【缓存命中查询】
     * ═══════════════════════════════════════════════════════════════
     *
     * 同 cacheKey 的已完成报告，取最新的一条。
     *
     * ★ 什么时候用？
     *   用户对同一份简历版本 + 同一个 JD 重复点"分析"时，
     *   直接返回上次的结果，不重复计算。
     *
     * ★ cacheKey 包含哪些因素？（见 AnalysisService）
     *   versionId | jdId | scoringVersion | aiEnabled
     *   ★ 必须含后两个 —— 否则算法升级后还返回旧分数。
     */
    Optional<AnalysisReport> findFirstByCacheKeyAndStatusInOrderByIdDesc(
            String cacheKey, Collection<String> statuses);

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【按简历查最新分数】★ 列表页用
     * ═══════════════════════════════════════════════════════════════
     *
     * 一次查出多个简历版本的最新报告，避免 N+1。
     *
     * ★ 为什么要用 JPQL 而不是方法名？
     *   需要"每个 versionId 取最新一条"，方法名表达不了。
     *   这里用子查询取 max(id)。
     *
     *   ★ 更严谨的写法是用窗口函数（MySQL 8 支持 ROW_NUMBER），
     *     但 JPA 里写窗口函数要原生 SQL，本项目数据量小，
     *     子查询足够。
     */
    @Query("""
            select r from AnalysisReport r
            where r.userId = :userId
              and r.resumeVersionId in :versionIds
              and r.status in ('SUCCESS', 'PARTIAL')
              and r.id = (
                  select max(r2.id) from AnalysisReport r2
                  where r2.resumeVersionId = r.resumeVersionId
                    and r2.userId = :userId
                    and r2.status in ('SUCCESS', 'PARTIAL')
              )
            """)
    List<AnalysisReport> findLatestByVersionIds(@Param("userId") Long userId,
                                                @Param("versionIds") Collection<Long> versionIds);
}
