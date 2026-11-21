package com.jianli.resume.entity;

import com.jianli.resume.enums.VersionSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  简历的一个"版本"。一个 ResumeVersion = 某一时刻的完整简历正文。
 *
 *  ★★ 最重要的一条设计：内容【不可变】
 *     一旦插入，content 就再也不会被 UPDATE。
 *     用户改内容 = 插入一条新记录，不是修改旧记录。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么内容必须不可变？】
 *
 *  三个理由，从重要到次要：
 *
 *  ① 【所有历史快照都指向它】
 *     分析报告里存了"当时这份简历被解析成什么"，
 *     建议里存了"证据：你的这句'负责订单系统开发'"。
 *     如果用户改了正文，你再打开那份报告 ——
 *     证据句在原文里找不到了，报告就自相矛盾了。
 *
 *     ★ 内容不可变 = 任何一份报告永远可复现、可解释。
 *       这是整个评估类产品的底线。
 *
 *  ② 【缓存 key 稳定】
 *     解析结果按 content_hash 缓存 30 天。
 *     内容不可变 → hash 永远指向同一份内容 → 缓存永远有效。
 *     如果内容可变，就必须处理"内容改了要清缓存"，容易漏。
 *
 *  ③ 【版本对比有意义】
 *     diff 比较的就是两个不可变的快照。
 *     如果内容能改，"对比"就变成了"跟自己比"。
 *
 *
 *  【★ 数据流向】
 *
 *      创建简历 → 插入 v1（source=PASTE，parent=null）
 *         ↓
 *      用户编辑 → 插入 v2（source=EDIT，parent=v1.id）
 *         ↓
 *      采纳社区建议 → 插入 v3（source=ADOPT_COMMENT，parent=v2.id）
 *         ↓
 *      分析时：读某个版本的 content → 送去解析 → 算分
 *         ↓
 *      版本对比：读两个版本的 content → 行级 diff
 *
 *  ★ parent_version_id 让版本形成一条"链"：
 *    能追溯"这一版是从哪一版改过来的"。
 */
