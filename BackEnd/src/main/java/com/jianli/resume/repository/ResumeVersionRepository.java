package com.jianli.resume.repository;

import com.jianli.resume.entity.ResumeVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个接口是什么】
 *  简历版本表的数据访问。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向】
 *
 *      create()      → save(v1)                 INSERT 第一版
 *      addVersion()  → findMaxVersionNo(id)     算下一个版本号
 *                    → findFirstByResumeIdAndContentHash(...)  去重
 *                    → save(v2)                 INSERT 新版
 *      detail()      → findByResumeIdOrderByVersionNoDesc(id)  版本时间线
 *      getVersion()  → findById(id)             取全文
 */
public interface ResumeVersionRepository extends JpaRepository<ResumeVersion, Long> {

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【版本时间线】按版本号倒序（最新的在最上面）
     * ═══════════════════════════════════════════════════════════════
     *
     * 生成 SQL：
     *      SELECT * FROM resume_version WHERE resume_id = ?
     *      ORDER BY version_no DESC
     *
     * ★ 为什么按 version_no 而不是 created_at？
     *   两个都能表达"先后"，但 version_no 更可靠：
     *   created_at 在极端情况下可能相同（毫秒级并发），
     *   而 version_no 有唯一键保证不重复。
     *
     * ★ 索引：
     *   uk_version_no(resume_id, version_no) 正好能覆盖
     *   WHERE + ORDER BY，不需要额外排序。
     *
     * ★ 为什么不分页？
     *   一份简历的版本通常 < 20 个（用户不会改几百次）。
     *   一次全查出来比"分页 + 前端再请求"简单得多。
     *   ★ 如果真有人改了几百次，可以改成 Page；但那是极端情况，
     *     现在没必要为此增加复杂度。
     */
    List<ResumeVersion> findByResumeIdOrderByVersionNoDesc(Long resumeId);

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【按 id + 简历 id 查】用于版本对比时校验归属
     * ═══════════════════════════════════════════════════════════════
     *
     * 生成 SQL：
     *      SELECT * FROM resume_version WHERE id = ? AND resume_id = ?
     *
     * ★ 用在哪：步骤 6 的版本对比
     *   对比时必须保证"两个版本都属于同一份简历"，
     *   否则会拿 A 的简历跟 B 的简历比 —— 没有意义。
     *
     * ★ 同样地：带条件查 > 查出来再判断。
     */
    Optional<ResumeVersion> findByIdAndResumeId(Long id, Long resumeId);

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【取最大版本号】用于算下一个版本号
     * ═══════════════════════════════════════════════════════════════
     *
     * JPQL：
     *      SELECT COALESCE(MAX(v.versionNo), 0) FROM ResumeVersion v
     *      WHERE v.resumeId = :resumeId
     *
     * 【★ COALESCE 是干嘛的？】
     *   如果这个简历还没有任何版本（创建时的第一个版本还没插），
     *   MAX() 会返回 NULL。
     *   COALESCE(MAX(...), 0) 表示"是 NULL 就取 0"。
     *
     *   这样 nextNo = 0 + 1 = 1，正好是 v1，不用特殊处理"第一次"的情况。
     *
     *   ★ 如果不加 COALESCE：
     *     返回值是 Integer 的话就是 null，+1 会抛 NullPointerException。
     *     或者返回值是 int 的话会自动拆箱 null → NPE。
     *
     * 【★ 为什么返回值是 int 而不是 Integer？】
     *   COALESCE 保证永远有值（至少是 0），所以可以安全用基本类型。
     *   少一层拆箱判断。
     *
     * 【★ 为什么用 @Query 而不是方法名推导？】
     *   方法名推导不支持聚合函数（MAX / COUNT 之类）。
     *   可以用 findTopByResumeIdOrderByVersionNoDesc（取第一条），
     *   但那样要处理"没有记录"的情况，反而更麻烦。
     *   直接写 MAX 最直观。
     */
    @Query("select coalesce(max(v.versionNo), 0) from ResumeVersion v where v.resumeId = :resumeId")
    int findMaxVersionNo(@Param("resumeId") Long resumeId);

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【按内容哈希查】用于"内容没变就不建新版本"
     * ═══════════════════════════════════════════════════════════════
     *
     * JPQL（或方法名推导，这里手写更清楚）：
     *      SELECT * FROM resume_version
     *      WHERE resume_id = ? AND content_hash = ? LIMIT 1
     *
     * 【★ 为什么要去重？】
     *   用户点了保存但没改内容（或者改完又改回去了）。
     *   如果每次都建新版本：
     *     - 版本号白白消耗
     *     - 时间线上出现一模一样的两条，用户困惑
     *     - 版本对比时两个版本 diff 为空
     *
     *   ★ 直接拒绝并提示"内容未变化"，是更好的体验。
     *
     * 【★ 为什么是 findFirst... 而不是 find...？】
     *   理论上同一个简历内 hash 不会重复（因为重复的会被拒绝），
     *   但万一历史数据有重复，返回多条会抛异常。
     *   加 First 变成 LIMIT 1，只关心"有没有"，更稳健。
     *
     * ★ 返回 Optional 而不是 boolean：
     *   因为后面可能想拿到那条重复的版本（比如告诉用户"和第 2 版相同"）。
     *   现在只判断 isPresent()，但保留信息给未来用。
     *
     * ★ 索引：idx_version_hash(content_hash) 建了，
     *   不过这里的查询是 (resume_id, content_hash) 组合，
     *   MySQL 会选 uk_version_no(resume_id, ...) 或 idx_version_hash，
     *   数据量小时差别不大。
     */
    Optional<ResumeVersion> findFirstByResumeIdAndContentHash(Long resumeId, String contentHash);

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【某用户的某个版本的归属校验】
     * ═══════════════════════════════════════════════════════════════
     *
     * 用在哪：分析时（步骤 8）需要确认"这个版本是不是当前用户的"。
     *
     * JPQL 拆解：
     *      select v from ResumeVersion v
     *      where v.id = :versionId
     *        and v.resumeId in (
     *            select r.id from Resume r where r.userId = :userId
     *        )
     *
     * ★ 子查询的意思是："这个版本所属的简历，是不是这个用户的"。
     *   一条 SQL 完成"取版本 + 校验归属"，不用查两次。
     *
     * ★ 为什么不用连表 join？
     *   因为 Resume 和 ResumeVersion 之间没有 JPA 关联（我们只存 id），
     *   所以只能写子查询。
     *   ★ 这是"不用 @ManyToOne"带来的一个小代价：
     *     需要手写 JPQL 子查询。但换来的是零懒加载问题，值得。
     */
    @Query("""
            select v from ResumeVersion v
            where v.id = :versionId
              and v.resumeId in (select r.id from Resume r where r.userId = :userId)
            """)
    Optional<ResumeVersion> findOwnedVersion(@Param("versionId") Long versionId,
                                             @Param("userId") Long userId);

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【某用户的所有版本】按创建时间倒序
     * ═══════════════════════════════════════════════════════════════
     *
     * 用在哪：步骤 15 的"按简历版本查看面试题历史"，
     *        以及将来可能的"全站版本统计"。
     *
     * ★ 现在用不到，但先把查询写好 ——
     *   这属于"知道自己需要什么查询"的设计，不是过度设计
     *   （多写一个方法几行代码，总比到时候临时加好）。
     */
    @Query("""
            select v from ResumeVersion v
            where v.resumeId in (select r.id from Resume r where r.userId = :userId)
            order by v.createdAt desc
            """)
    List<ResumeVersion> findByUserId(@Param("userId") Long userId);
}
