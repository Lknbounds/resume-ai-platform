package com.jianli.jd.repository;

import com.jianli.jd.entity.JobDescription;
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
 *  JD 表的数据访问。没有实现类，方法名即查询。
 * ═══════════════════════════════════════════════════════════════════════
 */
public interface JobDescriptionRepository extends JpaRepository<JobDescription, Long> {

    /**
     * 我的 JD 列表，按更新时间倒序。
     *
     * ★ 为什么按 updatedAt 而不是 createdAt？
     *   用户可能修改 JD（改了岗位要求），改过的应该排前面。
     */
    Page<JobDescription> findByUserIdOrderByUpdatedAtDesc(Long userId, Pageable pageable);

    /**
     * ★ 越权校验在这里：只查自己的。
     *
     * 返回 Optional.empty 而不是抛权限异常 ——
     * 不泄露"这个 id 是否存在"。
     */
    Optional<JobDescription> findByIdAndUserId(Long id, Long userId);

    /**
     * ★ 搜索 JD：按岗位名或公司名模糊匹配。理由同 ResumeRepository.searchByUserId。
     */
    @org.springframework.data.jpa.repository.Query("""
            select j from JobDescription j
            where j.userId = :userId
              and (lower(j.title) like lower(concat('%', :kw, '%'))
                   or lower(coalesce(j.company, '')) like lower(concat('%', :kw, '%')))
            order by j.updatedAt desc
            """)
    Page<JobDescription> searchByUserId(@Param("userId") Long userId,
                                        @Param("kw") String keyword,
                                        Pageable pageable);

    /** 导入时一次性载入当前用户的 JD，用于批量去重。 */
    List<JobDescription> findByUserId(Long userId);

    /** 统计用户存了多少个 JD */
    long countByUserId(Long userId);
}
