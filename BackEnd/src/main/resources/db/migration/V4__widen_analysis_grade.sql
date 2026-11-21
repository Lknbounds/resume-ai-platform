-- =====================================================================
-- V4__widen_analysis_grade.sql
--
-- 【为什么要改】
--   V1 里 analysis_report.grade 定义成 VARCHAR(8)，
--   但枚举值 EXCELLENT 有 9 个字符 → 插入时被 MySQL 截断报错：
--
--       Data truncation: Data too long for column 'grade' at row 1
--
--   ★ 这是个"只在拿到 85 分以上才会触发"的隐藏 bug ——
--     分数低时 grade 是 GOOD / FAIR / WEAK（都 ≤ 6 字符），一切正常。
--     只有打出优秀分时才炸。所以前面测试一直没发现。
--
-- 【为什么改成 VARCHAR(16) 而不是刚好 9】
--   留余量。以后如果加 "OUTSTANDING"（11 字符）之类的等级不用再迁移。
--   VARCHAR 只占实际长度 + 1 字节，多留几个字符不浪费空间。
--
-- 【教训】
--   枚举字段的列宽要按【最长可能的枚举值】定，不是按"常见的那个"。
--   这是设计阶段容易忽略、运行时才暴露的典型问题。
-- =====================================================================

ALTER TABLE `analysis_report`
  MODIFY COLUMN `grade` VARCHAR(16) DEFAULT NULL
  COMMENT '等级：EXCELLENT/GOOD/FAIR/WEAK';

-- ---------------------------------------------------------------------
-- ★ 顺便检查：还有别的列宽可能不够吗？
--
--   status          VARCHAR(16)  → PARTIAL(7) PENDING(7) 够
--   scoring_version VARCHAR(16)  → v1 够
--   degraded_reasons VARCHAR(255) → 逗号拼接，够
--   ai_model        VARCHAR(64)  → qwen-plus 够
--   error_message   VARCHAR(500) → 够
--
--   analysis_suggestion.severity VARCHAR(8) → HIGH/MEDIUM/LOW 够
--   analysis_suggestion.section  VARCHAR(32) → EXPERIENCE 够
--
--   ★ 只有 grade 有问题。
-- ---------------------------------------------------------------------
