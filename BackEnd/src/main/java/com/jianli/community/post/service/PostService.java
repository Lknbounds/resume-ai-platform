package com.jianli.community.post.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianli.analysis.dto.AnalysisReportVO;
import com.jianli.analysis.entity.AnalysisReport;
import com.jianli.analysis.repository.AnalysisReportRepository;
import com.jianli.common.BizException;
import com.jianli.common.ErrorCode;
import com.jianli.common.PageResult;
import com.jianli.community.post.dto.CreatePostRequest;
import com.jianli.community.post.dto.PostVO;
import com.jianli.community.post.entity.Post;
import com.jianli.community.post.repository.PostRepository;
import com.jianli.community.vote.repository.VoteRepository;
import com.jianli.jd.entity.JobDescription;
import com.jianli.jd.repository.JobDescriptionRepository;
import com.jianli.privacy.service.PrivacyReviewService;
import com.jianli.user.entity.AppUser;
import com.jianli.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  社区帖子的业务核心：发布、列表、详情、删除。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 发布流程（本类最重要的方法）】
 *
 *      ① 校验报告归属 + 状态（必须是 SUCCESS/PARTIAL 且是你的）
 *      ② 校验没重复发布（同一报告只能发一次）
 *      ③ 【审核】标题+正文+简历 拼起来，检测个人信息
 *         └─ 不通过 → 抛 43002 + 命中详情（★ 审核记录已独立提交）
 *      ④ 通过 → 存帖子（含简历正文快照 + 报告 JSON 快照）
 *
 *  ★ 第 ③ 步是整个社区模块的准入闸门。
 *    没有它，用户会把带手机号的简历直接发到公开社区。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    /** 简历正文做快照时的最大长度（防止超大文本撑爆帖子表） */
    private static final int MAX_RESUME_SNAPSHOT = 50_000;

    private final PostRepository postRepository;
    private final AnalysisReportRepository reportRepository;
    private final JobDescriptionRepository jdRepository;
    private final PrivacyReviewService privacyReviewService;
    private final VoteRepository voteRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final com.jianli.community.hot.HotScoreService hotScoreService;

    // ═══════════════════════════════════════════════════════════════
    //  【一、发布】
    // ═══════════════════════════════════════════════════════════════

    /**
     * 发布帖子。
     *
     * @throws BizException REPORT_NOT_OWNED       —— 报告不是你的
     * @throws BizException ANALYSIS_NOT_READY     —— 报告没算完
     * @throws BizException POST_DUPLICATED        —— 这份报告已经发过了
     * @throws BizException POST_PRIVACY_REJECTED  —— ★ 命中个人信息（43002）
     */
    @Transactional
    public PostVO publish(Long userId, CreatePostRequest req) {

        // ───── ① 校验报告 ─────
        AnalysisReport report = reportRepository.findByIdAndUserId(req.reportId(), userId)
                .orElseThrow(() -> new BizException(ErrorCode.REPORT_NOT_OWNED));

        if (!List.of("SUCCESS", "PARTIAL").contains(report.getStatus())) {
            throw new BizException(ErrorCode.ANALYSIS_NOT_READY);
        }

        // ───── ② 防重复发布 ─────
        if (postRepository.existsByReportId(report.getId())) {
            throw new BizException(ErrorCode.POST_DUPLICATED);
        }

        // ───── ③ ★ 个人信息审核 ─────
        /*
         * ★ 审核哪些内容？
         *     标题 + 补充说明 + 简历正文
         *
         * ★ 为什么不审 JD？
         *    JD 是公开的岗位描述，不含用户个人信息。
         *
         * ★ 拼接顺序重要吗？
         *    不重要 —— 命中的位置是相对【拼接后的文本】。
         *    但前端要能定位，所以最好让用户主要改 resumeContent，
         *    而 hits 的 start/end 是相对整个拼接串的。
         *
         *    ★ 这是个小瑕疵：前端拿到偏移量后，
         *      需要在对应 textarea 里减去前面各段长度。
         *      本项目简化处理：预检时单独传 resumeContent，
         *      发布时的 43002 只用于提示，不用于精确定位。
         */
        String toCheck = String.join("\n",
                nvl(req.title()),
                nvl(req.content()),
                nvl(req.resumeContent()));

        var outcome = privacyReviewService.checkAndRecord(userId, "POST_PUBLISH", toCheck);

        if (!outcome.passed()) {
            /*
             * ★ 审核记录已经在 REQUIRES_NEW 事务里提交了 ——
             *   这里抛异常回滚帖子相关操作，但审计留痕保住了。
             *
             * ★ 错误体里带命中详情：前端要弹出"命中项 + 高亮"对话框。
             */
            log.info("发帖被拒（个人信息）, userId={}, 命中={}", userId, outcome.hits().size());

            throw new BizException(ErrorCode.POST_PRIVACY_REJECTED,
                    new CreatePostRequest.PrivacyRejected(
                            outcome.review().getId(),
                            "POST_PUBLISH",
                            com.jianli.privacy.PrivacyDetector.RULE_VERSION,
                            outcome.hits()));
        }

        // ───── ④ 组装并保存 ─────
        Post post = new Post();
        post.setUserId(userId);
        post.setReportId(report.getId());
        post.setPrivacyReviewId(outcome.review().getId());
        post.setTitle(req.title().trim());
        post.setContent(req.content() == null ? null : req.content().trim());
        post.setResumeContent(truncate(req.resumeContent(), MAX_RESUME_SNAPSHOT));
        post.setReportSnapshot(buildSnapshot(report));
        post.setOverallScore(report.getOverallScore() == null
                ? BigDecimal.ZERO : report.getOverallScore());
        post.setIsAnonymous(Boolean.TRUE.equals(req.isAnonymous()));
        post.setStatus("PUBLISHED");

        // ★ 冗余岗位信息，用于社区筛选
        /*
         * ★ 这里不能写成 jdRepository...ifPresent(jd -> post.setTargetPosition(...))
         *   因为下面还有 post = postRepository.save(post) ——
         *   post 被重新赋值后就不是"effectively final"，
         *   lambda 里捕获它会编译报错。
         *
         * ★ 解法：先把值取出来，再赋给 post。
         */
        String jdTitle = jdRepository.findById(report.getJdId())
                .map(JobDescription::getTitle)
                .orElse(null);
        post.setTargetPosition(jdTitle);

        post.setYearsOfExperience(extractYears(report));

        post = postRepository.save(post);

        /*
         * ★ 新帖写入热度榜。
         *
         *   新帖 base 分一般是 0（没有赞/评论/浏览），
         *   算出 hot = 0 / (0+2)^1.5 = 0。
         *   ★ 为什么还要写？
         *     让它出现在 ZSET 里，这样：
         *       1. 榜单清理时能识别"这是有效帖子"
         *       2. 第一次投票后 updateZset 能找到它
         *   （其实 ZADD 是 upsert，不写也行，但显式写入更清晰）
         */
        double hot = hotScoreService.compute(post);
        post.setHotScore(hot);
        postRepository.save(post);
        hotScoreService.updateZset(post.getId(), hot);

        log.info("发帖成功, userId={}, postId={}, 匿名={}", userId, post.getId(), post.getIsAnonymous());

        return toVO(post, userId, true);
    }

    // ═══════════════════════════════════════════════════════════════
    //  【二、列表】
    // ═══════════════════════════════════════════════════════════════

    /**
     * 社区列表。
     *
     * @param position 岗位筛选（等值）。null = 不筛
     * @param expMin   最低年限。null = 不限
     * @param expMax   最高年限。null = 不限
     * @param keyword  搜索标题/正文。null = 不搜
     * @param sort     "hot" 热度 / "new" 最新
     * @param page     页码（1 开始）
     * @param size     每页条数
     * @param currentUserId 当前用户（未登录为 null），用于标记 isOwner / myVote
     */
    @Transactional(readOnly = true)
    public PageResult<PostVO> list(String position, Integer expMin, Integer expMax,
                                    String keyword, String sort,
                                    int page, int size, Long currentUserId) {

        int safePage = Math.max(page - 1, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);

        /*
         * ═══════════════════════════════════════════════════════════
         *  【★★ 热度排序：两条路径，按是否有筛选条件分流】
         * ═══════════════════════════════════════════════════════════
         *
         *  ★ 为什么不能一律用 ZSET？
         *    ZSET 里只有 postId 和分数，没有岗位/年限信息。
         *    要"筛选 Java 岗位 + 3-5 年，再按热度排"，
         *    Redis 无法完成 —— 必须回数据库。
         *
         *  路径 A（无筛选）：ZSET 取 id → 批量查帖
         *      优点：Redis 直接给出排序结果，O(log N)
         *      缺点：无法筛选
         *
         *  路径 B（有筛选 / ZSET 不可用）：MySQL ORDER BY hot_score
         *      优点：支持任意筛选组合
         *      缺点：慢一些（但有索引）
         *
         *  ★ 判定条件：
         *     sort=hot &&
         *     position 为空 &&
         *     expMin 为空 &&
         *     expMax 为空 &&
         *     keyword 为空
         *     → 走路径 A
         */
        boolean hasFilter = blankToNull(position) != null
                || expMin != null
                || expMax != null
                || blankToNull(keyword) != null;

        if ("hot".equals(sort) && !hasFilter) {
            PageResult<PostVO> fromZset = listByHotZset(safePage, safeSize, currentUserId);

            /*
             * ★ ZSET 返回空（Redis 挂了 / 还没有数据）→ 回退到 MySQL。
             *
             *   什么时候会这样？
             *     1. 刚部署，ZSET 还没建立（第一次定时任务还没跑）
             *     2. Redis 挂了
             *     3. 确实没有帖子
             *
             *   ★ 调用方不区分这三种 —— 统一回退，保证功能可用。
             */
            if (fromZset != null) {
                return fromZset;
            }
        }

        // ═══════════ 路径 B：MySQL 排序 ═══════════
        /*
         * ★ 为什么 hot 要加 createdAt 作为第二排序？
         *   热度分是浮点数，可能有相同值。
         *   不加第二排序键的话，相同分数的顺序不确定 ——
         *   翻页时可能出现"同一条出现在两页"。
         *   （这是分页排序的经典坑：排序键必须能唯一确定顺序）
         */
        Sort sortBy = "hot".equals(sort)
                ? Sort.by(Sort.Direction.DESC, "hotScore", "createdAt")
                : Sort.by(Sort.Direction.DESC, "createdAt");

        Page<Post> p = postRepository.search(
                blankToNull(position),
                expMin, expMax,
                blankToNull(keyword),
                PageRequest.of(safePage, safeSize, sortBy));

        return assemblePage(p, safePage, safeSize, currentUserId);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【路径 A：从 ZSET 读热度榜】
     * ═══════════════════════════════════════════════════════════════
     *
     * @return 组装好的分页结果；ZSET 为空时返回 null（调用方回退 MySQL）
     */
    private PageResult<PostVO> listByHotZset(int safePage, int safeSize, Long currentUserId) {

        int offset = safePage * safeSize;

        // ① 从 ZSET 取 id（已按热度排序）
        List<Long> ids = hotScoreService.topIds(offset, safeSize);

        if (ids.isEmpty()) {
            return null;   // ★ 返回 null 表示"没法用这条路"，不是"没有数据"
        }

        // ② 按 id 批量查帖
        List<Post> posts = postRepository.findAllById(ids);

        /*
         * ★ 关键：要按 ZSET 的顺序重新排列。
         *
         *   findAllById 返回的顺序【不保证】和传入的 id 顺序一致
         *   （实际是按主键升序）。
         *
         *   ★ 如果直接用 posts 的顺序，热度榜就变成了"按 id 排序"。
         *     这是个很容易漏的 bug —— 榜单看起来有数据，但顺序是错的。
         */
        Map<Long, Post> byId = posts.stream()
                .collect(Collectors.toMap(Post::getId, p -> p, (a, b) -> a));

        List<Post> ordered = new ArrayList<>();
        for (Long id : ids) {
            Post p = byId.get(id);
            /*
             * ★ 帖子可能已被删除（软删）或已超过 30 天，
             *   但 ZSET 里还有残留 → byId.get 返回 null → 跳过。
             *
             *   下次定时任务会把它清理掉。
             */
            if (p != null && "PUBLISHED".equals(p.getStatus())) {
                ordered.add(p);
            }
        }

        if (ordered.isEmpty()) {
            return null;
        }

        // ③ 总数：用 ZSET 的 size（近似）
        long total = hotScoreService.size();

        return new PageResult<>(
                toVOs(ordered, currentUserId, false),
                total,
                safePage + 1,
                safeSize,
                (int) Math.ceil((double) total / safeSize));
    }

    /**
     * 组装分页结果（路径 B 用）。
     */
    private PageResult<PostVO> assemblePage(Page<Post> p, int safePage, int safeSize,
                                             Long currentUserId) {
        return new PageResult<>(
                toVOs(p.getContent(), currentUserId, false),
                p.getTotalElements(),
                safePage + 1,
                safeSize,
                p.getTotalPages());
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【批量转 VO】★ 避免 N+1 的统一入口
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 为什么抽出来？
     *   list / myPosts / listByHotZset 三处都要做同样的事：
     *     批量查昵称 + 批量查我的投票 → 组装 VO
     *   不抽的话会有三份几乎一样的代码。
     *
     * ★ 这里的两个批量查询是本方法存在的核心理由：
     *   如果逐条查，10 条帖子 = 20 次 SQL。
     *   批量查是 2 次 SQL，与页大小无关。
     */
    private List<PostVO> toVOs(List<Post> posts, Long currentUserId, boolean withBody) {
        if (posts.isEmpty()) return List.of();

        Set<Long> userIds = posts.stream()
                .map(Post::getUserId)
                .collect(Collectors.toSet());

        Map<Long, String> nicknames = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getNickname));

        Map<Long, String> myVotes = loadMyVotes(currentUserId, posts);

        return posts.stream()
                .map(post -> toVO(post, currentUserId, withBody,
                        nicknames.get(post.getUserId()), myVotes.get(post.getId())))
                .toList();
    }

    /** 我的帖子 */
    @Transactional(readOnly = true)
    public PageResult<PostVO> myPosts(Long userId, int page, int size) {
        int safePage = Math.max(page - 1, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);

        Page<Post> p = postRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
                userId, "PUBLISHED", PageRequest.of(safePage, safeSize));

        return assemblePage(p, safePage, safeSize, userId);
    }

    // ═══════════════════════════════════════════════════════════════
    //  【三、详情】
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public PostVO detail(Long postId, Long currentUserId) {
        Post post = postRepository.findByIdAndStatus(postId, "PUBLISHED")
                .orElseThrow(() -> new BizException(ErrorCode.POST_NOT_VISIBLE));

        /*
         * ★ 浏览数 +1。
         *
         * ★ 为什么直接 UPDATE 而不走 Redis 缓冲？
         *   本项目帖子量小，一次 UPDATE 完全可以。
         *   文档里设计的"Redis 缓冲 + 定时回写"是为高并发准备的，
         *   现在没必要 —— 简单方案够用就先简单。
         *
         *   ★ 但如果每 10 分钟有几千次浏览，就该改成 Redis 了。
         */
        post.setViewCount(post.getViewCount() + 1);

        /*
         * ★ 本项目并发量很小，浏览数变化后直接重算热度。
         *
         *   浏览数本身会进入热度公式：
         *       log10(viewCount + 1) × 3
         *   如果只在定时任务里重算，用户看完帖子后热度榜最长要等 10 分钟
         *   才会反映这次浏览。即时更新更符合当前体量，也让排序行为更直观。
         */
        double hot = hotScoreService.compute(post);
        post.setHotScore(hot);
        postRepository.save(post);
        hotScoreService.updateZset(post.getId(), hot);

        String nickname = userRepository.findById(post.getUserId())
                .map(AppUser::getNickname).orElse("已注销用户");

        String myVote = currentUserId == null ? null
                : voteRepository.findFirstByUserIdAndTargetTypeAndTargetId(
                        currentUserId, "POST", postId)
                .map(v -> v.getVoteType()).orElse(null);

        return toVO(post, currentUserId, true, nickname, myVote);
    }

    // ═══════════════════════════════════════════════════════════════
    //  【四、删除】
    // ═══════════════════════════════════════════════════════════════

    /**
     * 删除帖子（软删除）。
     *
     * ★ 为什么是软删除而不是物理删除？
     *   1. 评论还在，物理删帖会让评论变成孤儿（或一起删掉，但那就无法追溯）
     *   2. 保留审计：内容违规时有据可查
     *   3. status='DELETED' 之后列表和详情都返回 404，用户体验上等于"没了"
     */
    @Transactional
    public void delete(Long userId, Long postId) {
        Post post = postRepository.findByIdAndStatus(postId, "PUBLISHED")
                .orElseThrow(() -> new BizException(ErrorCode.POST_NOT_VISIBLE));

        if (!post.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        post.setStatus("DELETED");
        log.info("帖子已删除, postId={}, userId={}", postId, userId);
    }

    // ═══════════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════════

    /** 构造报告快照 JSON */
    private String buildSnapshot(AnalysisReport report) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("overallScore", report.getOverallScore());
            snapshot.put("grade", report.getGrade());

            // 三个 JSON 列原样搬过去（它们已经是数组/对象）
            snapshot.put("dimensions", readJsonValue(report.getDimensionJson()));
            snapshot.put("keywordAnalysis", readJsonValue(report.getKeywordJson()));

            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.warn("构造报告快照失败, reportId={}", report.getId());
            return "{}";
        }
    }

    private Object readJsonValue(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 从报告的简历解析结果里取年限（用于社区筛选） */
    private int extractYears(AnalysisReport report) {
        if (report.getResumeParsedJson() == null) return 0;
        try {
            var node = objectMapper.readTree(report.getResumeParsedJson());
            var years = node.get("totalYears");
            return years == null ? 0 : (int) Math.round(years.asDouble());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 批量查"我对这批帖子的投票" */
    private Map<Long, String> loadMyVotes(Long userId, List<Post> posts) {
        if (userId == null || posts.isEmpty()) return Map.of();

        List<Long> ids = posts.stream().map(Post::getId).toList();

        return voteRepository.findByUserIdAndTargetTypeAndTargetIdIn(userId, "POST", ids)
                .stream()
                .collect(Collectors.toMap(
                        v -> v.getTargetId(),
                        v -> v.getVoteType(),
                        (a, b) -> a));   // 理论上不会重复
    }

    // ═══════════════════════════════════════════════════════════════
    //  VO 转换
    // ═══════════════════════════════════════════════════════════════

    private PostVO toVO(Post post, Long currentUserId, boolean withBody) {
        String nickname = userRepository.findById(post.getUserId())
                .map(AppUser::getNickname).orElse("已注销用户");

        String myVote = currentUserId == null ? null
                : voteRepository.findFirstByUserIdAndTargetTypeAndTargetId(
                        currentUserId, "POST", post.getId())
                .map(v -> v.getVoteType()).orElse(null);

        return toVO(post, currentUserId, withBody, nickname, myVote);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【组装 VO】★ 匿名处理的唯一入口
     * ═══════════════════════════════════════════════════════════════
     *
     * @param withBody 是否带简历正文和报告快照
     *                 ★ 列表不带（省流量），详情才带
     */
    private PostVO toVO(Post post, Long currentUserId, boolean withBody,
                        String nickname, String myVote) {

        boolean anonymous = Boolean.TRUE.equals(post.getIsAnonymous());
        boolean isOwner = currentUserId != null && currentUserId.equals(post.getUserId());

        /*
         * ★★ 匿名处理的三个要点：
         *
         *  1. 匿名时 author 里【userId 为 null】——
         *     不是"传了但不显示"，是根本不传。前端拿不到就泄露不了。
         *
         *  2. isOwner 仍然按真实 userId 判断 ——
         *     这样作者自己能看到删除按钮，但别人看不到。
         *     ★ 判断在服务端做，前端不参与。
         *
         *  3. ★ 作者看自己的匿名帖时，作者信息也是匿名的吗？
         *     是的 —— 保持一致性。作者自己知道这是自己发的
         *     （因为有删除按钮），但界面上不显示自己的昵称，
         *     避免截图时暴露。
         */
        PostVO.AuthorVO author = anonymous
                ? PostVO.AuthorVO.anonymousAuthor()
                : PostVO.AuthorVO.real(post.getUserId(), nickname);

        return new PostVO(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                author,
                withBody ? post.getResumeContent() : null,
                withBody ? parseSnapshot(post.getReportSnapshot()) : null,
                post.getOverallScore(),
                post.getTargetPosition(),
                post.getYearsOfExperience(),
                post.getUpCount(),
                post.getDownCount(),
                post.getCommentCount(),
                post.getViewCount(),
                myVote,
                isOwner,
                anonymous,
                post.getStatus(),
                post.getCreatedAt()
        );
    }

    private PostVO.ReportSnapshot parseSnapshot(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, PostVO.ReportSnapshot.class);
        } catch (Exception e) {
            log.debug("报告快照解析失败");
            return null;
        }
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