@Entity
@Table(name = "resume_version")
@Getter
@Setter
@NoArgsConstructor
public class ResumeVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属简历。
     *
     * ★ 这里【建了外键】（fk_version_resume，ON DELETE CASCADE）。
     *   删简历时版本自动级联删除，由数据库保证，不依赖代码。
     */
    @Column(name = "resume_id", nullable = false)
    private Long resumeId;

    /**
     * 版本号，从 1 开始递增。
     *
     * ★ 唯一性怎么保证？
     *   1. 数据库唯一键 uk_version_no(resume_id, version_no) —— 最后防线
     *   2. 计算下一个版本号时用 SELECT ... FOR UPDATE 锁住简历行 —— 防并发
     *
     * ★ 为什么两个都要？
     *   锁能防止"两个请求同时算出 3"，但如果哪天有人绕过 Service 直接插数据，
     *   唯一键还能兜住。防御要分层。
     */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /**
     * 简历全文。★ 不可变。
     *
     * 【★★ @JdbcTypeCode(SqlTypes.LONGVARCHAR) 这一行是干嘛的？】
     *
     *   Java 的 String 默认映射成 VARCHAR(255)。
     *   但简历正文可能是上万字，必须用大文本类型。
     *
     *   数据库里是 MEDIUMTEXT（最大 16MB），JDBC 层面报告为 LONGVARCHAR。
     *   加上 @JdbcTypeCode(SqlTypes.LONGVARCHAR) 之后：
     *     - Hibernate 知道这是"长文本"，不是 VARCHAR(255)
     *     - 启动时 validate 不会因为类型不匹配而报错
     *     - 插入时用 setCharacterStream，能正确处理大文本
     *
     *   ★ 这里有个实测踩过的坑：
     *     如果只写 @Column(columnDefinition = "MEDIUMTEXT")，
     *     columnDefinition 只影响"Hibernate 生成 DDL 时用什么类型"，
     *     **不影响校验时预期的类型** —— 校验仍然预期 VARCHAR(255)，
     *     于是报：found [mediumtext (Types#LONGVARCHAR)], but expecting [varchar(255)]
     *
     *     所以必须用 @JdbcTypeCode 显式告诉 Hibernate 类型。
     *     （本项目的 DDL 是 Flyway 管的，columnDefinition 其实用不上，
     *       但留着能让 Entity 自解释"这列是大文本"。）
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    /**
     * 内容的 SHA-256 哈希（64 位十六进制）。
     *
     * 【★ 三个用途】
     *
     *  ① 【防空版本】
     *     用户点了保存但没改内容 → 算出来的 hash 和已有版本相同
     *     → 拒绝创建，不浪费存储和版本号
     *     → 返回友好提示"内容与已有版本相同"
     *
     *  ② 【缓存 key】
     *     解析结果按 content_hash 缓存（步骤 8）。
     *     内容不可变 → hash 稳定 → 缓存永远有效。
     *
     *  ③ 【去重】
     *     同一个用户把同一份简历粘两次，能识别出来（虽然本步骤不做，
     *     但字段设计为这个能力留了口子）。
     *
     * ★ 为什么是 CHAR(64) 而不是 VARCHAR(64)？
     *   SHA-256 的十六进制表示【永远】是 64 个字符，长度固定。
     *   CHAR 是定长类型，存储和比较都更高效。
     *   （VARCHAR 要多存 1-2 字节的长度信息）
     *
     * 【★★ 另一个实测踩到的坑：String 默认映射成 VARCHAR，不是 CHAR】
     *
     *   即使建表脚本里是 CHAR(64)，Hibernate 也预期 VARCHAR(64)，
     *   启动时报：
     *       Schema-validation: wrong column type encountered in column
     *       [content_hash]; found [char (Types#CHAR)],
     *       but expecting [varchar(64) (Types#VARCHAR)]
     *
     *   ★ 解决：用 @JdbcTypeCode(SqlTypes.CHAR) 显式声明"我要的是 CHAR"。
     *     这和 content 字段用 LONGVARCHAR 是同一个道理 ——
     *     **Java 类型和数据库类型的映射不是唯一的，必须显式告诉 Hibernate。**
     *
     *   ★ 另一种解法是把建表脚本改成 VARCHAR(64)（开 V3 迁移）。
     *     但 hash 用 CHAR 在语义上更准确，所以选择保留 CHAR + 注解。
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /**
     * 这一版是怎么产生的。
     *
     * ★★ @Enumerated(EnumType.STRING) 绝对不能省！
     *   默认是 ORDINAL（存 0/1/2），一旦枚举顺序变化，历史数据全部错位。
     *   详见 VersionSource 枚举的注释。
     *
     * ★ length = 16：AI / RULE / PASTE 这类值最多几个字符，
     *   但建表脚本里是 VARCHAR(16)，必须一致，否则 validate 报错。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VersionSource source;


    /** 原始 PDF 的私有存储相对路径。 */
    @Column(name = "original_file_path", length = 500)
    private String originalFilePath;

    /** 上传时的原始文件名。 */
    @Column(name = "original_file_name", length = 255)
    private String originalFileName;

    /** 原件 MIME 类型。 */
    @Column(name = "original_mime_type", length = 100)
    private String originalMimeType;

    /** 原件大小（字节）。 */
    @Column(name = "original_file_size")
    private Long originalFileSize;

    /** 原件 SHA-256。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "original_file_hash", length = 64)
    private String originalFileHash;

    /** 原件上传时间。 */
    @Column(name = "original_uploaded_at")
    private LocalDateTime originalUploadedAt;

    /**
     * 派生自哪一版。
     *
     * ★ 作用：形成版本链，能追溯"这一版是怎么来的"。
     *   v1(PASTE) ← v2(EDIT) ← v3(ADOPT_COMMENT)
     *
     * ★ 注意：建表脚本里【没有给这个字段建外键】。
     *   因为它指向自己这张表（自关联），加外键会有删除顺序的问题。
     *   而且它只是个"溯源"字段，为 null 也无妨（v1 就是 null）。
     */
    @Column(name = "parent_version_id")
    private Long parentVersionId;

    /**
     * 变更说明，如"补充项目量化数据"、"采纳评论 #88"。
     *
     * ★ 为什么需要它？
     *   版本时间线上只显示 v1/v2/v3 和字数，用户分不清哪版是哪版。
     *   加一句说明，回看时就知道"哦，这版是加了量化数据"。
     *
     * ★ 它是选填的：用户不填就是 null。
     */
    @Column(name = "change_note", length = 255)
    private String changeNote;

    /**
     * 字符数（不含空白）。★ 冗余字段。
     *
     * ★ 为什么冗余？
     *   版本列表要显示"860 字"。如果不冗余，每行都要算一遍内容长度 ——
     *   而内容可能是上万字，一次列表 10 行就是 10 次大文本读取。
     *
     * ★ 为什么不存 content.length() 而要去掉空白？
     *   用户预期的"字数"是"实际写了多少字"，
     *   而 content.length() 会把换行、缩进、空格都算进去。
     *   一份 50 行、每行缩进 4 空格的简历，光空格就有 200 个。
     *
     *   所以用 normalizer.charCount()：去掉所有空白字符再计数。
     *   ★ 这也和"至少 50 字"的校验口径一致。
     */
    @Column(name = "char_count", nullable = false)
    private Integer charCount;

    /**
     * 创建时间。★ 注意：没有 updatedAt。
     *
     * ★ 为什么没有？
     *   因为内容不可变 —— 一条记录插入之后永远不会被修改，
     *   "更新时间"和"创建时间"永远相等，存两个字段没有意义。
     *
     *   ★ 这是"不可变设计"带来的简化：少一个字段、少一次 UPDATE。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
        if (charCount == null) {
            charCount = 0;
        }
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 为什么没有 @PreUpdate？】
     *  因为本类的内容不应该被修改。如果哪天你发现需要加 @PreUpdate，
     *  先停下来想想：是不是应该插入一条新版本，而不是改旧版本？
     *
     *  ★ 唯一允许的例外是步骤 14 的"采纳"会设置 adoptedVersionId ——
     *    但那是改 comment 表，不是改本表。
     * ═══════════════════════════════════════════════════════════════
     */
}
