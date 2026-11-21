-- 简历版本关联的原始 PDF 原件。
-- 文件本体保存在后端私有目录，数据库只保存相对路径和元信息。
ALTER TABLE `resume_version`
  ADD COLUMN `original_file_path`   VARCHAR(500) DEFAULT NULL COMMENT '原件相对路径',
  ADD COLUMN `original_file_name`   VARCHAR(255) DEFAULT NULL COMMENT '原始文件名',
  ADD COLUMN `original_mime_type`   VARCHAR(100) DEFAULT NULL COMMENT 'MIME 类型',
  ADD COLUMN `original_file_size`   BIGINT UNSIGNED DEFAULT NULL COMMENT '文件大小（字节）',
  ADD COLUMN `original_file_hash`   CHAR(64) DEFAULT NULL COMMENT 'SHA-256 文件哈希',
  ADD COLUMN `original_uploaded_at` DATETIME(3) DEFAULT NULL COMMENT '原件上传时间';