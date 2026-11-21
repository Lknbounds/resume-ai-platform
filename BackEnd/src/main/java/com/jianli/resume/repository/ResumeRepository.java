package com.jianli.resume.repository;

import com.jianli.resume.entity.Resume;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个接口是什么】
 *  简历表的数据访问。没有实现类，方法名即查询。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向】
 *
 *      ResumeService.create()
 *         └─ save(resume)                    → INSERT INTO resume (...)
 *
 *      ResumeService.list()
 *         └─ findByUserIdOrderByUpdatedAtDesc(userId, pageable)
 *              → SELECT * FROM resume WHERE user_id=? ORDER BY updated_at DESC LIMIT ?,?
 *
 *      ResumeService.detail()
 *         └─ findByIdAndUserId(id, userId)
 *              → SELECT * FROM resume WHERE id=? AND user_id=?    ← 越权校验在 SQL 里
 *
 *      ResumeService.addVersion()
 *         └─ findByIdForUpdate(id)
 *              → SELECT * FROM resume WHERE id=? FOR UPDATE      ← 行锁
 */
public interface ResumeRepository extends JpaRepository<Resume, Long> {

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【我的简历列表】按更新时间倒序
     * ═══════════════════════════════════════════════════════════════
     *
     * 生成 SQL：
     *      SELECT * FROM resume WHERE user_id = ?
     *      ORDER BY updated_at DESC LIMIT ? OFFSET ?
     *
     * ★ 为什么按 updated_at 而不是 created_at？
     *   用户最关心"最近在改哪份简历"。
     *   刚加了新版本的简历应该排到最前面 ——
     *   而加版本会触发 @PreUpdate 刷新 updated_at，正好满足。
     *
     * ★ 索引怎么走的？
     *   建表时有 idx_resume_user (user_id, updated_at DESC)。
     *   WHERE user_id = ? 用索引前半段，ORDER BY updated_at DESC 用后半段，
     *   所以不需要额外排序（filesort），效率好。
     *
     * ★ 参数 Pageable 是什么？
     *   Spring Data 的分页对象，包含页码、每页条数、排序规则。
     *   由 Service 构造：PageRequest.of(page - 1, size)
     *   ★ 注意页码要 -1，因为 Spring 是 0-based。
     *
     * ★ 返回值 Page<Resume>：
     *   里面既有当前页的数据（getContent），
     *   也有总数（getTotalElements），一次查询全拿到。
     */
    Page<Resume> findByUserIdOrderByUpdatedAtDesc(Long userId, Pageable pageable);

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【搜索简历】按标题或目标岗位模糊匹配
     * ═══════════════════════════════════════════════════════════════
     *
     * 生成 SQL：
     *      SELECT * FROM resume
     *      WHERE user_id = ?
     *        AND (title LIKE ? OR target_position LIKE ?)
     *      ORDER BY updated_at DESC
     *
     * 【★ 为什么用 LIKE '%kw%' 而不是全文索引？】
     *
     *  1. 用户的简历数通常 < 20 份，全表扫描毫无压力
     *  2. LIKE '%kw%' 无法走索引，但在这个数据量下无所谓
     *  3. 全文索引（或 Elasticsearch）要维护额外的索引结构，
     *     还要处理中文分词 —— 投入产出比太低
     *
     *  ★ 这是"知道正确解，但根据数据量选择更简单的实现"。
     *    如果将来简历数上千，再上全文索引。
     *
     * 【★ 为什么要 OR 两个字段？】
     *   用户可能记得标题（"社招版"），也可能记得岗位（"Java"）。
     *   两个都搜才符合直觉。
     */
    @Query("""
            select r from Resume r
            where r.userId = :userId
              and (lower(r.title) like lower(concat('%', :kw, '%'))
                   or lower(coalesce(r.targetPosition, '')) like lower(concat('%', :kw, '%')))
            order by r.updatedAt desc
            """)
    Page<Resume> searchByUserId(@Param("userId") Long userId,
                                @Param("kw") String keyword,
                                Pageable pageable);

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【按 id + 用户 id 查】★ 越权校验的核心
     * ═══════════════════════════════════════════════════════════════
     *
     * 生成 SQL：
     *      SELECT * FROM resume WHERE id = ? AND user_id = ?
     *
     * 【★★ 为什么必须带 user_id 条件？】
     *
     *  反例：如果只写 findById(id)，然后代码里判断：
     *      Resume r = repo.findById(id).orElseThrow();
     *      if (!r.getUserId().equals(userId)) throw FORBIDDEN;   ← 容易漏！
     *
     *  这样写有两个问题：
     *    1. 每个用到的地方都要记得写这个 if —— 漏一个就是越权漏洞
     *    2. 多读了一整行数据（其实根本不该让你读到）
     *
     *  ★ 用 findByIdAndUserId：
     *      - 条件在 SQL 里，物理上就读不到别人的数据
     *      - 返回 Optional.empty() → 统一当作"不存在"
     *      - 不可能忘记校验
     *
     *  【★ 为什么返回"不存在"而不是"无权限"？】
     *    如果返回 403 "无权访问"，等于告诉攻击者：
     *    "这个 id 是存在的，只是不属于你" —— 泄露了信息。
     *    统一返回 404 "不存在"，攻击者无法判断 id 是否有效。
     *
     *  ★ 这和登录失败统一返回"用户名或密码错误"是同一个思路。
     */
    Optional<Resume> findByIdAndUserId(Long id, Long userId);

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【加行锁查询】★ 解决"并发创建版本"的问题
     * ═══════════════════════════════════════════════════════════════
     *
     * 生成 SQL：
     *      SELECT * FROM resume WHERE id = ? FOR UPDATE
     *
     * 【★★ 为什么需要行锁？先看没有锁时的翻车现场】
     *
     *  场景：用户在两个标签页同时点了"保存新版本"
     *
     *      时间   窗口A                      窗口B
     *      T1     max(version_no) = 2
     *      T2                                 max(version_no) = 2
     *      T3     INSERT version_no = 3 ✓
     *      T4                                 INSERT version_no = 3 ✗
     *                                        → 唯一键 uk_version_no 冲突，报 500
     *
     *  用户看到的是"莫名其妙的系统错误"。
     *
     *  加锁之后：
     *      时间   窗口A                      窗口B
     *      T1     拿到 resume 行锁 ↓
     *      T2                                 【阻塞等待】...
     *      T3     max=2, INSERT v3, 提交 ↓
     *      T4                                 【拿到锁】max=3, INSERT v4 ✓
     *
     *  ★ 两个版本都成功，版本号连续，没有冲突。
     *
     *  【★ @Lock(LockModeType.PESSIMISTIC_WRITE) 是什么？】
     *
     *    PESSIMISTIC_WRITE = 悲观写锁 = SELECT ... FOR UPDATE
     *    "悲观"的意思是：假设一定会冲突，所以先占住。
     *
     *    对比 PESSIMISTIC_READ（共享锁，SELECT ... FOR SHARE）：
     *      多个事务可以同时持有读锁，但谁都不能写。
     *      我们是要写，所以用写锁。
     *
     *    ★ 还有"乐观锁"（@Version 字段 + version 号比对），
     *      适合冲突少的场景（失败了重试）。
     *      但版本号计算必须"读-算-写"原子完成，乐观锁做不到 ——
     *      重试时还得重新算，不如直接串行化简单。
     *
     *  【★ 锁的粒度会不会太大？】
     *    不会。锁的是【一行】（这一份简历），
     *    不同用户的简历互不影响。
     *    而且加版本是低频操作（用户手动点），不会造成锁竞争。
     *
     *  【★ 为什么不用默认的 findById？】
     *    因为默认查询不加锁。必须显式声明 @Lock 才会生成 FOR UPDATE。
     *
     *  【★ 用 @Query 写 JPQL 而不是方法名推导，为什么？】
     *    因为方法名推导没法表达"加锁"这个语义 ——
     *    @Lock 必须配一个查询方法，而这个查询只按 id 查，
     *    用 @Query 显式写出来更清楚。
     *
     *  ★ 注意：这个方法【不带 user_id 条件】，
     *    所以 Service 里拿到之后必须自己做归属校验（用 filter 或 if）。
     *    为什么不用 findByIdAndUserId + @Lock？
     *    技术上可以，但 @Query 里写两个条件要额外处理命名参数，
     *    而这里我们本来就要拿到对象后再判断，所以拆开写更清晰。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Resume r where r.id = :id")
    Optional<Resume> findByIdForUpdate(@Param("id") Long id);

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【统计某个用户的简历数】—— 本项目暂未使用，示例用
     * ═══════════════════════════════════════════════════════════════
     *
     * 生成 SQL：SELECT COUNT(*) FROM resume WHERE user_id = ?
     *
     * ★ 可能用在：
     *   - 首页显示"你有 3 份简历"
     *   - 限制"最多创建 20 份"（本项目不做，但预留能力）
     */
    long countByUserId(Long userId);
}
