-- =====================================================================
-- V1__init.sql  AI 辅助简历分析与社区互评平台  初始化脚本
-- MySQL 8.0.28+  InnoDB / utf8mb4_0900_ai_ci
-- =====================================================================

-- ---------- 1. 用户 ----------
CREATE TABLE `app_user` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`            VARCHAR(32)     NOT NULL                COMMENT '登录名，大小写不敏感唯一',
  `password_hash`       VARCHAR(100)    NOT NULL                COMMENT 'BCrypt 哈希（$2a$10$...）',
  `nickname`            VARCHAR(32)     NOT NULL                COMMENT '昵称',
  `target_position`     VARCHAR(64)     DEFAULT NULL            COMMENT '目标岗位（个人资料）',
  `years_of_experience` TINYINT UNSIGNED NOT NULL DEFAULT 0     COMMENT '经验年限（年，0-50）',
  `created_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
  `updated_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `last_login_at`       DATETIME(3)     DEFAULT NULL            COMMENT '最后登录时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户';

-- ---------- 2. 简历（逻辑主体） ----------
CREATE TABLE `resume` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`            BIGINT UNSIGNED NOT NULL                COMMENT '归属用户',
  `title`              VARCHAR(64)     NOT NULL                COMMENT '简历标题，如「Java 后端简历」',
  `current_version_id` BIGINT UNSIGNED DEFAULT NULL            COMMENT '当前版本ID（逻辑外键，不建约束，避免循环依赖）',
  `version_count`      INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '版本计数（冗余，便于列表展示）',
  `created_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_resume_user` (`user_id`, `updated_at` DESC),
  CONSTRAINT `fk_resume_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='简历';

-- ---------- 3. 简历版本（内容不可变） ----------
CREATE TABLE `resume_version` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `resume_id`        BIGINT UNSIGNED NOT NULL                COMMENT '所属简历',
  `version_no`       INT UNSIGNED    NOT NULL                COMMENT '版本号，从 1 递增',
  `content`          MEDIUMTEXT      NOT NULL                COMMENT '简历纯文本全文',
  `content_hash`     CHAR(64)        NOT NULL                COMMENT 'sha256(规范化content)，用于解析缓存/去重',
  `source`           VARCHAR(16)     NOT NULL DEFAULT 'PASTE' COMMENT 'PASTE/EDIT/ADOPT_COMMENT',
  `parent_version_id` BIGINT UNSIGNED DEFAULT NULL           COMMENT '派生自哪个版本（编辑/采纳评论时记录）',
  `change_note`      VARCHAR(255)    DEFAULT NULL            COMMENT '变更说明，如「采纳评论#12 补充量化结果」',
  `char_count`       INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '字符数（冗余，列表展示用）',
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_version_no` (`resume_id`, `version_no`),
  KEY `idx_version_hash` (`content_hash`),
  CONSTRAINT `fk_version_resume` FOREIGN KEY (`resume_id`) REFERENCES `resume` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='简历版本';

