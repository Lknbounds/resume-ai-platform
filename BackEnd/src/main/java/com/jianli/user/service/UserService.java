package com.jianli.user.service;

import com.jianli.common.BizException;
import com.jianli.common.ErrorCode;
import com.jianli.security.TokenService;
import com.jianli.user.dto.ChangePasswordRequest;
import com.jianli.user.dto.UpdateProfileRequest;
import com.jianli.user.dto.UserVO;
import com.jianli.user.entity.AppUser;
import com.jianli.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  用户资料相关业务：查资料、改资料、改密码。
 *
 *  和 AuthService 的分工（再明确一次）：
 *      UserService → 用户的"资料"（昵称、岗位、年限、密码）
 *      AuthService → 用户的"凭证"（注册、登录、token）
 *
 *  ★ 那改密码为什么在 UserService 而不是 AuthService？
 *    因为它是"修改资料"的一种，而且必须已登录才能做。
 *    不过它又要调 TokenService（踢下线），所以本类依赖了 security 包 ——
 *    这是合理的跨包依赖（业务需要），不是循环依赖。
 * ═══════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /**
     * 密码加密器。
     *
     * ★ 为什么这里也需要它？
     *   改密码时要验证旧密码（matches）、加密新密码（encode）。
     *
     * ★ 它和 AuthService 里那个是同一个 Bean（Spring 单例），
     *   只是在两个类里各注入一次。这不浪费 —— 注入的是引用，不是新对象。
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * 用来"踢下线"。
     *
     * ★ 依赖方向：user 包 → security 包。
     *   security 包不依赖 user 包（它只依赖 infra.redis），所以没有循环。
     */
    private final TokenService tokenService;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【查当前用户的资料】GET /api/users/me
     * ═══════════════════════════════════════════════════════════════
     *
     * @param userId 由 @CurrentUserId 从 token 里取出
     * @throws BizException USER_NOT_FOUND —— token 有效但用户被删了
     */
    @Transactional(readOnly = true)
    public UserVO getProfile(Long userId) {
        return userRepository.findById(userId)
                .map(UserVO::of)
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【修改资料】PUT /api/users/me
     * ═══════════════════════════════════════════════════════════════
     *
     * 流程：查用户 → 逐个字段判断要不要改 → 返回 VO
     *
     * ★ @Transactional 的必要性：
     *   这里会 set 多个字段，改密码场景还会调 Redis。
     *   加事务保证数据库改动的一致回滚（Redis 操作不受事务控制，见下）。
     *
     * ★ 为什么不用显式 save？
     *   因为 user 是"托管状态"（从当前事务的查询里取出来的），
     *   Hibernate 会在事务提交时自动比对、自动 UPDATE。
     *   这叫脏检查（dirty checking）。
     *
     *   ★ 但如果 set 的值和原来一样，Hibernate 会跳过 UPDATE ——
     *     所以"用户点了保存但没改任何东西"不会产生多余的 SQL。
     *
     * @param userId 当前登录用户 id
     * @param req    新资料
     * @return 更新后的完整资料（前端可以直接用它刷新界面）
     */
    @Transactional
    public UserVO updateProfile(Long userId, UpdateProfileRequest req) {

        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));

        /*
         * 【逐字段更新】
         *
         * ★ 为什么 targetPosition 可以直接 set（不判 null）？
         *   因为 null 在这里是合法的 —— 用户要清空岗位。
         *
         * ★ 为什么 yearsOfExperience 要判 null？
         *   因为它有默认值语义：null = "这次不改"，0 = "改成 0 年"。
         *   这两种情况必须区分（见 UpdateProfileRequest 的说明）。
         */
        user.setNickname(req.nickname());
        user.setTargetPosition(req.targetPosition());

        if (req.yearsOfExperience() != null) {
            user.setYearsOfExperience(req.yearsOfExperience());
        }

        log.debug("用户资料已更新, userId={}", userId);

        /*
         * ★ 为什么直接返回更新后的数据，而不是返回 void 让前端再查一次？
         *   省一次请求。而且前端拿到返回值可以立刻刷新显示，
         *   不会有"保存成功但昵称还没变"的短暂不一致。
         */
        return UserVO.of(user);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【修改密码】PUT /api/users/me/password
     * ═══════════════════════════════════════════════════════════════
     *
     * 流程：验证旧密码 → 加密新密码 → 踢掉所有设备
     *
     * @param userId 当前登录用户 id
     * @param req    旧密码 + 新密码
     * @throws BizException BAD_CREDENTIALS —— 旧密码不对
     *
     * ★ 为什么旧密码错也用 BAD_CREDENTIALS 而不是新加一个错误码？
     *   语义一致：都是"凭证不对"。
     *   而且复用已有错误码，前端不用新增处理分支。
     *
     * ★★ 事务和 Redis 的关系（重要）：
     *   数据库操作受 @Transactional 控制，但 **Redis 操作不受**。
     *   如果 revokeAllRefresh 成功、之后数据库提交失败回滚：
     *       → 密码没改成，但所有设备被踢了
     *       → 用户需要重新登录，但密码还是旧的（稍微困扰，但不危险）
     *
     *   如果反过来（Redis 失败、数据库成功）：
     *       → 密码改了，但旧 token 还有效（危险！）
     *
     *   ★ 所以顺序很重要：**先改数据库，再踢下线**。
     *     这样最坏情况是"被踢了但密码没改"，用户可以重新登录。
     *     而不能是"密码改了但没踢"，那就等于没改。
     */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {

        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));

        /*
         * 【第一步：验证旧密码】
         *
         * matches(明文, 哈希) —— 顺序不能反！
         * 反了编译能过（都是 String），但永远返回 false。
         */
        if (!passwordEncoder.matches(req.oldPassword(), user.getPasswordHash())) {
            log.warn("改密码失败：原密码错误, userId={}", userId);
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }

        /*
         * 【第二步：加密并设置新密码】
         *
         * ★ 不要判断"新旧密码是否相同"——
         *   即使相同，重新加密一次也无害（BCrypt 每次结果都不同）。
         *   如果要禁止，加一行 if 即可，但产品上没这个必要。
         */
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));

        /*
         * 【第三步：踢掉所有设备的登录态】
         *
         * ★★ 这一步是安全要求，不是可选优化。
         *
         *   改密码往往意味着"怀疑账号被盗"。
         *   如果不踢，攻击者手里的 refresh token 还能继续换新 access，
         *   你的密码改了也白改。
         *
         * ★ 注意：这只清了 refresh 白名单。
         *   已经签发的 access token 在剩下的 2 小时内【仍然可用】。
         *   要彻底失效，需要把每个 access 的 jti 拉黑 ——
         *   但我们不知道用户手上有哪些 access token（无状态）。
         *
         *   ★ 行业常见做法就是这样：接受最多 2 小时的窗口，
         *     或者把 access 有效期设得更短（比如 15 分钟）。
         *     本项目选 2 小时，是个可接受的平衡。
         */
        tokenService.revokeAllRefresh(userId);

        log.info("密码已修改，所有设备已强制下线, userId={}", userId);
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 这个类的三个设计要点（面试可讲）】
     *
     *  1. 用 /me 而不是 /{id}：用户身份由服务端从 token 推导，
     *     从根本上杜绝越权（客户端没有传别人 id 的机会）
     *
     *  2. 改密码必须验证旧密码：防止"电脑没锁屏被人改密码"
     *
     *  3. 改密码后必须踢所有设备：否则攻击者的会话还能继续用
     *
     *  ★ 第 2、3 条是很多项目会漏掉的安全细节，
     *    面试时主动说出来，能体现安全意识。
     * ═══════════════════════════════════════════════════════════════
     */
}
