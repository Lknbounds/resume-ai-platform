package com.jianli.resume.service;

import com.jianli.common.BizException;
import com.jianli.common.ErrorCode;
import com.jianli.common.PageResult;
import com.jianli.resume.dto.AddVersionRequest;
import com.jianli.resume.dto.CreateResumeRequest;
import com.jianli.resume.dto.ResumeDetailVO;
import com.jianli.resume.dto.ResumeSummaryVO;
import com.jianli.resume.dto.VersionDetailVO;
import com.jianli.resume.dto.VersionSummaryVO;
import com.jianli.resume.entity.Resume;
import com.jianli.resume.entity.ResumeVersion;
import com.jianli.resume.enums.VersionSource;
import com.jianli.resume.repository.ResumeRepository;
import com.jianli.resume.repository.ResumeVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  简历与版本的业务核心。
 *
 *  它负责：创建简历、加版本、查列表、查详情、改标题、删除。
 *  它不负责：版本对比（步骤 6）、分析（步骤 8）。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：一览】
 *
 *  ┌──────────────┬──────────────────────────────────────────────┐
 *  │ create       │ INSERT resume → INSERT version(v1)           │
 *  │              │ → UPDATE resume.current_version_id            │
 *  ├──────────────┼──────────────────────────────────────────────┤
 *  │ addVersion   │ 锁 resume → 查重 → INSERT version(vN)         │
 *  │              │ → UPDATE resume 指针/计数                     │
 *  ├──────────────┼──────────────────────────────────────────────┤
 *  │ list         │ SELECT resume 分页 → 逐条补 preview           │
 *  ├──────────────┼──────────────────────────────────────────────┤
 *  │ detail       │ SELECT resume → SELECT versions → 组装 VO      │
 *  ├──────────────┼──────────────────────────────────────────────┤
 *  │ getVersion   │ SELECT version → 校验归属 → 返回全文           │
 *  ├──────────────┼──────────────────────────────────────────────┤
 *  │ delete       │ DELETE resume（数据库级联删版本）              │
 *  └──────────────┴──────────────────────────────────────────────┘
 *
 *
 *  【★★ 这个类里最重要的三件事（面试可讲）】
 *
 *  ① 【越权校验放在 SQL 里】
 *     所有查询都带 user_id 条件（findByIdAndUserId），
 *     而不是"查出来再判断"。
 *     → 从物理上不可能读到别人的数据，也不可能忘记判断。
 *
 *  ② 【加版本时用行锁】
 *     findByIdForUpdate 锁住简历行，让"算版本号 + 插入"串行执行。
 *     → 两个标签页同时保存不会撞版本号。
 *
 *  ③ 【内容没变就不建版本】
 *     用 content_hash 判断，相同就拒绝。
 *     → 避免产生一堆一模一样的版本，污染时间线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    /**
     * 简历正文的最小字数（不含空白）。
     *
     * ★ 为什么是 50？
     *   太短的内容（比如"我会 Java"）没有分析价值 ——
     *   JD 对比、技能匹配、结构完整度全都算不出有意义的结果。
     *
     *   ★ 这个数字是产品决策，不是技术限制。
     *     前端也会用同样的阈值提示用户（见 ResumeListView）。
     *     ★ 两处必须一致，否则会出现"前端说够长、后端说太短"。
     */
    private static final int MIN_CHARS = 50;

    /**
     * 列表里版本的预览长度。
     *
     * ★ 只取前 80 个字符，够用户辨认是哪份简历了。
     *   太多会让列表响应变大，太少看不出内容。
     */
    private static final int PREVIEW_LENGTH = 80;

    /** 单页最大条数，防止前端传 size=10000 拖垮数据库 */
    private static final int MAX_PAGE_SIZE = 50;

    private final ResumeRepository resumeRepository;
    private final ResumeVersionRepository versionRepository;
    private final ResumeOriginalFileService resumeOriginalFileService;
    private final ResumeTextNormalizer normalizer;

    /**
     * 查最新分数。★ 接口定义在 resume 包，实现由 analysis 包提供。
     * 见 ResumeScoreLookup 的说明（依赖倒置）。
     */
    private final ResumeScoreLookup latestScoreProvider;

    // ═══════════════════════════════════════════════════════════════
    //  【一、新建简历】
    // ═══════════════════════════════════════════════════════════════

    /**
     * 创建简历，同时生成 v1。
     *
     * @param userId 当前登录用户
     * @param req    标题 + 正文
     * @return 简历详情（含版本列表，此时只有一个 v1）
     * @throws BizException RESUME_CONTENT_INVALID —— 正文太短
     *
     * 【★★ 为什么要在一个事务里做三步？】
     *
     *   步骤：INSERT resume → INSERT version → UPDATE resume 指针
     *
     *   如果不在事务里，中间任何一步失败都会留下脏数据：
     *     - 插了 resume 但没插 version → 简历没有内容（孤儿）
     *     - 插了 version 但没更新指针 → current_version_id 是 null，
     *       前端打开详情页不知道该显示哪一版
     *
     *   ★ 加 @Transactional，三步要么全成功要么全回滚。
     *     这是"一个业务操作 = 一个事务"的典型应用。
     *
     * ★ 为什么先 save resume 而不是先 save version？
     *   因为 version 需要 resume_id（外键 NOT NULL）。
     *   必须先有 resume 才能拿到它的自增 id。
     */
    @Transactional
    public ResumeDetailVO create(Long userId, CreateResumeRequest req) {

        /*
         * 【第一步：归一化 + 校验】
         *
         * ★ 为什么校验放在 Service 而不是 Controller 的注解上？
         *   因为要先归一化才能算准字数（见 CreateResumeRequest 的说明）。
         *   注解只能校验原始字符串。
         */
        String content = normalizeChecked(req.content());

        /*
         * 【第二步：插入简历主体】
         *
         * ★ versionCount 先设 0，等版本插完再改成 1。
         *   为什么不直接设 1？
         *   因为如果版本插入失败回滚了，至少语义上"简历有 0 个版本"是自洽的。
         *   （虽然有事务保证不会出现这种中间状态，但这样写意图更清晰）
         */
        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setTitle(req.title());

        /*
         * ★ 目标岗位：可能为 null（用户没填）。
         *
         * ★ 为什么要 trim()？
         *   用户可能输入 "  Java 后端  "，前后有空格。
         *   直接存会导致：
         *     1. 列表显示有多余空格
         *     2. 将来按岗位筛选时匹配不上
         *
         *   ★ 空字符串也同样处理成 null ——
         *     存 "" 和存 null 语义应该一致（都是"没填"）。
         *     统一成 null 可以让前端只判断一种情况。
         */
        resume.setTargetPosition(normalizeOptional(req.targetPosition()));
        resume.setVersionCount(0);

        /*
         * ★ save 返回的 resume 和传入的是同一个对象（JPA 特性），
         *   但 id 会被回填。所以下一步能用 resume.getId()。
         */
        resume = resumeRepository.save(resume);

        /*
         * 【第三步：插入 v1】
         *
         * parentVersionId 传 null —— 它是第一版，没有父版本。
         * changeNote 传"初始版本" —— 让时间线有可读的说明。
         */
        ResumeVersion v1 = buildVersion(
                resume.getId(),
                1,
                content,
                VersionSource.PASTE,
                null,
                "初始版本"
        );
        v1 = versionRepository.save(v1);

        /*
         * 【第四步：回写当前版本指针和计数】
         *
         * ★ 为什么不用再调一次 save？
         *   因为 resume 是"托管状态"（本次事务里查/存过的对象），
         *   Hibernate 会在提交时自动 UPDATE。
         *   调用 save 也无害，但没必要。
         *
         * ★ 这一步同时会触发 @PreUpdate，刷新 updatedAt。
         */
        resume.setCurrentVersionId(v1.getId());
        resume.setVersionCount(1);

        log.info("创建简历, userId={}, resumeId={}, charCount={}",
                userId, resume.getId(), v1.getCharCount());

        /*
         * 【第五步：返回详情】
         *
         * ★ 注意返回的是 ResumeDetailVO，不是刚建的 Resume 实体。
         *   前端拿到后可以直接跳详情页，不用再查一次。
         */
        return detailOf(resume);
    }

    // ═══════════════════════════════════════════════════════════════
    //  【二、新增版本】★ 本类最需要理解的方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 给已有简历加一个新版本。
     *
     * @param userId   当前登录用户
     * @param resumeId 简历 id
     * @param req      新正文 + 变更说明 + 来源
     * @return 新版本的完整详情（含正文）
     * @throws BizException RESUME_NOT_FOUND      —— 简历不存在或不是你的
     * @throws BizException RESUME_CONTENT_INVALID —— 正文太短
     * @throws BizException PARAM_INVALID          —— 内容与已有版本相同
     *
     * 【★★ 这个方法里有三个关键设计，逐个看】
     */
    @Transactional
    public VersionDetailVO addVersion(Long userId, Long resumeId, AddVersionRequest req) {

        /*
         * ═══════════════════════════════════════════════════════════
         *  【第一步：★ 加行锁查询简历】
         * ═══════════════════════════════════════════════════════════
         *
         * findByIdForUpdate → SELECT * FROM resume WHERE id = ? FOR UPDATE
         *
         * ★ 为什么要加锁？
         *   "算版本号 + 插入"必须原子完成。
         *   不加锁的话，两个请求可能都算出版本号 3，然后一个插入成功、
         *   另一个被唯一键拒绝 → 用户看到莫名的系统错误。
         *
         *   加锁之后，两个请求串行执行：第一个算 3 并插入，
         *   第二个拿到锁时已经能看到 3，算出来是 4。
         *
         * ★ 为什么用 .filter() 做归属校验而不是 if？
         *   因为 filter 让"查不到"和"不是你的"走同一个出口，
         *   都变成 Optional.empty() → 抛 RESUME_NOT_FOUND。
         *
         *   ★ 这比"先查再判断"少一层分支，而且不会漏写。
         *
         * ★ 如果简历不是这个用户的：
         *   锁是加上了，但事务会回滚，锁自然释放。
         *   不会造成死锁或锁泄漏。
         */
        Resume resume = resumeRepository.findByIdForUpdate(resumeId)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new BizException(ErrorCode.RESUME_NOT_FOUND));

        /*
         * ═══════════════════════════════════════════════════════════
         *  【第二步：归一化 + 校验】
         * ═══════════════════════════════════════════════════════════
         */
        String content = normalizeChecked(req.content());

        /*
         * ═══════════════════════════════════════════════════════════
         *  【第三步：★ 内容去重 —— 内容没变就不建新版本】
         * ═══════════════════════════════════════════════════════════
         *
         * ★ 为什么在"锁内"做这个检查？
         *   如果在锁外查，两个请求可能同时查到"没有重复"，
         *   然后都插入 —— 结果产生两个内容相同的版本。
         *
         *   锁内检查是安全的：拿到锁的那一刻，
         *   其他人不能插入这个简历的新版本。
         *
         * ★ 返回什么错误？
         *   PARAM_INVALID（10000）+ 自定义提示语。
         *
         *   ★ 为什么不用一个新错误码？
         *     因为这个场景前端只需要"提示一下就够"，
         *     不需要特殊处理分支。复用 10000 最省事。
         *     如果要精细，可以加一个 RESUME_UNCHANGED(40005)。
         */
        String hash = normalizer.sha256(content);
        if (versionRepository.findFirstByResumeIdAndContentHash(resumeId, hash).isPresent()) {
            throw new BizException(ErrorCode.PARAM_INVALID, null,
                    "内容与已有版本相同，未创建新版本");
        }

        /*
         * ═══════════════════════════════════════════════════════════
         *  【第四步：算下一个版本号】
         * ═══════════════════════════════════════════════════════════
         *
         * findMaxVersionNo 返回 SELECT COALESCE(MAX(version_no), 0)
         * 所以没有版本时返回 0，+1 得 1。
         *
         * ★ 因为已经在锁内，这里算出来的一定是准确的。
         *   如果并发，第二个请求会等第一个提交后再算。
         */
        int nextNo = versionRepository.findMaxVersionNo(resumeId) + 1;

        /*
         * ═══════════════════════════════════════════════════════════
         *  【第五步：插入新版本】
         * ═══════════════════════════════════════════════════════════
         *
         * parentVersionId = resume.getCurrentVersionId()
         *   → 记录"这一版是从当前版本改过来的"，形成版本链
         *
         * source 的兜底：
         *   req.source() 为 null 时默认 EDIT（用户手动改的）
         *   ★ 为什么不默认 PASTE？因为 PASTE 语义是"初次粘贴"，
         *     而加版本场景几乎都是"编辑后保存"。
         */
        VersionSource source = req.source() == null ? VersionSource.EDIT : req.source();

        ResumeVersion version = buildVersion(
                resumeId,
                nextNo,
                content,
                source,
                resolveParentVersionId(resumeId, resume.getCurrentVersionId(), req.parentVersionId()),
                req.changeNote()
        );
        version = versionRepository.save(version);

        /*
         * ═══════════════════════════════════════════════════════════
         *  【第六步：更新简历的指针和计数】
         * ═══════════════════════════════════════════════════════════
         *
         * ★ 这两个必须和插入版本在同一事务里：
         *   - currentVersionId 指向新版本（详情页默认显示最新）
         *   - versionCount +1（列表显示"3 个版本"）
         *
         * ★ 如果分两个事务，中间失败就会出现
         *   "版本插入了但计数没变"的不一致。
         */
        resume.setCurrentVersionId(version.getId());
        resume.setVersionCount(resume.getVersionCount() + 1);

        log.info("新增简历版本, userId={}, resumeId={}, versionNo={}, source={}",
                userId, resumeId, nextNo, source);

        /*
         * ★ 返回【详情】而不是【摘要】：
         *   前端保存后需要立刻显示新版本的正文（用户刚编辑的内容），
         *   返回摘要的话它还得再查一次。
         */
        return VersionDetailVO.of(version);
    }

    // ═══════════════════════════════════════════════════════════════
    //  【三、查询】
    // ═══════════════════════════════════════════════════════════════

    /**
     * 我的简历列表（分页）。
     *
     * @param userId 当前用户
     * @param page   页码，从 1 开始
     * @param size   每页条数
     *
     * 【★ 三个防御性处理】
     *
     *  1. page 最小为 1
     *     Math.max(page - 1, 0)：前端传 page=0 时按第 1 页处理，
     *     而不是抛出 Spring 的 IllegalArgumentException（页码不能为负）。
     *
     *  2. size 上限 50
     *     Math.min(size, MAX_PAGE_SIZE)：
     *     防止前端传 size=10000 把整个表拉出来。
     *     ★ 这是"服务端不相信客户端"的体现。
     *
     *  3. size 最小 1
     *     Math.max(size, 1)：防止传 0 或负数导致 SQL LIMIT 0。
     */
    @Transactional(readOnly = true)
    public PageResult<ResumeSummaryVO> list(Long userId, int page, int size, String keyword) {

        int safePage = Math.max(page - 1, 0);          // ★ 1-based → 0-based
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        /*
         * ★ 有关键词就搜索，没有就查全部。
         *
         * ★ 为什么要 trim 并判空？
         *   前端可能传空字符串（搜索框清空后仍带着参数）。
         *   空字符串会让 LIKE '%%' 匹配所有记录 —— 结果一样但走了不同的 SQL。
         *   统一走"无关键词"分支更快。
         */
        String kw = keyword == null ? "" : keyword.trim();

        Page<Resume> pageData = kw.isEmpty()
                ? resumeRepository.findByUserIdOrderByUpdatedAtDesc(
                        userId, PageRequest.of(safePage, safeSize))
                : resumeRepository.searchByUserId(
                        userId, kw, PageRequest.of(safePage, safeSize));

        /*
         * 【★★ 补 preview：注意这里是怎么避免 N+1 的】
         *
         * ❌ 错误写法（N+1）：
         *      page.getContent().stream().map(r -> {
         *          var v = versionRepository.findById(r.getCurrentVersionId());  ← 每行一次查询
         *          ...
         *      })
         *    10 条简历 = 10 次查询。
         *
         * ✅ 本项目的写法：
         *     - 先收集所有 currentVersionId
         *     - 一次 findAllById 批量查
         *     - 在内存里做映射
         *    2 次查询（1 次分页 + 1 次批量取版本），与页大小无关。
         *
         * ★ 那为什么不干脆不在列表里给 preview？
         *   因为用户需要辨认"这份简历是哪一份"。
         *   一页只有 10 条，批量查一次成本很低，值得。
         */
        List<Long> versionIds = pageData.getContent().stream()
                .map(Resume::getCurrentVersionId)
                .filter(id -> id != null)
                .toList();

        /*
         * ★ 为什么要 filter 掉 null？
         *   理论上 currentVersionId 一定有值（创建时就设置了），
         *   但万一历史数据有问题（比如早期 bug 导致的 null），
         *   findAllById 里传 null 会抛异常。
         *   ★ 防御性写法：不要假设"理论上不会"。
         */

        /*
         * findAllById(ids) 会一次查出所有版本。
         * 转成 Map<versionId, content> 方便下面按 id 取。
         *
         * ★ Collectors.toMap 的第三个参数 (a, b) -> a 是什么？
         *   当 key 重复时的处理策略。这里是"保留第一个"。
         *   理论上 versionId 不会重复，但 toMap 不加这个参数
         *   遇到重复会抛 IllegalStateException —— 加一个防御。
         */
        var versionsById = versionRepository.findAllById(versionIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ResumeVersion::getId,
                        v -> v,
                        (a, b) -> a
                ));

        /*
         * 【★ 批量查最新分数，避免 N+1】
         *
         * ★ 为什么不能放在上面的 stream 里逐个查？
         *   10 份简历 = 10 次 SQL，这叫 N+1 查询问题。
         *   一次 IN 查询拿到所有，是 1 次 SQL。
         *
         * ★ 依赖方向：resume 模块 → analysis 模块。
         *   analysis 模块也依赖 resume（要查版本），
         *   ★ 这构成了【循环依赖】吗？
         *
         *   包层面看是循环的（resume → analysis → resume），
         *   但 Spring 的 Bean 依赖不成环：
         *      ResumeService 依赖 AnalysisReportRepository（Repository，不是 Service）
         *      AnalysisService 依赖 ResumeService
         *   所以实例化顺序是确定的，不会报循环依赖。
         */
        var latestAnalysisByVersion = latestScoreProvider.latestSummaries(userId,
                pageData.getContent().stream()
                        .map(Resume::getCurrentVersionId)
                        .filter(java.util.Objects::nonNull)
                        .toList());

        List<ResumeSummaryVO> records = pageData.getContent().stream()
                .map(r -> {
                    ResumeVersion current = versionsById.get(r.getCurrentVersionId());
                    var analysis = latestAnalysisByVersion.get(r.getCurrentVersionId());
                    return new ResumeSummaryVO(
                            r.getId(),
                            r.getTitle(),
                            r.getTargetPosition(),
                            r.getCurrentVersionId(),
                            r.getVersionCount(),
                            current == null ? null : preview(current.getContent()),
                            analysis == null ? null : analysis.score(),
                            analysis == null ? null : analysis.reportId(),
                            analysis == null ? null : analysis.jdTitle(),
                            analysis == null ? null : analysis.jdCompany(),
                            analysis == null ? null : analysis.analyzedAt(),
                            analysis == null ? 0 : analysis.jdCount(),
                            r.getUpdatedAt()
                    );
                })
                .toList();

        /*
         * ★ 用 PageResult.of(List) 而不是 PageResult.of(Page)：
         *   因为我们已经手动 map 过了，手上是 List 而不是 Page。
         *   这时要自己带上分页信息。
         *
         * ★★ 这里曾经有过一个 bug：写成 page + 1，结果请求 page=1 时返回 page=2。
         *   原因：入参 page 已经是 1-based（前端传的就是 1），
         *        只有 safePage 需要 -1（给 Spring 的 0-based）。
         *        返回给前端时应该用 safePage + 1 还原，而不是 page + 1。
         *
         *   ★ 用 safePage + 1 而不是直接 page，是因为 page 可能是 0 或负数
         *     （前端传错），那时 safePage 会被纠正成 0，+1 得到 1 才对。
         *
         *   ★ 这个 bug 是"跑一遍验证"才发现的 —— 光看代码很容易漏，
         *     因为 page+1 看起来太自然了。这也是为什么要逐步验证。
         */
        return new PageResult<>(
                records,
                pageData.getTotalElements(),
                safePage + 1,                  // ★ 0-based → 1-based（用纠正后的页码）
                safeSize,
                pageData.getTotalPages()
        );
    }

    /**
     * 简历详情（含版本列表摘要）。
     *
     * @throws BizException RESUME_NOT_FOUND —— 不存在或不是你的
     */
    @Transactional(readOnly = true)
    public ResumeDetailVO detail(Long userId, Long resumeId) {

        /*
         * ★ findByIdAndUserId：越权校验在 SQL 里。
         *   不是这个用户的简历 → Optional.empty → 抛"不存在"。
         */
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.RESUME_NOT_FOUND));

        return detailOf(resume);
    }

    /**
     * 取某个版本的完整内容。
     *
     * 【★ 为什么这个方法的签名只有 versionId，没有 resumeId？】
     *
     *   因为前端点时间线上的某个版本时，只知道版本 id。
     *   但要校验"这个版本是不是当前用户的"，需要：
     *       版本 → 它所属的简历 → 简历的 userId
     *
     *   ★ 两种实现：
     *      a) 查出版本，再查简历，再判断（2 次查询）
     *      b) 用 findOwnedVersion 一条 JPQL 子查询搞定（1 次查询）
     *
     *   本项目用 b（见下面的实现）。
     *
     * @throws BizException VERSION_NOT_FOUND —— 不存在或不是你的
     */
    @Transactional(readOnly = true)
    public VersionDetailVO getVersion(Long userId, Long versionId) {

        /*
         * findOwnedVersion 的 JPQL：
         *     select v from ResumeVersion v
         *     where v.id = :versionId
         *       and v.resumeId in (select r.id from Resume r where r.userId = :userId)
         *
         * ★ 这一条 SQL 同时完成了"取内容"和"校验归属"。
         *
         * ★ 找不到时统一返回 VERSION_NOT_FOUND 而不是 FORBIDDEN，
         *   同样是为了不泄露"这个 id 是否存在"。
         */
        ResumeVersion version = versionRepository.findOwnedVersion(versionId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.VERSION_NOT_FOUND));

        return VersionDetailVO.of(version);
    }

    // ═══════════════════════════════════════════════════════════════
    //  【四、修改与删除】
    // ═══════════════════════════════════════════════════════════════

    /**
     * 改简历信息（标题 + 目标岗位）。
     *
     * ★ 为什么不加 @Transactional 的写锁？
     *   改标题不会影响版本号、不计入版本，没有并发问题。
     *   两个请求同时改，后提交的覆盖先提交的 —— 这是可接受的语义
     *   （用户自己都改过，让最后一次生效）。
     *
     * ★ 目标岗位允许传 null 表示清除。
     *   注意：这里不能像 create 那样"null 就跳过不设"，
     *   因为用户可能是想把岗位删掉。
     *   所以直接 set，null 就是 null。
     */
    @Transactional
    public ResumeDetailVO updateInfo(Long userId, Long resumeId,
                                     String title, String targetPosition) {

        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.RESUME_NOT_FOUND));

        resume.setTitle(title);
        resume.setTargetPosition(normalizeOptional(targetPosition));

        log.debug("简历信息更新, resumeId={}, title={}, targetPosition={}",
                resumeId, title, resume.getTargetPosition());

        return detailOf(resume);
    }

    /**
     * 删除简历。
     *
     * 【★★ 删除时会发生什么？】
     *
     *   数据库外键 fk_version_resume 配了 ON DELETE CASCADE：
     *       DELETE FROM resume WHERE id = ?
     *       → 数据库自动删除所有 resume_version 里 resume_id = ? 的行
     *
     *   ★ 所以代码里只需要删 resume 一行，版本由数据库级联。
     *
     *   那分析报告呢？
     *       fk_report_version 也是 ON DELETE CASCADE
     *       → 版本删了 → 报告也删了
     *
     *   ★ 三张表级联删除，代码只有一行。
     *     这就是"把约束交给数据库"的好处 —— 不会漏、不会忘。
     *
     * 【★ 那社区里已发布的帖子怎么办？】
     *   帖子的 report_id 是 ON DELETE SET NULL，
     *   而且帖子保存了 report_snapshot 快照。
     *   所以报告删了，帖子内容照样完整可看。
     *   ★ 这是"社区内容应当不可变"的设计（步骤 12 会详细讲）。
     *
     * ★ 为什么不做"软删除"（标记 status='DELETED'）？
     *   简历是用户的私有数据，删了就是真的不想留了。
     *   软删除会让用户觉得"我删了但数据还在"，不诚实。
     *   ★ 社区帖子才需要软删除（保留审计），简历不需要。
     */
    @Transactional
    public void delete(Long userId, Long resumeId) {

        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.RESUME_NOT_FOUND));

        resumeOriginalFileService.deleteForResume(resumeId);
        resumeRepository.delete(resume);

        log.info("删除简历, userId={}, resumeId={}", userId, resumeId);
    }

    // ═══════════════════════════════════════════════════════════════
    //  【五、供其他模块复用】
    // ═══════════════════════════════════════════════════════════════

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【拿到一个"属于当前用户"的版本】★ 跨模块复用的关键
     * ═══════════════════════════════════════════════════════════════
     *
     * 谁会用：
     *      步骤 8  发起分析时 → 需要版本的正文和 contentHash
     *      步骤 15 生成面试题时 → 需要版本的解析结果
     *
     * 【★★ 为什么要把这个"校验 + 查询"封装成一个公开方法？】
     *
     *   反例：如果每个模块自己写：
     *        var v = versionRepository.findById(versionId).orElseThrow();
     *        var r = resumeRepository.findById(v.getResumeId()).orElseThrow();
     *        if (!r.getUserId().equals(userId)) throw FORBIDDEN;
     *
     *   问题：
     *     1. 每个模块都要写这 3 行，漏一个就是越权漏洞
     *     2. 每个模块都用 findById 查两次（性能差）
     *     3. 错误码可能不一致（有人抛 404 有人抛 403）
     *
     *   ★ 封装之后：一行搞定，而且永远正确。
     *     这是"把容易出错的重复逻辑收口"的典型做法。
     *
     * @throws BizException VERSION_NOT_FOUND —— 不存在或不属于当前用户
     */
    @Transactional(readOnly = true)
    public ResumeVersion requireOwnedVersion(Long userId, Long versionId) {
        return versionRepository.findOwnedVersion(versionId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.VERSION_NOT_FOUND));
    }

    /**
     * 拿到"属于当前用户"的简历。同 requireOwnedVersion 的思路。
     *
     * 谁会用：步骤 7 的 JD？不，那是另一个实体。
     *          目前主要给将来需要"确认简历归属"的地方用。
     */
    @Transactional(readOnly = true)
    public Resume requireOwnedResume(Long userId, Long resumeId) {
        return resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.RESUME_NOT_FOUND));
    }

    // ═══════════════════════════════════════════════════════════════
    //  【六、内部工具方法】
    // ═══════════════════════════════════════════════════════════════

    /**
     * 归一化 + 校验字数。
     *
     * ★ 抽出来的原因：create 和 addVersion 都要用。
     *   如果两处各写一遍，阈值或校验逻辑改了一处忘了另一处 ——
     *   就会出现"新建要求 50 字、加版本只要 10 字"的不一致。
     */
    private String normalizeChecked(String raw) {

        /*
         * ★ 第一步归一化（统一换行、去 BOM、压缩空行）。
         *   必须在算字数之前做，否则空白会影响计数。
         */
        String content = normalizer.normalize(raw);

        /*
         * ★ 第二步算字数（去掉所有空白）。
         *   注意：不是 content.length()，那样会把换行和缩进也算进去。
         */
        int count = normalizer.charCount(content);

        if (count < MIN_CHARS) {
            /*
             * ★ 为什么不把实际字数放在错误信息里？
             *   错误码的默认提示是"简历内容过短（至少 50 字）"。
             *   前端可以显示"当前 32 字"，但服务端不一定要给 ——
             *   因为前端自己就能算，而且更实时。
             */
            throw new BizException(ErrorCode.RESUME_CONTENT_INVALID);
        }

        return content;
    }

    /**
     * 构造一个版本实体（不保存）。
     *
     * ★ 为什么抽出来：create 和 addVersion 都要构造，字段完全一样。
     *
     * @param resumeId    归属简历
     * @param no          版本号
     * @param content     已归一化并校验过的正文
     * @param source      来源
     * @param parentId    父版本（可 null）
     * @param note        变更说明（可 null）
     */
    private Long resolveParentVersionId(Long resumeId, Long currentVersionId, Long requestedParentId) {
        Long parentId = requestedParentId == null ? currentVersionId : requestedParentId;
        if (parentId == null) {
            return null;
        }
        versionRepository.findByIdAndResumeId(parentId, resumeId)
                .orElseThrow(() -> new BizException(ErrorCode.VERSION_MISMATCH));
        return parentId;
    }
    private ResumeVersion buildVersion(Long resumeId, int no, String content,
                                       VersionSource source, Long parentId, String note) {
        ResumeVersion v = new ResumeVersion();
        v.setResumeId(resumeId);
        v.setVersionNo(no);
        v.setContent(content);

        /*
         * ★ 在这里算 hash，而不是让调用方传：
         *   保证 hash 一定对应"这一份 content"，
         *   不会出现"传了 content 但忘了更新 hash"的 bug。
         */
        v.setContentHash(normalizer.sha256(content));

        v.setSource(source);
        v.setParentVersionId(parentId);
        v.setChangeNote(note);

        /*
         * ★ charCount 也在这里算，同理 ——
         *   保证它和 content 永远一致。
         *
         * ★ 那 @PrePersist 里为什么还要兜底？
         *   那是为了"别的地方直接 new 一个 ResumeVersion 并 save"的场景
         *   （比如测试代码）。两层保护不冲突。
         */
        v.setCharCount(normalizer.charCount(content));

        return v;
    }

    /**
     * 把 Resume 组装成 ResumeDetailVO（含版本列表）。
     *
     * ★ 为什么抽出来：create / detail / rename 三处都要用。
     */
    private ResumeDetailVO detailOf(Resume resume) {

        /*
         * ★ 查版本列表：按 version_no 倒序（最新在前）。
         *
         * ★ 这里没有用分页 —— 一份简历的版本数通常 < 20，
         *   一次全查比"分页 + 前端再请求"简单太多。
         */
        List<VersionSummaryVO> versions = versionRepository
                .findByResumeIdOrderByVersionNoDesc(resume.getId())
                .stream()
                .map(VersionSummaryVO::of)      // ★ 实体 → 摘要 VO（无正文）
                .toList();

        return new ResumeDetailVO(
                resume.getId(),
                resume.getTitle(),
                resume.getTargetPosition(),
                resume.getCurrentVersionId(),
                resume.getVersionCount(),
                versions,
                resume.getCreatedAt(),
                resume.getUpdatedAt()
        );
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【可选字符串归一化】null / 空白 / 空串 → null
     * ═══════════════════════════════════════════════════════════════
     *
     * 【★ 为什么要统一成 null，而不是保留空字符串？】
     *
     *   用户可能这样提交目标岗位：
     *      没传       → null
     *      传了 ""    → 空字符串
     *      传了 "  "  → 全是空格
     *
     *   这三种情况在产品语义上【完全一样】—— 都是"没填岗位"。
     *   如果数据库里同时存在 null 和 ""，就会带来一系列问题：
     *     1. 前端要判断两种："if (!p || p === '')"  ← 容易漏
     *     2. SQL 查询要写 "WHERE target_position IS NULL OR target_position = ''"
     *     3. 统计"有多少份简历填了岗位"时容易算错
     *
     *   ★ 统一成 null 之后，所有地方只需要判断 null。
     *
     * 【★ 为什么用 strip() 而不是 trim()？】
     *   strip() 去掉所有 Unicode 空白（含全角空格 U+3000），
     *   trim() 只去掉 ASCII 空白。
     *   中文用户很容易打出全角空格 —— strip() 更合适。
     *
     * @param s 原始字符串（可为 null）
     * @return 去掉首尾空白后的字符串；原值为 null 或全空白时返回 null
     */
    private String normalizeOptional(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 取正文前 N 个字符做预览。
     *
     * 【★ 为什么要把换行换成空格？】
     *
     *   列表里显示成一行。如果保留换行：
     *       "张三 | 后端开发
     *        教育背景：某大学..."
     *   在表格单元格里会显示成多行，撑高行高，很难看。
     *
     *   ★ 换成空格后是一行连续文字，配合 CSS 的 ellipsis 截断，
     *     正好是"张三 | 后端开发 教育背景：某大学 计算机..."这种效果。
     */
    private String preview(String content) {
        if (content == null) {
            return null;
        }

        /*
         * ★ 先做替换再截断，还是先截断再替换？
         *   先替换：\n → 空格，然后取前 80 个字符。
         *   先截断：取前 80 个字符（可能正好截在 \n 上），再替换。
         *
         *   两者结果几乎一样。这里选"先替换"，因为逻辑更直观。
         */
        String single = content.replace('\n', ' ');

        return single.length() <= PREVIEW_LENGTH
                ? single
                : single.substring(0, PREVIEW_LENGTH) + "…";
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 这个类的设计要点（面试可讲）】
     *
     *  1. 越权校验在 SQL 里（findByIdAndUserId / findOwnedVersion），
     *     不是"查出来再判断" —— 物理上读不到别人的数据
     *
     *  2. 加版本用 FOR UPDATE 行锁，
     *     让"算版本号 + 插入"串行化，防并发撞号
     *
     *  3. 内容用 hash 去重，相同就拒绝，不产生空版本
     *
     *  4. 列表补 preview 时批量查（findAllById），避免 N+1
     *
     *  5. 版本内容不可变 —— 没有任何代码路径能 UPDATE content
     *
     *  6. 删除靠数据库级联，代码只有一行，不会漏
     *
     *  ★ 如果被问"你的简历版本管理怎么设计的"，
     *    就按这 6 条答，每条都能对应到具体代码。
     * ═══════════════════════════════════════════════════════════════
     */
}
