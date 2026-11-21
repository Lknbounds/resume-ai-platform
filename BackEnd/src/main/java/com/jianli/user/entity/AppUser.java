package com.jianli.user.entity;

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
 *  实体类。一个 AppUser 对象 = app_user 表里的一行数据。
 *
 *      数据库表 app_user                     Java 对象 AppUser
 *      ┌────┬──────────┬───────────────┐    ┌──────────────────────┐
 *      │ id │ username │ password_hash │    │ Long id              │
 *      │ 1  │ alice    │ $2a$10$...    │ ── │ String username      │
 *      └────┴──────────┴───────────────┘    │ String passwordHash  │
 *                                           └──────────────────────┘
 *
 *  传统三层架构里它叫 PO / DO / Entity，名字不同但作用一样：
 *  「表和对象之间的映射」。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：它在链路里的哪一环】
 *
 *      MySQL 的 app_user 表
 *         │  ← JPA 根据本类的注解，自动做 行 ↔ 字段 的映射
 *         ↓
 *      AppUser 对象（本类）        ← Repository 查询返回的就是它
 *         ↓
 *      UserService 里手动转：UserVO.of(user)
 *         ↓
 *      UserVO                      ← ★ 对外暴露的形状（不含 passwordHash）
 *         ↓
 *      ApiResponse<UserVO> → JSON → 浏览器
 *
 *  ★ Entity 绝不会直接出现在 API 响应里 —— 本类含 passwordHash，
 *    直接返回等于把密码哈希泄露给前端。转换发生在 Service 层。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *
 *  【类上这几行注解，每一个为什么加】
 *
 *  @Entity
 *     告诉 JPA："这个类对应数据库的一张表。"
 *     没有它，JPA 完全不认识这个类，Repository 也没法用它。
 *
 *  @Table(name = "app_user")
 *     指定表名。★ 为什么必须写？因为 "user" 是 MySQL 的保留关键字，
 *     表名如果就叫 user 会报语法错误，所以建表时特意取名 app_user。
 *     既然表名和类名推不出来，就必须显式告诉 JPA。
 *
 *  @Getter / @Setter
 *     Lombok 自动生成 9 个字段的 getter 和 setter（共 18 个方法）。
 *
 *     ★ 为什么不用 @Data？（面试常问）
 *     @Data 会额外生成 toString() 和 equals/hashCode，两个都有问题：
 *       1. toString() 打印【所有字段】，包括 passwordHash。
 *          哪天有人写 log.info("user={}", user)，密码哈希就进了日志 ——
 *          这是真实的泄露途径，不是理论风险。
 *       2. equals/hashCode 用全部字段参与计算，JPA 在懒加载代理、
 *          放进 HashSet 时会出各种怪问题。
 *     所以实体只要 Getter/Setter，不要 @Data。
 *
 *  @NoArgsConstructor
 *     生成无参构造器。★ JPA 从数据库查数据时，需要先 new 一个空对象，
 *     再通过反射逐个 set 字段。没有无参构造器会报
 *     "No default constructor for entity"。
 *     （这也是为什么实体不能用 record —— record 只有全参构造器。）
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

    /**
     * 主键。
     *
     * @Id  标记这个字段是主键
     * @GeneratedValue(strategy = GenerationType.IDENTITY)
     *      主键由数据库自增生成，对应 MySQL 的 AUTO_INCREMENT。
     *      插入后 JPA 会把数据库生成的 id 自动回填到本对象，
     *      所以 save() 之后可以立刻 getId() 拿到值。
     *
     * ★ 为什么用自增 BIGINT 而不是 UUID？
     *   InnoDB 的聚簇索引按主键顺序存放数据：
     *     自增 id → 顺序追加，写入快、索引小、几乎不页分裂
     *     UUID   → 随机分布，每次插入都可能触发页分裂，索引多占 16 字节
     *   本项目单库单表，不需要"分布式唯一 id"，自增更合适。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 登录名，全局唯一。
     *
     * nullable = false → 数据库 NOT NULL
     * length   = 32    → VARCHAR(32)
     * unique   = true  → 唯一索引
     *
     * ★ 为什么没写 name = "username"？
     *   字段名和列名一模一样，JPA 自动推断。少写一处就少一个拼错的机会。
     *   下面名字对不上的（passwordHash ↔ password_hash）才必须写 name。
     *
     * ★ 防重复注册的真正防线是【数据库唯一索引】，不是代码里的 if 判断 ——
     *   两个请求同时注册同一用户名时，两个 if 会同时通过。
     */
    @Column(nullable = false, length = 32, unique = true)
    private String username;

    /**
     * 密码的 BCrypt 哈希。BCrypt 输出固定 60 字符，这里给 100 留余量。
     *
     * ★ 存的是哈希不是明文。BCrypt 的三个特点：
     *   1. 单向：无法从哈希反推密码
     *   2. 自带 salt：同一密码每次加密结果都不同（所以不需要单独的盐字段）
     *   3. 慢：单次约 50-100ms，爆破成本极高
     *   对比 MD5/SHA256 是"快哈希"，GPU 每秒能算几十亿次，等于帮了攻击者。
     *
     * ★ 列名 password_hash 与字段名 passwordHash 不同，必须写 name。
     */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /** 昵称。字段名与列名相同，不需要 name。 */
    @Column(nullable = false, length = 32)
    private String nickname;

    /**
     * 目标岗位，选填。
     *
     * ★ 没写 nullable = false，就是允许 null，对应建表脚本的 DEFAULT NULL。
     *   这两者必须一致，否则启动时 validate 会报错。
     */
    @Column(name = "target_position", length = 64)
    private String targetPosition;

    /**
     * 经验年限。
     *
     * ★ 为什么用 Integer 而不是 int？（面试常问）
     *   1. int 默认值是 0，无法区分"没填过"和"填了 0 年"；
     *      Integer 可以是 null，语义清晰（应届生 0 年是合法值，必须和 null 区分）。
     *   2. JPA 处理可选字段时对包装类型兼容性更好。
     */
    @Column(name = "years_of_experience", nullable = false)
    private Integer yearsOfExperience;

    /**
     * 创建时间。
     *
     * ★ updatable = false：插入后就再也不会被 UPDATE 语句修改。
     *   即使有人不小心调用 setCreatedAt(...)，JPA 也不会写回数据库。
     *   给"创建时间"这类只读字段上的保险。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间。每次 UPDATE 自动刷新（见下面的 @PreUpdate）。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 最后登录时间。刚注册的用户还没登录过，所以允许 null。 */
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【@PrePersist】JPA 生命周期回调：第一次插入数据库之前自动执行。
     *  不需要你在任何地方手动调用它。
     * ═══════════════════════════════════════════════════════════════
     *
     *  【★ 数据库不是有 DEFAULT CURRENT_TIMESTAMP 吗，为什么还要在这里赋值？】
     *
     *  这是最容易误解的一点。看建表脚本：
     *      `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
     *
     *  数据库默认值只在【INSERT 语句完全没提这一列】时才生效。
     *  但 JPA 生成的 INSERT 会列出【所有】实体字段：
     *      INSERT INTO app_user (username, ..., created_at, updated_at)
     *      VALUES (?, ..., ?, ?)          ← 这里传的是 null！
     *
     *  于是数据库尝试插入 null，违反 NOT NULL 约束，直接报错。
     *  结论：只要用 JPA 插入，就必须在代码里赋值。
     *
     *  【★ 为什么用 ZoneOffset.UTC，而不是 LocalDateTime.now()？】
     *  LocalDateTime.now() 取的是 JVM 所在时区的时间。
     *  本项目的约定是【统一存 UTC，展示时再转东八区】：
     *      存：  LocalDateTime.now(ZoneOffset.UTC)      ← 这里
     *      JDBC：connectionTimeZone=UTC                ← application.yml
     *      展示：JacksonConfig 格式化                  ← 步骤 2
     *  三者必须一致，有一处忘了就会出现"时间差 8 小时"的经典 bug。
     *
     *  【★ 为什么判断 createdAt == null？】
     *  如果调用方（比如测试代码）手动设了 createdAt，就不该覆盖它。
     *  只有为空时才自动填充 —— 这才是"默认值"的正确语义。
     */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        // 兜底：调用方忘了设置年限时填 0，避免插入 null 违反 NOT NULL
        if (yearsOfExperience == null) {
            yearsOfExperience = 0;
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【@PreUpdate】每次 UPDATE 数据库之前自动执行。
     * ═══════════════════════════════════════════════════════════════
     *
     *  这样每个 Service 方法里都不需要手写 user.setUpdatedAt(now)。
     *  只要对象被修改并保存，updatedAt 自动是最新时间。
     *
     *  ★ 这里不判断 null，因为更新时它一定有值（插入时已赋过），
     *    直接覆盖成当前时间就是正确行为。
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 为什么这里没有 @OneToMany / @ManyToOne 之类的关联？】
     *
     *  传统 JPA 教程会这么写：
     *      @OneToMany(mappedBy = "user")
     *      private List<Resume> resumes;      ← 关联对象
     *
     *  本项目【刻意不这样做】，后面所有实体都只存 id，不建关联对象。
     *  三个理由：
     *
     *  ① 避免 LazyInitializationException（最常见的坑）
     *     懒加载是"用到时才查库"，但本项目配了 open-in-view=false
     *     （生产环境的正确做法），事务一结束 Session 就关了。
     *     事务外再访问关联对象就抛异常。用普通 Long 字段永远没这问题。
     *
     *  ② 避免隐式 N+1
     *     写 resume.getVersions() 时，循环里每循环一次就发一条 SQL。
     *     存 id 后必须在 Service 里显式决定"要不要多查一次"，反而更可控。
     *
     *  ③ 数据完整性由数据库外键保证
     *     建表脚本里有 fk_version_resume ... ON DELETE CASCADE，
     *     删简历时版本自动级联删除，比 JPA 关联更可靠。
     * ═══════════════════════════════════════════════════════════════
     */
}