-- ---------- 4. JD ----------
CREATE TABLE `job_description` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`       BIGINT UNSIGNED NOT NULL                COMMENT '归属用户',
  `title`         VARCHAR(100)    NOT NULL                COMMENT '岗位名称，如「Java 后端开发工程师」',
  `company`       VARCHAR(100)    DEFAULT NULL            COMMENT '公司名称（可选）',
  `content`       MEDIUMTEXT      NOT NULL                COMMENT 'JD 原始文本',
  `content_hash`  CHAR(64)        NOT NULL                COMMENT 'sha256(规范化content + parserVersion)',
  `parse_status`  VARCHAR(16)     NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PARSING/SUCCESS/FAILED',
  `parsed_source` VARCHAR(8)      DEFAULT NULL            COMMENT 'AI/RULE，解析来源',
  `parsed_json`   JSON            DEFAULT NULL            COMMENT '结构化解析结果 JdParsed 快照',
  `parsed_at`     DATETIME(3)     DEFAULT NULL            COMMENT '解析时间',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_jd_user` (`user_id`, `created_at` DESC),
  KEY `idx_jd_hash` (`content_hash`),
  CONSTRAINT `fk_jd_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='目标岗位JD';

-- ---------- 5. 分析报告 ----------
CREATE TABLE `analysis_report` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`           BIGINT UNSIGNED NOT NULL                COMMENT '发起人',
  `resume_version_id` BIGINT UNSIGNED NOT NULL                COMMENT '被分析的简历版本',
  `jd_id`             BIGINT UNSIGNED NOT NULL                COMMENT '目标JD',
  `status`            VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/PARTIAL/FAILED',
  `overall_score`     DECIMAL(5,2)    DEFAULT NULL            COMMENT '总分 0.00-100.00，由程序计算',
  `grade`             VARCHAR(8)      DEFAULT NULL            COMMENT 'EXCELLENT/GOOD/FAIR/WEAK',
  `dimension_json`    JSON            DEFAULT NULL            COMMENT '维度明细 [{key,score,weight,effectiveWeight,detail}]',
  `keyword_json`      JSON            DEFAULT NULL            COMMENT '{hits,partials,missing}',
  `semantic_json`     JSON            DEFAULT NULL            COMMENT '{source:AI/RULE/NONE, items:[{skill,similarity,matchedBy}]}',
  `weights_json`      JSON            DEFAULT NULL            COMMENT '生效权重快照（含归一化结果）',
  `scoring_version`   VARCHAR(16)     NOT NULL DEFAULT 'v1'   COMMENT '评分算法版本，算法改动必须升版本',
  `jd_parsed_json`    JSON            DEFAULT NULL            COMMENT 'JD 解析结果快照',
  `resume_parsed_json` JSON           DEFAULT NULL            COMMENT '简历解析结果快照',
  `ai_enabled`        TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '本次分析是否启用AI',
  `degraded`          TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '是否发生降级',
  `degraded_reasons`  VARCHAR(255)    DEFAULT NULL            COMMENT '降级原因，逗号分隔：AI_TIMEOUT,AI_DISABLED,AiOutputInvalid',
  `ai_model`          VARCHAR(64)     DEFAULT NULL            COMMENT '实际使用的模型，如 qwen-plus',
  `cache_key`         CHAR(64)        NOT NULL                COMMENT 'sha256(versionId|jdId|scoringVersion|aiEnabled|parseHash)，缓存/幂等键',
  `duration_ms`       INT UNSIGNED    DEFAULT NULL            COMMENT '端到端耗时',
  `error_message`     VARCHAR(500)    DEFAULT NULL            COMMENT '失败原因（仅 FAILED 时有值）',
  `created_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_report_user` (`user_id`, `created_at` DESC),
  KEY `idx_report_version` (`resume_version_id`),
  KEY `idx_report_jd` (`jd_id`),
  KEY `idx_report_cache` (`cache_key`),
  CONSTRAINT `fk_report_user`    FOREIGN KEY (`user_id`)           REFERENCES `app_user` (`id`)       ON DELETE CASCADE,
  CONSTRAINT `fk_report_version` FOREIGN KEY (`resume_version_id`) REFERENCES `resume_version` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_report_jd`      FOREIGN KEY (`jd_id`)             REFERENCES `job_description` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ATS匹配分析报告';

-- ---------- 6. 板块建议 ----------
CREATE TABLE `analysis_suggestion` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_id`   BIGINT UNSIGNED NOT NULL                COMMENT '所属报告',
  `section`     VARCHAR(32)     NOT NULL                COMMENT 'SUMMARY/SKILLS/EXPERIENCE/PROJECT/EDUCATION/KEYWORDS',
  `severity`    VARCHAR(8)      NOT NULL DEFAULT 'MEDIUM' COMMENT 'HIGH/MEDIUM/LOW',
  `issue`       VARCHAR(500)    NOT NULL                COMMENT '问题描述',
  `evidence`    VARCHAR(500)    DEFAULT NULL            COMMENT '证据引用：简历原文片段（后端校验必须存在于content中）',
  `suggestion`  VARCHAR(1000)   NOT NULL                COMMENT '修改方向',
  `source`      VARCHAR(8)      NOT NULL DEFAULT 'RULE' COMMENT 'AI/RULE',
  `sort_no`     INT             NOT NULL DEFAULT 0      COMMENT '排序',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_sug_report` (`report_id`, `sort_no`),
  CONSTRAINT `fk_sug_report` FOREIGN KEY (`report_id`) REFERENCES `analysis_report` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='按板块的改进建议';

-- ---------- 7. AI 重写片段 ----------
CREATE TABLE `resume_rewrite` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_id`     BIGINT UNSIGNED NOT NULL                COMMENT '所属报告',
  `section`       VARCHAR(32)     NOT NULL                COMMENT '板块',
  `original_text` VARCHAR(1000)   NOT NULL                COMMENT '原句（必须能在简历原文中定位）',
  `start_offset`  INT             NOT NULL                COMMENT '原句在 resume_version.content 中的起始字符下标',
  `rewritten_text` VARCHAR(2000)  NOT NULL                COMMENT '改写结果',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_rw_report` (`report_id`, `id`),
  CONSTRAINT `fk_rw_report` FOREIGN KEY (`report_id`) REFERENCES `analysis_report` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI简历重写片段（禁止编造经历）';

-- ---------- 8. 个人信息审核记录 ----------
CREATE TABLE `privacy_review` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`      BIGINT UNSIGNED NOT NULL                COMMENT '提交人',
  `scene`        VARCHAR(24)     NOT NULL                COMMENT 'POST_PUBLISH/COMMENT_PUBLISH/PRECHECK',
  `status`       VARCHAR(16)     NOT NULL                COMMENT 'PASSED/REJECTED',
  `hit_count`    INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '命中条目数',
  `hit_detail`   JSON            DEFAULT NULL            COMMENT '[{type,start,end,maskedSnippet,ruleId}] 仅存脱敏片段',
  `rule_version` VARCHAR(16)     NOT NULL                COMMENT '审核规则版本，如 v1',
  `ai_checked`   TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '是否启用了AI辅助复核',
  `content_hash` CHAR(64)        NOT NULL                COMMENT '被审核内容的 sha256（不存原文）',
  `duration_ms`  INT UNSIGNED    DEFAULT NULL            COMMENT '审核耗时',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_prv_user` (`user_id`, `created_at` DESC),
  KEY `idx_prv_status` (`status`, `created_at` DESC),
  CONSTRAINT `fk_prv_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='个人信息审核记录（原文不入库）';

