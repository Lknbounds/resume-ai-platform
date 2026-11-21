package com.jianli.resume.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  简历的"逻辑主体"。一个 Resume 对象 = 一份简历（不含内容）。
 *
 *  ★ 注意：内容的真正载体是 ResumeVersion（版本），不是本类。
 *    本类只记录"有这份简历、叫什么、当前是哪一版"。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么要拆成"简历 + 版本"两张表？】
 *
 *  一份简历会被反复修改：
 *      一稿投大厂
 *      二稿改量化
 *      三稿采纳社区建议
 *
 *  如果只用一张表反复 UPDATE：
 *      ✗ 历史没了 —— 你再也看不到第一版长什么样
 *      ✗ 版本对比做不了 —— 拿什么比？
 *      ✗ 分析报告失效 —— 报告说"你的项目描述缺少量化"，
 *        但用户已经把正文改了，你打开报告找不到证据句了
 *
 *  拆成两张表之后：
 *      resume        → 逻辑主体（标题、当前版本指针、版本数）
 *      resume_version → 内容【不可变】（每次修改插入新行，永不 UPDATE 正文）
 *
 *  ★ 这是"可复现性"的基础：
 *    任何一次分析报告都能精确指向"当时那一版"，
 *    三个月后回看仍是同一份内容。
 *
 *
 *  【★ 数据流向】
 *
 *      用户点"新建简历"
 *         ↓
 *      ResumeService.create()
 *         ├─ INSERT resume（本类）            → 拿到 resume.id
 *         ├─ INSERT resume_version（v1，内容）→ 拿到 version.id
 *         └─ UPDATE resume.current_version_id = version.id
 *         ↓
 *      用户点"保存为新版本"
 *         ↓
 *      ResumeService.addVersion()
 *         ├─ SELECT ... FOR UPDATE 锁住 resume 行  ← 防并发版本号冲突
 *         ├─ INSERT resume_version（v2）
 *         └─ UPDATE resume.current_version_id / version_count
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ current_version_id 为什么不建外键？（面试常问）】
 *
 *  看这两张表的关系：
 *      resume.current_version_id  → 指向 resume_version.id
 *      resume_version.resume_id   → 指向 resume.id
 *
 *  这是【循环引用】。如果两个都建外键，会出现插入顺序无解：
 *      - 想插 resume，但 current_version_id 要先有值 → 得先有 version
 *      - 想插 version，但 resume_id 要先有值 → 得先有 resume
 *
 *  ★ 本项目的方案：
 *      resume_version.resume_id   → 建外键（真正的归属关系）✓
 *      resume.current_version_id  → 只建普通索引，由 Service 保证一致性
 *
 *  ★ 一致性怎么保证？
 *    在【同一个事务】里做完三件事：插版本、改指针、改计数。
 *    事务要么全成功要么全失败，不会出现"指针指向不存在的版本"。
 *
 *  ★ 这也是"用代码保证一致性 vs 用数据库约束保证一致性"的取舍：
 *    能用外键就用外键（resume_version.resume_id 就是），
 *    有循环依赖时只能退而求其次用事务。
 *
 *  【★ 为什么不写 @OneToMany List<ResumeVersion> versions？】
 *  原因和 AppUser 一样（见那里第 ③ 点）：
 *    1. 避免 LazyInitializationException（本项目 open-in-view=false）
 *    2. 避免隐式 N+1（循环里访问关联对象会逐条查库）
 *    3. 数据完整性由数据库外键保证
 *  需要用版本时，显式调 versionRepository.findByResumeId(...) ——
 *  查几次、什么时候查，全由代码控制。
 */
@Entity
@Table(name = "resume")
@Getter
@Setter
@NoArgsConstructor
public class Resume {

    /** 主键。自增 BIGINT，理由见 AppUser 的说明 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 归属用户。
     *
     * ★ 这个字段是【越权校验的基石】。
     *   所有涉及简历的操作都要先问："这份简历是不是当前用户的？"
     *   实现方式：查询时带上 user_id 条件
     *       findByIdAndUserId(resumeId, userId)
     *
     *   ★ 为什么不在查出来之后再判断？
     *     查出来再判断也能防越权，但会多一次不必要的查询，
     *     而且容易漏写 if（漏了就是漏洞）。
     *     在 SQL 里带上条件，从根上做到"查不到就是不存在"。
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 简历标题，如「Java 后端简历」「社招版」。用户自己起 */
    @Column(nullable = false, length = 64)
    private String title;

