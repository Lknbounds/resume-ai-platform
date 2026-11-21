-- =====================================================================
-- seed-demo-data.sql  演示数据
--
-- 【用途】
--   手动执行，往库里灌一批帖子/评论/投票，方便测试热度榜。
--   ★ 不放进 Flyway 的 migration 目录 —— 演示数据不该跟着每次部署跑。
--     放这里，需要时手动执行。
--
-- 【怎么执行】
--   mysql -uroot -p123456 --default-character-set=utf8mb4 resume_ai < seed-demo-data.sql
--
-- 【注意】
--   ★ 这个脚本会【假设】库里已有一个 ID 为 1 的 app_user。
--     如果还没有，先注册一个账号再跑。
--   ★ 脚本可以重复执行 —— 开头会清掉旧的演示数据（按 username 前缀识别）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 0. 清理旧演示数据
--
-- ★ 为什么按 username 前缀识别？
--   用 'demo_%' 开头的账号是脚本创建的，
--   不会误删用户自己注册的账号。
-- ---------------------------------------------------------------------
DELETE FROM vote WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'demo\_%');
DELETE FROM comment WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'demo\_%');
DELETE FROM post WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'demo\_%');
DELETE FROM privacy_review WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'demo\_%');
DELETE FROM analysis_report WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'demo\_%');
DELETE FROM resume WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'demo\_%');
DELETE FROM job_description WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'demo\_%');
DELETE FROM app_user WHERE username LIKE 'demo\_%';

