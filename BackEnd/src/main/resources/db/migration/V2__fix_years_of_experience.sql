-- =====================================================================
-- V2__fix_years_of_experience.sql
--
-- 【为什么有这条迁移】
--   V1 里 years_of_experience 定义成了 TINYINT UNSIGNED。
--   但 Java 实体 AppUser 里用的是 Integer，
--   Hibernate 的 ddl-auto=validate 在启动时比对：
--       MySQL 实际类型：tinyint unsigned (Types#TINYINT)
--       Java 期望类型：integer        (Types#INTEGER)
--   → 不匹配，启动失败：
--       Schema-validation: wrong column type encountered in column
--       [years_of_experience] ... found [tinyint unsigned], but expecting [integer]
--
-- 【为什么改成 INT 而不是把 Java 改成 Short】
--   TINYINT UNSIGNED 的范围是 0-255，对应 Java 的 Short。
--   但用 Short 意味着所有地方都要写 (short) 3，非常别扭。
--   INT 占 4 字节 vs TINYINT 1 字节 —— 一张用户表几千行，
--   多占几十 KB，完全没有意义。可读性和开发体验优先。
--
-- 【为什么新开 V2 而不再改 V1】
--   Flyway 的规矩：已执行的脚本不能再改。
--   改了会导致迁移记录与实际文件对不上（checksum mismatch），
--   其他环境（比如服务器）也会处于不同的状态。
--   正确做法永远是"追加新脚本"。
--
-- 【注意】
--   post 表也有同名字段，一并修改，避免步骤 13 再踩同一个坑。
-- =====================================================================

ALTER TABLE `app_user`
  MODIFY COLUMN `years_of_experience` INT NOT NULL DEFAULT 0
  COMMENT '经验年限（年，0-50）';

ALTER TABLE `post`
  MODIFY COLUMN `years_of_experience` INT NOT NULL DEFAULT 0
  COMMENT '经验年限（冗余，用于筛选）';