-- ---------- 9. 帖子 ----------
CREATE TABLE `post` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`            BIGINT UNSIGNED NOT NULL                COMMENT '作者（匿名也记录）',
  `report_id`          BIGINT UNSIGNED DEFAULT NULL            COMMENT '来源报告（报告删除后置空，靠快照展示）',
  `privacy_review_id`  BIGINT UNSIGNED NOT NULL                COMMENT '发布前的审核记录（必须是PASSED）',
  `title`              VARCHAR(120)    NOT NULL                COMMENT '帖子标题',
  `content`            VARCHAR(2000)   DEFAULT NULL            COMMENT '作者补充说明/求助正文',
  `resume_content`     MEDIUMTEXT      NOT NULL                COMMENT '发布时的简历正文（用户已抹去敏感信息）',
  `report_snapshot`    JSON            NOT NULL                COMMENT '报告快照：overallScore/dimensions/keywords/suggestions',
  `overall_score`      DECIMAL(5,2)    NOT NULL                COMMENT '总分（冗余，用于排序筛选）',
  `target_position`    VARCHAR(64)     DEFAULT NULL            COMMENT '目标岗位（冗余，用于筛选）',
  `years_of_experience` TINYINT UNSIGNED NOT NULL DEFAULT 0    COMMENT '经验年限（冗余，用于筛选）',
  `is_anonymous`       TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '是否匿名展示',
  `status`             VARCHAR(16)     NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED/DELETED/HIDDEN',
  `view_count`         INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '浏览数（Redis 定时回写）',
  `comment_count`      INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '评论数（冗余）',
  `up_count`           INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '赞（冗余）',
  `down_count`         INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '踩（冗余）',
  `hot_score`          DOUBLE          NOT NULL DEFAULT 0      COMMENT '热度分（MySQL 侧兜底排序用）',
  `created_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_report` (`report_id`),
  KEY `idx_post_status_time` (`status`, `created_at` DESC),
  KEY `idx_post_position` (`status`, `target_position`, `created_at` DESC),
  KEY `idx_post_exp` (`status`, `years_of_experience`, `created_at` DESC),
  KEY `idx_post_user` (`user_id`, `created_at` DESC),
  KEY `idx_post_privacy` (`privacy_review_id`),
  CONSTRAINT `fk_post_user`    FOREIGN KEY (`user_id`)            REFERENCES `app_user` (`id`)        ON DELETE CASCADE,
  CONSTRAINT `fk_post_report`  FOREIGN KEY (`report_id`)          REFERENCES `analysis_report` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='社区帖子';

-- ---------- 10. 评论（两级） ----------
CREATE TABLE `comment` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `post_id`             BIGINT UNSIGNED NOT NULL                COMMENT '所属帖子',
  `user_id`             BIGINT UNSIGNED NOT NULL                COMMENT '作者',
  `parent_id`           BIGINT UNSIGNED DEFAULT NULL            COMMENT '父评论ID；为空表示顶层评论',
  `root_id`             BIGINT UNSIGNED DEFAULT NULL            COMMENT '顶层评论ID；顶层评论自身为 NULL，回复为根评论ID',
  `section`             VARCHAR(32)     NOT NULL DEFAULT 'OTHER' COMMENT '建议针对的简历板块',
  `content`             VARCHAR(1000)   NOT NULL                COMMENT '评论内容',
  `like_count`          INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '点赞数',
  `useful_count`        INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '「有用」票数',
  `adopted`             TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '是否被发布者采纳',
  `adopted_version_id`  BIGINT UNSIGNED DEFAULT NULL            COMMENT '采纳后生成的新简历版本',
  `adopted_at`          DATETIME(3)     DEFAULT NULL            COMMENT '采纳时间',
  `status`              VARCHAR(16)     NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED/DELETED',
  `created_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_comment_post` (`post_id`, `root_id`, `created_at`),
  KEY `idx_comment_user` (`user_id`, `created_at` DESC),
  KEY `idx_comment_adopted` (`post_id`, `adopted`),
  CONSTRAINT `fk_comment_post`    FOREIGN KEY (`post_id`)   REFERENCES `post` (`id`)            ON DELETE CASCADE,
  CONSTRAINT `fk_comment_user`    FOREIGN KEY (`user_id`)   REFERENCES `app_user` (`id`)        ON DELETE CASCADE,
  CONSTRAINT `fk_comment_parent`  FOREIGN KEY (`parent_id`) REFERENCES `comment` (`id`)         ON DELETE CASCADE,
  CONSTRAINT `fk_comment_version` FOREIGN KEY (`adopted_version_id`) REFERENCES `resume_version` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='帖子评论';

-- ---------- 11. 投票（多态：POST / COMMENT） ----------
CREATE TABLE `vote` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '投票人',
  `target_type` VARCHAR(16)     NOT NULL                COMMENT 'POST/COMMENT',
  `target_id`   BIGINT UNSIGNED NOT NULL                COMMENT '目标ID',
  `vote_type`   VARCHAR(16)     NOT NULL                COMMENT 'UP/DOWN(帖子)  USEFUL(评论)',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vote` (`user_id`, `target_type`, `target_id`, `vote_type`),
  KEY `idx_vote_target` (`target_type`, `target_id`, `vote_type`),
  CONSTRAINT `fk_vote_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='多态投票表（无外键到目标表）';

-- ---------- 12. 面试题生成批次 ----------
CREATE TABLE `interview_question_set` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`           BIGINT UNSIGNED NOT NULL                COMMENT '生成人',
  `resume_version_id` BIGINT UNSIGNED NOT NULL                COMMENT '基于的简历版本',
  `jd_id`             BIGINT UNSIGNED NOT NULL                COMMENT '基于的JD',
  `status`            VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
  `use_external_info` TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '是否启用「参考公开信息」',
  `external_source`   VARCHAR(16)     NOT NULL DEFAULT 'NONE' COMMENT 'NONE/DATASET',
  `external_refs`     JSON            DEFAULT NULL            COMMENT '引用的面经条目ID/片段标题（仅素材）',
  `model`             VARCHAR(64)     DEFAULT NULL            COMMENT '使用的模型',
  `question_count`    INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '题目数量',
  `error_message`     VARCHAR(500)    DEFAULT NULL            COMMENT '失败原因',
  `duration_ms`       INT UNSIGNED    DEFAULT NULL            COMMENT '耗时',
  `created_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_iqs_user` (`user_id`, `created_at` DESC),
  KEY `idx_iqs_version` (`resume_version_id`, `created_at` DESC),
  CONSTRAINT `fk_iqs_user`    FOREIGN KEY (`user_id`)           REFERENCES `app_user` (`id`)       ON DELETE CASCADE,
  CONSTRAINT `fk_iqs_version` FOREIGN KEY (`resume_version_id`) REFERENCES `resume_version` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_iqs_jd`      FOREIGN KEY (`jd_id`)             REFERENCES `job_description` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='面试预演题生成批次';

-- ---------- 13. 面试题 ----------
CREATE TABLE `interview_question` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `set_id`     BIGINT UNSIGNED NOT NULL                COMMENT '所属批次',
  `sort_no`    INT             NOT NULL DEFAULT 0      COMMENT '顺序',
  `category`   VARCHAR(16)     NOT NULL                COMMENT 'TECHNICAL/PROJECT/BEHAVIORAL/SCENARIO',
  `question`   VARCHAR(500)    NOT NULL                COMMENT '题目',
  `intent`     VARCHAR(500)    NOT NULL                COMMENT '考察点',
  `key_points` JSON            NOT NULL                COMMENT '回答应覆盖的要点 ["...","..."]',
  `follow_up`  VARCHAR(500)    DEFAULT NULL            COMMENT '可能的追问',
  `source`     VARCHAR(16)     NOT NULL DEFAULT 'AI'   COMMENT 'AI/TEMPLATE',
  `ref_note`   VARCHAR(255)    DEFAULT NULL            COMMENT '参考了哪条公开素材（仅注明，不复制原文）',
  `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_iq_set` (`set_id`, `sort_no`),
  CONSTRAINT `fk_iq_set` FOREIGN KEY (`set_id`) REFERENCES `interview_question_set` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='面试题';
