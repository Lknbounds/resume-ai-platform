package com.jianli.user.service;

import com.jianli.common.BizException;
import com.jianli.common.ErrorCode;
import com.jianli.security.JwtProperties;
import com.jianli.security.JwtTokenProvider;
import com.jianli.security.TokenService;
import com.jianli.user.dto.LoginRequest;
import com.jianli.user.dto.RegisterRequest;
import com.jianli.user.dto.TokenResponse;
import com.jianli.user.dto.UserVO;
import com.jianli.user.entity.AppUser;
import com.jianli.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  认证业务的核心：注册、登录、刷新、登出。
 *
 *  它是"业务规则"和"技术细节"的桥梁：
 *      向上：给 Controller 提供 4 个方法
 *      向下：调 Repository 存数据、调 JwtTokenProvider 签 token、调 Redis 记账
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：四个方法各自流向哪里】
 *
 *  ── register ──
 *      RegisterRequest
 *        ├─ 查重 → userRepository.existsByUsername
 *        ├─ 密码加密 → passwordEncoder.encode（BCrypt）
 *        ├─ 造实体 → new AppUser() + set...
 *        ├─ 入库 → userRepository.save（INSERT INTO app_user）
 *        └─ 返回 UserVO（不含密码）
 *
 *  ── login ──
 *      LoginRequest
 *        ├─ 查用户 → userRepository.findByUsername
 *        ├─ 比对密码 → passwordEncoder.matches
 *        ├─ 更新登录时间 → user.setLastLoginAt（UPDATE）
 *        └─ 签发 token → issueTokens()
 *              ├─ JwtTokenProvider.createAccess / createRefresh
 *              ├─ TokenService.saveRefresh（Redis 白名单）
 *              └─ 返回 TokenResponse
 *
 *  ── refresh ──
 *      refreshToken 字符串
 *        ├─ 解析 → JwtTokenProvider.parse（验签+验过期）
 *        ├─ 检查类型是否 refresh
 *        ├─ 查白名单 → TokenService.isRefreshValid
 *        ├─ ★ 删除旧的 → TokenService.revokeRefresh（轮换）
 *        └─ 签发新的 → issueTokens()
 *
 *  ── logout ──
 *      accessToken + refreshToken
 *        ├─ access 的 jti 进黑名单 → TokenService.blacklistAccess（TTL=剩余有效期）
 *        └─ refresh 从白名单删除 → TokenService.revokeRefresh
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 依赖的 5 个东西，分别干什么】
 *
 *  UserRepository      → 读写 app_user 表
 *  PasswordEncoder     → BCrypt 加密和比对（Bean 在 SecurityConfig 里定义）
 *  JwtTokenProvider    → 生成和解析 token
 *  TokenService        → 管理 Redis 里的白名单/黑名单
 *  JwtProperties       → 拿 token 有效期（要告诉前端 expiresIn，也要给 Redis 设 TTL）
 *
 * ★ 为什么这个类的依赖这么多？
 *   它是"编排者"：把多个组件串起来完成一个业务流程。
 *   这正是 Service 层的典型样子 —— 自己几乎不写算法，
 *   都是"调 A、判断、再调 B"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final TokenService tokenService;
    private final JwtProperties jwtProperties;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【一、注册】
     * ═══════════════════════════════════════════════════════════════
     *
     * 流程：查重 → 加密 → 入库 → 返回 VO
     *
     * ★ @Transactional 在这里的作用：
     *   整个方法在一个事务里。如果中间任何一步抛异常，
     *   前面的 INSERT 会一起回滚，不会留下半截数据。
     *   虽然本方法只有一次写操作，加事务是为了一致性和未来的扩展。
     *
     * @param req 已经通过 @Valid 校验的请求（用户名格式、密码强度都合格了）
     * @return 新建用户的信息（不含密码哈希）
     * @throws BizException USERNAME_EXISTS —— 用户名已被占用
     */
    @Transactional
    public UserVO register(RegisterRequest req) {

        /*
         * 【第一步：查重】
         *
         * ★ 为什么用 existsByUsername 而不是 findByUsername？
         *   exists 只发 SELECT 1 ... LIMIT 1，不读所有列，更快。
         *
         * ★★ 重要：这个检查【不能防止并发重复】！
         *
         *   两个请求同时到达：
         *      请求A: existsByUsername("alice") → false
         *      请求B: existsByUsername("alice") → false     ← 都通过了
         *      请求A: INSERT ... → 成功
         *      请求B: INSERT ... → 违反唯一索引，抛 DuplicateKeyException
         *
         *   所以真正防重的是【数据库唯一索引】，这里只是"提前友好提示"，
         *   让 99% 的正常情况得到清晰的错误信息，而不是 500 错误。
         *
         *   ★ 并且加了 @Transactional 之后还有一层保障：
         *     B 的 INSERT 失败会回滚整个事务，不会留下脏数据。
         *
         *   ★ 这也解释了为什么 AppUser 的 username 上写了 unique=true，
         *     以及建表脚本里有 uk_user_username 索引 —— 那才是最后防线。
         */
        if (userRepository.existsByUsername(req.username())) {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }

        /*
         * 【第二步：构造实体】
         *
         * ★ 为什么 setYearsOfExperience(0) 而不让它自动？
         *   虽然 @PrePersist 里也兜底了，但这里显式设置更清楚：
         *   "新注册用户默认 0 年经验"。看代码的人不用去翻实体类。
         */
        AppUser user = new AppUser();
        user.setUsername(req.username());

        /*
         * 【第三步：密码加密】★ 关键
         *
         * passwordEncoder.encode("Passw0rd1")
         *   → "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
         *
         * ★ BCrypt 的三个特点（面试常问）：
         *   1. 每次加密结果【都不一样】！
         *      因为内置了随机 salt。同一个密码加密两次，得到两个不同的哈希。
         *      验证时不需要知道 salt —— 它存在哈希串里面。
         *
         *   2. 无法反推。
         *      matches(明文, 哈希) 的做法是：从哈希里取出 salt，
         *      用同样的 salt 再算一次，比较结果。
         *
         *   3. 慢。cost=10 意味着 2^10 = 1024 轮计算，约 50-100ms。
         *      对登录来说这点延迟无感，但让爆破成本高几个数量级。
         *
         * ★ 为什么不能自己写加密？
         *   自己用 MD5/SHA256 + 手写 salt 极易出错（比如 salt 复用、
         *   拼接顺序错、忘了加盐）。直接用 Spring 的标准实现。
         */
        user.setPasswordHash(passwordEncoder.encode(req.password()));

        user.setNickname(req.nickname());

        /*
         * yearsOfExperience 不设置：@PrePersist 会兜底成 0。
         * ★ 但这里显式写更好读：
         */
        user.setYearsOfExperience(0);

        /*
         * 【第四步：入库】
         *
         * save() 的语义：
         *   id 为 null → 执行 INSERT
         *   id 有值   → 执行 UPDATE
         * 这里 id 是 null，所以是 INSERT。
         *
         * ★ 返回值 saved 和 user 是同一个对象（JPA 会把生成的 id 回填到 user）。
         *   用返回值接一下只是为了语义清楚。
         *
         * ★ createdAt / updatedAt 不用设置 —— @PrePersist 会处理。
         */
        AppUser saved = userRepository.save(user);

        log.info("新用户注册成功, userId={}, username={}", saved.getId(), saved.getUsername());

        /*
         * 【第五步：转成 VO 返回】
         *
         * ★ 这里返回 UserVO 而不是 AppUser，是因为 Controller 会直接
         *   把它放进响应体。如果返回 AppUser，passwordHash 就会泄露。
         *
         * ★ 注册后【不自动登录】。
         *   前端会在注册成功后立刻调一次 login（见 04 文档的 register）。
         *   也可以在这里直接签发 token，但那样注册接口的职责就变重了，
         *   而且前端要处理两套返回结构。分开更清晰。
         */
        return UserVO.of(saved);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【二、登录】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 这是全项目最重要的一个方法，涉及的核心是"如何安全地验证身份"。
     *
     * @param req 用户名 + 密码
     * @return 两个 token + 有效期 + 用户信息
     * @throws BizException BAD_CREDENTIALS —— 用户名不存在 或 密码错误
     */
    @Transactional
    public TokenResponse login(LoginRequest req) {

        /*
         * ═══════════════════════════════════════════════════════════
         *  【第一步：查用户 + 比对密码，两步合一】
         * ═══════════════════════════════════════════════════════════
         *
         * 这段链式调用值得逐段拆开看：
         *
         * ① userRepository.findByUsername(req.username())
         *      返回 Optional<AppUser>
         *      用户不存在 → Optional.empty()
         *      用户存在   → Optional.of(user)
         *
         * ② .filter(u -> passwordEncoder.matches(...))
         *      如果 Optional 里有值，执行判断：
         *          密码对 → 原样返回这个 Optional
         *          密码错 → 变成 Optional.empty()
         *      如果本来就是 empty，filter 不执行判断，还是 empty
         *
         * ③ .orElseThrow(...)
         *      empty → 抛 BAD_CREDENTIALS
         *      有值  → 返回那个 AppUser
         *
         * ★★ 这个写法的精妙之处：用户不存在 和 密码错误，
         *    走的是【同一个出口】，抛出的是【同一个异常】，
         *    返回给前端的是【同一句话】。
         *
         *    如果分开写：
         *        if (user == null) throw new BizException("用户不存在");
         *        if (!match) throw new BizException("密码错误");
         *    攻击者就能通过提示语判断哪些用户名存在，然后针对性爆破 ——
         *    这叫【用户名枚举攻击】。
         *
         *    合在一起之后，攻击者得不到任何额外信息。
         *
         * ★ matches(明文, 哈希) 的参数顺序：
         *    第一个是用户输入的明文
         *    第二个是数据库里存的哈希
         *   ★ 顺序写反了编译不会报错（都是 String），但永远返回 false。
         *     这是个很隐蔽的坑。
         */
        AppUser user = userRepository.findByUsername(req.username())
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> {
                    // ★ 日志里记一下尝试失败，便于发现异常登录。
                    //   但注意：只记用户名，【绝不记密码】。
                    log.warn("登录失败, username={}", req.username());
                    return new BizException(ErrorCode.BAD_CREDENTIALS);
                });

        /*
         * ═══════════════════════════════════════════════════════════
         *  【第二步：更新最后登录时间】
         * ═══════════════════════════════════════════════════════════
         *
         * ★ 为什么用 LocalDateTime.now(ZoneOffset.UTC)？
         *   全项目统一存 UTC 时间（见 AppUser 的 @PrePersist 说明）。
         *   如果这里写 now()，就会和注册时的时间差 8 小时。
         *
         * ★ 需要显式 save 吗？
         *   不需要。因为 user 是"托管状态"（managed）—— 它来自当前事务里的
         *   一次查询，Hibernate 会追踪它的变化，
         *   事务提交时自动发一条 UPDATE。
         *   这叫"脏检查"（dirty checking）。
         *
         *   ★ 但如果你把 user 传给别的方法、或者在事务外修改，就需要手动 save。
         *     为了明确，本项目在有些地方会显式 save（无害），
         *     这里省略是因为它就在同一个方法里，一眼能看出是在事务内。
         */
        user.setLastLoginAt(LocalDateTime.now(ZoneOffset.UTC));

        /*
         * ═══════════════════════════════════════════════════════════
         *  【第三步：签发 token】
         * ═══════════════════════════════════════════════════════════
         */
        log.info("登录成功, userId={}, username={}", user.getId(), user.getUsername());
        return issueTokens(user);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【三、刷新 token】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★★ 这个方法的核心设计叫"轮换"（rotation），务必理解：
     *
     *   每次刷新，旧 refresh token 立即作废，发一个新的。
     *
     *   为什么？
     *   1. 【限制泄露危害】攻击者偷到 refresh token，最多也只能用一次。
     *      因为他用完之后，这个 token 就作废了。
     *
     *   2. 【留下异常信号】如果攻击者先用掉了，真用户下次刷新就会失败
     *      （因为旧的已作废）→ 用户被迫重新登录 → 这是一个"被盗"的信号。
     *
     *   3. 【防止长期潜伏】不轮换的话，一个 stolen refresh 可以反复刷新 7 天，
     *      你永远发现不了。
     *
     * ★ 这就是为什么这个方法的最后要"先删旧的，再发新的"，
     *   顺序不能反。
     *
     * @param refreshToken 前端存的刷新令牌
     * @return 一对新的 token
     * @throws BizException TOKEN_INVALID —— 任何环节不对都返回这个
     */
    @Transactional
    public TokenResponse refresh(String refreshToken) {

        /*
         * 【第一步：解析 token】
         *
         * ★ 为什么要 try-catch？
         *   parse() 会抛 JwtException（过期、签名错、格式错）。
         *   这些都是"用户凭证不可用"，应该统一变成 TOKEN_INVALID，
         *   而不是让它冒到 GlobalExceptionHandler 变成 500。
         *
         * ★ 为什么 catch 两个类型？
         *   JwtException          → jjwt 抛的（签名/过期/格式）
         *   IllegalArgumentException → 字符串为 null 或空时抛的
         *   两个都要接，否则空字符串会导致 500。
         */
        Claims claims;
        try {
            claims = tokenProvider.parse(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("refresh token 解析失败: {}", e.getMessage());
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        /*
         * 【第二步：确认这是 refresh token，不是 access token】
         *
         * ★ 为什么要检查？
         *   防一种攻击/误用：用户拿 access token 来调 refresh 接口。
         *   如果放行了，等于把 2 小时的 access 变成了 7 天的新 access
         *   （因为每次刷新都会发新的），短有效期的意义就没了。
         *
         * ★ 同样地，JwtAuthenticationFilter 里也会检查"必须是 access"，
         *   两边都检查，双向封闭。
         */
        if (!JwtTokenProvider.TYPE_REFRESH.equals(tokenProvider.type(claims))) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        /*
         * 【第三步：从 claims 里取出 userId 和 jti】
         */
        Long userId = tokenProvider.userId(claims);
        String jti = tokenProvider.jti(claims);

        /*
         * ═══════════════════════════════════════════════════════════
         *  【第四步：★ 检查白名单 —— 这一步是整个撤销机制的核心】
         * ═══════════════════════════════════════════════════════════
         *
         * 为什么签名对、没过期的 token 还要查白名单？
         *
         *   因为 JWT 是自包含的：只要签名对、没过期，服务端就认。
         *   但我们需要能【主动撤销】：
         *      - 用户登出         → 删掉白名单记录
         *      - 改密码/被禁用     → revokeAllRefresh 清空
         *      - 已经轮换过一次    → 旧记录被删，自然查不到
         *
         *   查白名单就是"我承认这个 token 是我发过并且还没撤销的"。
         *
         * ★ 如果一个 refresh token 被偷了，用户登出后：
         *   攻击者拿着它来刷新 → 白名单里查不到 → 401
         *   这就是"登出后立即失效"的实现原理。
         */
        if (!tokenService.isRefreshValid(userId, jti)) {
            log.warn("refresh token 不在白名单（可能已登出/已被轮换）, userId={}", userId);
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        /*
         * 【第五步：确认用户仍然存在】
         *
         * ★ 为什么还要查一次？
         *   token 可能签发后被删除了（本项目不提供注销，但理论上可能）。
         *   查一下更稳妥，成本很低（主键查询）。
         */
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(ErrorCode.TOKEN_INVALID));

        /*
         * ═══════════════════════════════════════════════════════════
         *  【第六步：★ 轮换 —— 删掉旧的，再发新的】
         * ═══════════════════════════════════════════════════════════
         *
         * ★ 这一行必须在 issueTokens 之前！
         *   否则如果发新 token 的过程中出错，旧 token 还能用，
         *   就变成了"两个 refresh 都有效"，破坏了轮换的安全性。
         *
         * ★ 更严谨的写法应该是"先发新的、再删旧的"吗？
         *   不。如果先发新后删旧，中间崩溃了会留下两个有效 token。
         *   而先删后发，中间崩溃了用户就得重新登录 ——
         *   安全优先于可用性，选前者。
         */
        tokenService.revokeRefresh(userId, jti);

        log.info("token 刷新成功（已轮换）, userId={}", userId);
        return issueTokens(user);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【四、登出】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 为什么参数是字符串而不是 Claims？
     *   因为调用方（Controller）手里只有请求头里的原始字符串。
     *   让它先解析一遍再传进来，等于把"解析"这件事散到 Controller 里。
     *   在这里统一解析，职责更清楚。
     *
     * ★ 为什么这个方法不加 @Transactional？
     *   因为它【完全不碰数据库】，只操作 Redis。
     *   事务是管理数据库连接的，对 Redis 没有作用。
     *   加了反而多一层无用开销。
     *
     * @param accessToken  请求头里的 access token（可能为 null）
     * @param refreshToken 请求体里的 refresh token（可能为 null）
     */
    public void logout(String accessToken, String refreshToken) {

        /*
         * ═══════════════════════════════════════════════════════════
         *  【第一步：把 access token 拉黑】
         * ═══════════════════════════════════════════════════════════
         *
         * ★ 为什么可能为 null？
         *   理论上登出接口需要登录才能调，但万一前端实现有问题
         *   （比如 token 已过期还想登出），我们也应该让它"成功"，
         *   不能因为 token 过期就报错 —— 用户点"退出登录"却没退出，体验很差。
         *
         * ★ 为什么要 try-catch？
         *   如果 token 已经过期，parse 会抛 ExpiredJwtException。
         *   这时候它已经无效了，不需要拉黑，直接忽略即可。
         *   静默吞掉是合理的 —— 结果（这个 token 不能用了）已经达成。
         *
         * ★ remainingTtl 算的是什么？
         *   这个 token 还有多久过期。黑名单 TTL 设成这个值，
         *   意味着 token 自然过期时黑名单记录也一起消失（见 RedisKeys.blacklist）。
         */
        if (accessToken != null && !accessToken.isBlank()) {
            try {
                Claims claims = tokenProvider.parse(accessToken);
                tokenService.blacklistAccess(
                        tokenProvider.jti(claims),
                        tokenProvider.remainingTtl(claims));
            } catch (JwtException | IllegalArgumentException ignored) {
                // token 本来就无效，不需要拉黑
                log.debug("登出时 access token 已失效，跳过拉黑");
            }
        }

        /*
         * 【第二步：从白名单删除 refresh token】
         *
         * ★ 这一步之后，这个 refresh token 就彻底不能用了。
         *   即使它在 7 天有效期内。
         */
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                Claims claims = tokenProvider.parse(refreshToken);
                tokenService.revokeRefresh(tokenProvider.userId(claims), tokenProvider.jti(claims));
            } catch (JwtException | IllegalArgumentException ignored) {
                log.debug("登出时 refresh token 已失效，跳过删除");
            }
        }

        /*
         * ═══════════════════════════════════════════════════════════
         *  【★ 为什么登出要传两个 token，而前端可能只有 access？】
         *
         *  前端在 localStorage 里两个都存着（见 04 文档），所以都能传。
         *
         *  如果只传 access 会怎样？
         *      access 被拉黑 → 2 小时内不能用了 ✅
         *      但 refresh 还在白名单里 → 攻击者可以用它换一个新的 access ❌
         *      等于"登出"没有真正生效！
         *
         *  ★ 所以登出必须两个都处理，缺一个就有漏洞。
         *    这是安全检查清单里必查的一条。
         * ═══════════════════════════════════════════════════════════
         */
        log.info("用户登出");
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【内部方法：签发一对 token 并登记白名单】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 为什么抽出来？
     *   login 和 refresh 都要做同样的事（签发 + 登记 + 组装响应），
     *   抽出来只写一次。以后改逻辑（比如加设备 id）只改一处。
     *
     * ★ 为什么是 private？
     *   这是内部实现细节，外部不该直接调。
     *   外部应该走 login / refresh，那样才有完整的业务校验。
     *
     * @param user 已经通过身份验证的用户
     * @return 完整的登录响应
     */
    private TokenResponse issueTokens(AppUser user) {

        /*
         * 【第一步：生成两个 token】
         *
         * 两个 token 的内容几乎一样（同样的 userId、username），
         * 只有两点不同：
         *   type → access / refresh
         *   ttl  → 2 小时 / 7 天
         *
         * ★ 为什么要区分？见 JwtTokenProvider.TYPE_ACCESS 的详细说明。
         */
        String access = tokenProvider.createAccess(user.getId(), user.getUsername());
        String refresh = tokenProvider.createRefresh(user.getId(), user.getUsername());

        /*
         * ═══════════════════════════════════════════════════════════
         *  【第二步：把 refresh 的 jti 存进 Redis 白名单】
         * ═══════════════════════════════════════════════════════════
         *
         * ★★ 这里有个"绕"的地方，值得说明：
         *
         *   我们刚生成了 refresh 字符串，但需要的是它的 jti。
         *   jti 是在生成时随机产生的 UUID，生成后没有直接返回。
         *   所以唯一的办法是【再解析一次】把 jti 读出来。
         *
         *   ★ 这确实有点笨 —— 多了一次解析开销。
         *     更干净的写法是让 createRefresh 返回一个包含 (token, jti) 的小 record：
         *         record IssuedToken(String token, String jti) {}
         *         IssuedToken refresh = tokenProvider.createRefresh(...);
         *
         *     这里保留现在的写法是为了让流程更直白（能一眼看出"存的是哪个 jti"），
         *     你可以自己改成上面那种更优雅的版本。
         *
         * ★ 为什么只存 refresh 的 jti，不存 access 的？
         *   因为 access 用的是黑名单（默认放行），
         *   存了反而会让白名单膨胀（见 TokenService 类注释里的对比表）。
         */
        Claims refreshClaims = tokenProvider.parse(refresh);
        tokenService.saveRefresh(
                user.getId(),
                tokenProvider.jti(refreshClaims),
                jwtProperties.refreshTtl()     // TTL 和 token 有效期一致
        );

        /*
         * 【第三步：组装响应】
         *
         * ★ expiresIn 用 jwtProperties.accessTtl().toSeconds()
         *   而不是写死 7200。
         *   这样改 yml 里的配置就自动生效，不用改代码。
         *
         * ★ UserVO.of(user) —— 又出现了这个转换。
         *   记住：只要数据要"出系统"，就必须经过 Entity → VO 这一步。
         */
        return new TokenResponse(
                access,
                refresh,
                jwtProperties.accessTtl().toSeconds(),
                UserVO.of(user)
        );
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 这个类的设计要点小结（面试可讲）】
     *
     *  1. 登录失败统一返回 BAD_CREDENTIALS，防用户名枚举
     *  2. access 短有效期 + 黑名单；refresh 长有效期 + 白名单 + 轮换
     *  3. 登出必须同时处理两个 token，否则"登出"不彻底
     *  4. 密码只以 BCrypt 哈希形式落库，永不返回、永不记录日志
     *  5. 所有返回给前端的用户数据都经过 UserVO 投影
     *
     *  ★ 如果被问"JWT 无状态怎么实现登出"：
     *     答："access 用 jti 黑名单，TTL 对齐剩余有效期；
     *          refresh 用 Redis 白名单，登出时删除记录。
     *          这样既能立即失效，又不会让 Redis 无限膨胀。"
     * ═══════════════════════════════════════════════════════════════
     */
}