-- ---------------------------------------------------------------------
-- 1. 演示用户
--
-- ★ 密码哈希是 BCrypt("Passw0rd1") 的真实值，
--   所以可以用 demo_alice / Passw0rd1 直接登录。
-- ---------------------------------------------------------------------
INSERT INTO app_user (username, password_hash, nickname, target_position, years_of_experience)
VALUES
('demo_alice',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '小 A', 'Java 后端开发', 3),
('demo_bob',    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '老王', 'Java 后端开发', 5),
('demo_carol',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Carol', '前端开发', 2),
('demo_david',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'David', '数据分析', 4),
('demo_emma',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Emma', '算法工程师', 1);

-- 记下 id 到变量
SET @u_alice = (SELECT id FROM app_user WHERE username = 'demo_alice');
SET @u_bob   = (SELECT id FROM app_user WHERE username = 'demo_bob');
SET @u_carol = (SELECT id FROM app_user WHERE username = 'demo_carol');
SET @u_david = (SELECT id FROM app_user WHERE username = 'demo_david');
SET @u_emma  = (SELECT id FROM app_user WHERE username = 'demo_emma');

-- ---------------------------------------------------------------------
-- 2. 每人一份简历 + JD + 报告（简化：直接插报告，跳过真实分析）
--
-- ★ 为什么跳过真实分析？
--   分析要跑评分引擎，脚本做不到。
--   这里直接构造"看起来合理"的报告数据 ——
--   ★ 演示数据只用于测试社区功能，不需要真实计算过。
-- ---------------------------------------------------------------------

-- Alice 的
INSERT INTO resume (user_id, title, version_count)
VALUES (@u_alice, 'Java后端-社招版', 1);
SET @r_alice = LAST_INSERT_ID();

INSERT INTO resume_version (resume_id, version_no, content, content_hash, source, char_count)
VALUES (@r_alice, 1,
'小 A | Java 后端开发工程师
教育背景
某大学 计算机科学与技术 本科 2018-2022
工作经历
某科技 Java 开发 2022.07-至今
- 负责订单系统后端开发，支撑日均 30 万单
- 优化接口耗时从 320ms 降至 85ms
专业技能
Java、Spring Boot、MySQL、Redis、Kafka',
REPEAT('a', 64), 'PASTE', 180);
SET @v_alice = LAST_INSERT_ID();

INSERT INTO job_description (user_id, title, company, content, content_hash, parse_status, parsed_source)
VALUES (@u_alice, 'Java 后端开发工程师', '某科技',
'Java 后端开发工程师（3-5 年）\n1. 本科及以上学历\n2. 3 年以上 Java 开发经验，熟练掌握 Spring Boot、MySQL\n3. 熟悉 Redis、Kafka\n4. 有微服务经验',
REPEAT('b', 64), 'SUCCESS', 'RULE');
SET @jd_alice = LAST_INSERT_ID();

INSERT INTO analysis_report
  (user_id, resume_version_id, jd_id, status, overall_score, grade, scoring_version, cache_key,
   jd_parsed_json, resume_parsed_json, dimension_json, keyword_json, weights_json)
VALUES (@u_alice, @v_alice, @jd_alice, 'SUCCESS', 82.5, 'GOOD', 'v1', REPEAT('c', 64),
'{"jobTitle":"Java 后端开发工程师"}',
'{"totalYears":3.5}',
'[{"key":"skillCoverage","name":"技能覆盖","score":80.0,"weight":0.35,"effectiveWeight":0.35,"applicable":true,"detail":"7 项要求命中 6 项"},{"key":"experienceMatch","name":"经验年限","score":100.0,"weight":0.15,"effectiveWeight":0.15,"applicable":true,"detail":"3.5 年 / 要求 3 年"},{"key":"projectRelevance","name":"项目相关度","score":60.0,"weight":0.20,"effectiveWeight":0.20,"applicable":true,"detail":"技术栈重合度较高"},{"key":"quantifiedImpact","name":"量化成果","score":85.0,"weight":0.10,"effectiveWeight":0.10,"applicable":true,"detail":"多处量化"},{"key":"structureCompleteness","name":"结构完整度","score":80.0,"weight":0.10,"effectiveWeight":0.10,"applicable":true,"detail":"缺项目板块"},{"key":"educationMatch","name":"学历匹配","score":100.0,"weight":0.10,"effectiveWeight":0.10,"applicable":true,"detail":"本科达标"}]',
'{"hits":[{"name":"Java","importance":"HIGH","matchedBy":"EXACT","evidence":"Java 后端开发"},{"name":"Spring Boot","importance":"HIGH","matchedBy":"EXACT","evidence":null},{"name":"MySQL","importance":"HIGH","matchedBy":"EXACT","evidence":null},{"name":"Redis","importance":"MEDIUM","matchedBy":"EXACT","evidence":null},{"name":"Kafka","importance":"MEDIUM","matchedBy":"EXACT","evidence":null}],"partials":[],"missing":[{"name":"微服务","importance":"MEDIUM","matchedBy":"MISSING","evidence":null}]}',
'{"configured":{},"effective":{},"notApplicable":[]}');
SET @rep_alice = LAST_INSERT_ID();

-- Bob 的（分数最高，热度应该最高）
INSERT INTO resume (user_id, title, version_count) VALUES (@u_bob, 'Java后端-资深版', 1);
SET @r_bob = LAST_INSERT_ID();
INSERT INTO resume_version (resume_id, version_no, content, content_hash, source, char_count)
VALUES (@r_bob, 1, '老王 | 资深 Java 工程师，5 年经验，主导过多个高并发系统', REPEAT('d', 64), 'PASTE', 30);
SET @v_bob = LAST_INSERT_ID();

INSERT INTO job_description (user_id, title, company, content, content_hash, parse_status, parsed_source)
VALUES (@u_bob, 'Java 后端开发工程师', '某大厂', '要求 5 年经验，精通 Java、分布式架构', REPEAT('e', 64), 'SUCCESS', 'RULE');
SET @jd_bob = LAST_INSERT_ID();

INSERT INTO analysis_report
  (user_id, resume_version_id, jd_id, status, overall_score, grade, scoring_version, cache_key,
   jd_parsed_json, resume_parsed_json, dimension_json, keyword_json, weights_json)
VALUES (@u_bob, @v_bob, @jd_bob, 'SUCCESS', 91.2, 'EXCELLENT', 'v1', REPEAT('f', 64),
'{"jobTitle":"Java 后端开发工程师"}', '{"totalYears":5.0}', '[]', '{"hits":[],"partials":[],"missing":[]}', '{}');
SET @rep_bob = LAST_INSERT_ID();

-- Carol 的（前端）
INSERT INTO resume (user_id, title, version_count) VALUES (@u_carol, '前端-2年', 1);
SET @r_carol = LAST_INSERT_ID();
INSERT INTO resume_version (resume_id, version_no, content, content_hash, source, char_count)
VALUES (@r_carol, 1, 'Carol | 前端开发工程师，2 年 Vue 经验', REPEAT('g', 64), 'PASTE', 25);
SET @v_carol = LAST_INSERT_ID();
INSERT INTO job_description (user_id, title, company, content, content_hash, parse_status, parsed_source)
VALUES (@u_carol, '前端开发工程师', '某公司', '要求熟悉 Vue、TypeScript', REPEAT('h', 64), 'SUCCESS', 'RULE');
SET @jd_carol = LAST_INSERT_ID();
INSERT INTO analysis_report
  (user_id, resume_version_id, jd_id, status, overall_score, grade, scoring_version, cache_key,
   jd_parsed_json, resume_parsed_json, dimension_json, keyword_json, weights_json)
VALUES (@u_carol, @v_carol, @jd_carol, 'SUCCESS', 68.3, 'FAIR', 'v1', REPEAT('i', 64),
'{"jobTitle":"前端开发工程师"}', '{"totalYears":2.0}', '[]', '{"hits":[],"partials":[],"missing":[]}', '{}');
SET @rep_carol = LAST_INSERT_ID();

-- David 的（数据分析）
INSERT INTO resume (user_id, title, version_count) VALUES (@u_david, '数据分析-4年', 1);
SET @r_david = LAST_INSERT_ID();
INSERT INTO resume_version (resume_id, version_no, content, content_hash, source, char_count)
VALUES (@r_david, 1, 'David | 数据分析师，4 年经验，SQL/Python/Tableau', REPEAT('j', 64), 'PASTE', 30);
SET @v_david = LAST_INSERT_ID();
INSERT INTO job_description (user_id, title, company, content, content_hash, parse_status, parsed_source)
VALUES (@u_david, '数据分析师', '某公司', '要求 3 年经验，精通 SQL、Python', REPEAT('k', 64), 'SUCCESS', 'RULE');
SET @jd_david = LAST_INSERT_ID();
INSERT INTO analysis_report
  (user_id, resume_version_id, jd_id, status, overall_score, grade, scoring_version, cache_key,
   jd_parsed_json, resume_parsed_json, dimension_json, keyword_json, weights_json)
VALUES (@u_david, @v_david, @jd_david, 'SUCCESS', 75.6, 'GOOD', 'v1', REPEAT('l', 64),
'{"jobTitle":"数据分析师"}', '{"totalYears":4.0}', '[]', '{"hits":[],"partials":[],"missing":[]}', '{}');
SET @rep_david = LAST_INSERT_ID();

-- Emma 的（算法，低分）
INSERT INTO resume (user_id, title, version_count) VALUES (@u_emma, '算法-应届', 1);
SET @r_emma = LAST_INSERT_ID();
INSERT INTO resume_version (resume_id, version_no, content, content_hash, source, char_count)
VALUES (@r_emma, 1, 'Emma | 应届生，算法方向，做过两个课程项目', REPEAT('m', 64), 'PASTE', 24);
SET @v_emma = LAST_INSERT_ID();
INSERT INTO job_description (user_id, title, company, content, content_hash, parse_status, parsed_source)
VALUES (@u_emma, '算法工程师', '某公司', '要求硕士，熟悉深度学习', REPEAT('n', 64), 'SUCCESS', 'RULE');
SET @jd_emma = LAST_INSERT_ID();
INSERT INTO analysis_report
  (user_id, resume_version_id, jd_id, status, overall_score, grade, scoring_version, cache_key,
   jd_parsed_json, resume_parsed_json, dimension_json, keyword_json, weights_json)
VALUES (@u_emma, @v_emma, @jd_emma, 'SUCCESS', 42.1, 'WEAK', 'v1', REPEAT('o', 64),
'{"jobTitle":"算法工程师"}', '{"totalYears":0.0}', '[]', '{"hits":[],"partials":[],"missing":[]}', '{}');
SET @rep_emma = LAST_INSERT_ID();

-- ---------------------------------------------------------------------
-- 3. 审核记录（全部 PASSED —— 演示数据不含个人信息）
-- ---------------------------------------------------------------------
INSERT INTO privacy_review (user_id, scene, status, hit_count, hit_detail, rule_version, content_hash)
VALUES
(@u_alice, 'POST_PUBLISH', 'PASSED', 0, '[]', 'v1', REPEAT('1', 64)),
(@u_bob,   'POST_PUBLISH', 'PASSED', 0, '[]', 'v1', REPEAT('2', 64)),
(@u_carol, 'POST_PUBLISH', 'PASSED', 0, '[]', 'v1', REPEAT('3', 64)),
(@u_david, 'POST_PUBLISH', 'PASSED', 0, '[]', 'v1', REPEAT('4', 64)),
(@u_emma,  'POST_PUBLISH', 'PASSED', 0, '[]', 'v1', REPEAT('5', 64));

SET @pr_alice = (SELECT id FROM privacy_review WHERE user_id = @u_alice ORDER BY id DESC LIMIT 1);
SET @pr_bob   = (SELECT id FROM privacy_review WHERE user_id = @u_bob   ORDER BY id DESC LIMIT 1);
SET @pr_carol = (SELECT id FROM privacy_review WHERE user_id = @u_carol ORDER BY id DESC LIMIT 1);
SET @pr_david = (SELECT id FROM privacy_review WHERE user_id = @u_david ORDER BY id DESC LIMIT 1);
SET @pr_emma  = (SELECT id FROM privacy_review WHERE user_id = @u_emma  ORDER BY id DESC LIMIT 1);

-- ---------------------------------------------------------------------
-- 4. 帖子
--
-- ★ created_at 故意设置成不同的时间，用来验证"时间衰减"。
--   Bob 的帖子发得最久（3 天前）但互动最多；
--   Emma 的刚发（5 分钟前）但零互动。
--   看热度榜时能观察衰减效果。
--
-- ★ hot_score 先置 0 —— 应用启动后定时任务会算出来。
--   也可以立即触发一次（见文末说明）。
-- ---------------------------------------------------------------------
INSERT INTO post
  (user_id, report_id, privacy_review_id, title, content, resume_content, report_snapshot,
   overall_score, target_position, years_of_experience, is_anonymous, status,
   view_count, comment_count, up_count, down_count, hot_score, created_at)
VALUES
(@u_bob, @rep_bob, @pr_bob,
 '5 年 Java 后端，简历投大厂全挂，求大佬指点',
 '之前在小公司做 CRUD，想跳大厂但简历过不了初筛。',
 '老王 | 资深 Java 工程师\n工作经历\n某科技 高级 Java 开发 2020.03-至今\n- 主导订单中台重构，QPS 从 800 提升至 5000\n- 设计分布式事务方案，覆盖 12 个服务\n- 带 4 人小组完成支付系统迁移\n专业技能\nJava、Spring Cloud、MySQL、Redis、Kafka、Kubernetes',
 '{"overallScore":91.2,"grade":"EXCELLENT","dimensions":[],"keywordAnalysis":{"hits":[],"partials":[],"missing":[]}}',
 91.2, 'Java 后端开发工程师', 5, 0, 'PUBLISHED',
 2380, 4, 42, 2, 0, DATE_SUB(NOW(), INTERVAL 3 DAY)),

(@u_alice, @rep_alice, @pr_alice,
 '3 年 Java 后端，简历有什么可以改的？',
 '主要投中小厂，投了十几家只面了两家。',
 '小 A | Java 后端开发工程师\n教育背景\n某大学 计算机科学与技术 本科 2018-2022\n工作经历\n某科技 Java 开发 2022.07-至今\n- 负责订单系统后端开发，支撑日均 30 万单\n- 优化接口耗时从 320ms 降至 85ms\n专业技能\nJava、Spring Boot、MySQL、Redis、Kafka',
 '{"overallScore":82.5,"grade":"GOOD","dimensions":[],"keywordAnalysis":{"hits":[],"partials":[],"missing":[]}}',
 82.5, 'Java 后端开发工程师', 3, 1, 'PUBLISHED',
 1206, 3, 28, 1, 0, DATE_SUB(NOW(), INTERVAL 1 DAY)),

(@u_david, @rep_david, @pr_david,
 '数据分析 4 年想转大厂，简历求看',
 '主要做业务分析，SQL 比较熟，Python 一般。',
 'David | 数据分析师\n工作经历\n某公司 数据分析 2020.07-至今\n- 搭建经营指标看板，覆盖 20 个核心指标\n- 用 Python 做用户分群分析，提升复购 12%\n专业技能\nSQL、Python、Tableau、Excel',
 '{"overallScore":75.6,"grade":"GOOD","dimensions":[],"keywordAnalysis":{"hits":[],"partials":[],"missing":[]}}',
 75.6, '数据分析师', 4, 0, 'PUBLISHED',
 486, 1, 12, 0, 0, DATE_SUB(NOW(), INTERVAL 8 HOUR)),

(@u_carol, @rep_carol, @pr_carol,
 '前端 2 年，简历这样写够吗？',
 'Vue 为主，没写过 React，会不会影响投递。',
 'Carol | 前端开发工程师\n工作经历\n某公司 前端开发 2023.07-至今\n- 负责后台管理系统前端开发\n- 使用 Vue3 + Element Plus 搭建 12 个页面\n专业技能\nVue、JavaScript、Element Plus、Git',
 '{"overallScore":68.3,"grade":"FAIR","dimensions":[],"keywordAnalysis":{"hits":[],"partials":[],"missing":[]}}',
 68.3, '前端开发工程师', 2, 0, 'PUBLISHED',
 312, 0, 8, 0, 0, DATE_SUB(NOW(), INTERVAL 2 HOUR)),

(@u_emma, @rep_emma, @pr_emma,
 '应届算法，简历好像没什么可写的',
 '只有两个课程项目，不知道该写什么。',
 'Emma | 应届生\n教育背景\n某大学 计算机 本科 2021-2025\n项目经历\n课程项目：图像分类（PyTorch）\n课程项目：推荐系统（协同过滤）',
 '{"overallScore":42.1,"grade":"WEAK","dimensions":[],"keywordAnalysis":{"hits":[],"partials":[],"missing":[]}}',
 42.1, '算法工程师', 0, 1, 'PUBLISHED',
 78, 0, 3, 0, 0, DATE_SUB(NOW(), INTERVAL 5 MINUTE));

-- 记下帖子 id
SET @p_bob   = (SELECT id FROM post WHERE user_id = @u_bob   ORDER BY id DESC LIMIT 1);
SET @p_alice = (SELECT id FROM post WHERE user_id = @u_alice ORDER BY id DESC LIMIT 1);
SET @p_david = (SELECT id FROM post WHERE user_id = @u_david ORDER BY id DESC LIMIT 1);
SET @p_carol = (SELECT id FROM post WHERE user_id = @u_carol ORDER BY id DESC LIMIT 1);
SET @p_emma  = (SELECT id FROM post WHERE user_id = @u_emma  ORDER BY id DESC LIMIT 1);

-- ---------------------------------------------------------------------
-- 5. 评论
--
-- ★ 注意 comment_count 和实际评论数要一致 ——
--   上面 post 表里写的数字是"看起来自然"的值，
--   但实际插入的评论数少于那个数字（脚本简化）。
--   ★ 这不影响功能测试，只是"评论数"和"评论列表"对不上。
--     真正测试时你可以手动发几条评论，观察计数是否正确变化。
-- ---------------------------------------------------------------------
INSERT INTO comment (post_id, user_id, parent_id, root_id, section, content, like_count, useful_count, status, created_at)
VALUES
-- Bob 的帖子下的评论
(@p_bob, @u_alice, NULL, NULL, 'EXPERIENCE', 'QPS 800→5000 这个数据很有说服力，但最好写清楚是你一个人做的还是团队做的', 12, 18, 'PUBLISHED', DATE_SUB(NOW(), INTERVAL 2 DAY)),
(@p_bob, @u_carol, NULL, NULL, 'SKILLS', 'K8s 只写了会，建议补一句用过什么场景，比如滚动发布、资源限制', 6, 9, 'PUBLISHED', DATE_SUB(NOW(), INTERVAL 1 DAY)),

-- Alice 的帖子下的评论
(@p_alice, @u_bob, NULL, NULL, 'PROJECT', '你没有项目经历板块，建议把工作里的订单系统拆出来单独写', 18, 24, 'PUBLISHED', DATE_SUB(NOW(), INTERVAL 20 HOUR)),
(@p_alice, @u_david, NULL, NULL, 'EXPERIENCE', '优化耗时 320→85ms 很好，可以再补一句怎么做的（索引？缓存？）', 9, 14, 'PUBLISHED', DATE_SUB(NOW(), INTERVAL 15 HOUR)),

-- David 的帖子
(@p_david, @u_bob, NULL, NULL, 'SKILLS', 'Python 一般的话建议别写太细，突出 SQL 和指标体系搭建能力', 5, 7, 'PUBLISHED', DATE_SUB(NOW(), INTERVAL 6 HOUR));

-- 回复（二级）
SET @c_bob_1 = (SELECT id FROM comment WHERE post_id = @p_bob AND user_id = @u_alice ORDER BY id DESC LIMIT 1);
INSERT INTO comment (post_id, user_id, parent_id, root_id, section, content, like_count, useful_count, status, created_at)
VALUES (@p_bob, @u_bob, @c_bob_1, @c_bob_1, 'EXPERIENCE',
        '主要是我带的，方案设计加落地都是我，两个人配合', 3, 4, 'PUBLISHED', DATE_SUB(NOW(), INTERVAL 1 DAY));

SET @c_alice_1 = (SELECT id FROM comment WHERE post_id = @p_alice AND user_id = @u_bob ORDER BY id DESC LIMIT 1);
INSERT INTO comment (post_id, user_id, parent_id, root_id, section, content, like_count, useful_count, status, created_at)
VALUES (@p_alice, @u_alice, @c_alice_1, @c_alice_1, 'PROJECT',
        '明白了，我拆出来试试，谢谢！', 1, 1, 'PUBLISHED', DATE_SUB(NOW(), INTERVAL 18 HOUR));

-- ---------------------------------------------------------------------
-- 6. 投票
--
-- ★ 唯一键 uk_vote 保证不重复。
--   这里用 INSERT IGNORE，重复执行脚本时不会报错。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO vote (user_id, target_type, target_id, vote_type) VALUES
-- Bob 的帖子：4 人点赞
(@u_alice, 'POST', @p_bob, 'UP'),
(@u_carol, 'POST', @p_bob, 'UP'),
(@u_david, 'POST', @p_bob, 'UP'),
(@u_emma,  'POST', @p_bob, 'UP'),

-- Alice 的帖子：2 人点赞、1 人踩
(@u_bob,   'POST', @p_alice, 'UP'),
(@u_david, 'POST', @p_alice, 'UP'),
(@u_carol, 'POST', @p_alice, 'DOWN'),

-- David 的帖子：2 赞
(@u_bob,   'POST', @p_david, 'UP'),
(@u_alice, 'POST', @p_david, 'UP'),

-- Carol 的帖子：1 赞
(@u_bob,   'POST', @p_carol, 'UP'),

-- 评论投票
(@u_bob,   'COMMENT', @c_bob_1, 'USEFUL'),
(@u_carol, 'COMMENT', @c_bob_1, 'USEFUL');

-- ---------------------------------------------------------------------
-- 完成
-- ---------------------------------------------------------------------
SELECT '演示数据插入完成' AS result;
SELECT
  (SELECT COUNT(*) FROM app_user)   AS users,
  (SELECT COUNT(*) FROM post)       AS posts,
  (SELECT COUNT(*) FROM comment)    AS comments,
  (SELECT COUNT(*) FROM vote)       AS votes;

-- =====================================================================
-- 【下一步：让热度榜生效】
--
-- 方案一（推荐）：启动后端，等 10 分钟自动重建
--   后端有 @Scheduled(cron = "0 */10 * * * *") 会重建热度榜。
--
-- 方案二：立即触发
--   数据插完后，先随便点一次"热度"排序 ——
--   此时 ZSET 是空的，会回退到 MySQL 的 hot_score 排序。
--   ★ 但 hot_score 还是 0，所以顺序会按 createdAt。
--
--   ★ 想立刻看到正确排序，有两种方式：
--     1. 投票触发：在浏览器里给任意帖子点个赞，该帖会重算并写入 ZSET
--     2. 等待定时任务（10 分钟内）
--
--   ★ 或者直接改 MySQL 里的 hot_score 模拟（不推荐，和 ZSET 会不一致）：
--     UPDATE post SET hot_score = overall_score WHERE status = 'PUBLISHED';
--     （这只是让"带筛选的热度排序"看起来有数据，
--       全站热度榜仍然依赖 ZSET，需要定时任务或投票触发）
--
-- 【★ 为什么脚本不直接往 Redis 写 ZSET？】
--   SQL 脚本写不了 Redis。
--   而且热度分应该由 Java 的 compute() 算 ——
--   在 SQL 里重写一遍公式，两处不一致的风险很大。
-- =====================================================================