    /**
     * 目标岗位。如「Java 后端开发工程师」。★ 可空。
     *
     * 【★ 为什么加这个字段？】
     *
     *   用户有 3 份简历时，光看标题分不清哪份投什么：
     *       「社招版」「转行版」「实习版」 ← 只知道场景，不知道岗位
     *
     *   加上岗位后：
     *       [Java后端-社招版]  → Java 后端开发工程师
     *       [数据分析-转行版]  → 数据分析师
     *       [前端-实习版]      → 前端开发工程师
     *
     * 【★ 它和 job_description 是什么关系？】
     *
     *   targetPosition  → 这份简历"面向哪类岗位"（用户手填，粗粒度）
     *   job_description → 某次分析用的"哪份具体 JD"（从保存的 JD 里选，细粒度）
     *
     *   举例：
     *       一份简历的 targetPosition = "Java 后端开发工程师"
     *       但用户可以拿它去分析 3 个公司的 Java 岗位 JD
     *       → 三份报告，同一份简历
     *
     *   所以两者不能互相替代。后续可以在简历上再关联一个"默认 JD"，
     *   但那是另一个字段。
     *
     * ★ 为什么允许 null？
     *   用户可能先建简历、后想清楚投什么。
     *   强制填写会让"我就想先存一下"的用户卡住。
     */
    @Column(name = "target_position", length = 64)
    private String targetPosition;

    /**
     * 当前版本 id。★ 逻辑外键，不建数据库约束（原因见类注释）。
     *
     * ★ 这个字段的作用：让"这份简历现在用的是哪一版"成为一次查询就能拿到的事实，
     *   而不需要每次去 resume_version 里找 version_no 最大的那个。
     *
     * ★ 它和 version_count 都由 Service 在事务内维护，不会出现不一致。
     */
    @Column(name = "current_version_id")
    private Long currentVersionId;

    /**
     * 版本数量。★ 冗余字段。
     *
     * ★ 为什么冗余？
     *   列表页要显示"3 个版本"。如果不冗余，每行都要
     *   `SELECT COUNT(*) FROM resume_version WHERE resume_id=?` ——
     *   一页 10 条简历就是 10 次聚合查询。
     *   存一个计数，列表页 O(1) 读取。
     *
     * ★ 代价：每次加版本都要 +1，必须和插入版本在同一事务里。
     *   本项目加版本的地方只有一个（addVersion），所以不会漏。
     *
     * ★ 如果真的不一致了怎么办？
     *   可以跑一条 SQL 修：
     *       UPDATE resume r SET version_count =
     *         (SELECT COUNT(*) FROM resume_version v WHERE v.resume_id = r.id);
     *   ★ 这就是"计数字段要能从源表重算"的原则 —— 冗余可以，但必须可恢复。
     */
    @Column(name = "version_count", nullable = false)
    private Integer versionCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 插入前自动填充时间，并给计数兜底。
     *
     * ★ 这里没有给 currentVersionId 兜底 —— 因为创建时它还拿不到版本 id。
     *   顺序是：先插 resume → 再插 version → 最后回写 currentVersionId。
     *   所以插入 resume 时它确实是 null，这是正常的。
     *
     * ★ 为什么 versionCount 要兜底成 0？
     *   建表脚本里是 NOT NULL DEFAULT 0。
     *   但 JPA 插入时会显式写这个字段（传 null），
     *   数据库默认值不会生效 —— 不兜底就会违反 NOT NULL。
     *   （和 AppUser.yearsOfExperience 是同一个道理）
     */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (versionCount == null) {
            versionCount = 0;
        }
    }

    /**
     * 更新前刷新 updatedAt。
     *
     * ★ 什么时候会触发？
     *   1. 改标题
     *   2. 加新版本（改 currentVersionId 和 versionCount）
     *
     * ★ 为什么简历列表按 updated_at 倒序？
     *   用户最关心"最近在改哪份简历"，
     *   所以"刚加了新版本"的简历应该排到最前面。
     *   updated_at 自动更新，正好满足这个需求。
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
