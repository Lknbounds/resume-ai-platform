package com.jianli.user.dto;

import com.jianli.user.entity.AppUser;

import java.time.LocalDateTime;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  用户的"对外视图"。前端能看到的用户信息，就是这个类里的字段。
 *
 *  VO = View Object，专门用于"返回给前端看"的对象。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：它是 Entity 的"安全投影"】
 *
 *      AppUser（Entity，数据库里的完整行）
 *      ┌────┬──────────┬───────────────┬──────────┬──────────────┐
 *      │ id │ username │ password_hash │ nickname │ target_pos   │ ...
 *      └────┴──────────┴───────────────┴──────────┴──────────────┘
 *                          ✂️ 剪掉
 *      ┌────┬──────────┬──────────┬──────────────┬──────────────┐
 *      │ id │ username │ nickname │ target_pos   │ years_of_exp │  → UserVO
 *      └────┴──────────┴──────────┴──────────────┴──────────────┘
 *
 *      UserService.getProfile(userId)
 *         └─ userRepository.findById(userId)     → AppUser
 *              .map(UserVO::of)                   → ★ 就是这里转换
 *              .orElseThrow(...)
 *              ↓
 *      Controller 包成 ApiResponse<UserVO> → JSON → 前端
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么必须有 VO，不能直接返回 Entity？四个理由】
 *
 *  ① 【安全】—— 最重要的一条
 *     AppUser 里有 passwordHash。直接返回的话：
 *         {"id":1,"username":"alice","passwordHash":"$2a$10$N9qo8uLO...", ...}
 *     前端的 F12 里能清清楚楚看到。虽然它是哈希不能直接反推出密码，
 *     但泄露哈希等于给了攻击者离线爆破的素材：
 *         拿着 $2a$10$... 在自己机器上无限次尝试，不受登录限流约束。
 *     而且如果哪天表里加了"手机号""身份证"字段，直接返回就全漏了。
 *
 *  ② 【解耦】—— 表结构变了，前端不用跟着改
 *     假设某天 AppUser 加了一列 last_ip。
 *     如果用 Entity 当 VO，前端会突然多收到一个字段（可能困惑，也可能泄露）
 *     如果只用 VO，什么都不变 —— 要不要暴露是新决定，不是被动的。
 *
 *  ③ 【派生字段】—— VO 可以有"算出来的"字段
 *     比如简历列表里要显示"最新得分"，那不是 AppUser 的字段，
 *     是查了 analysis_report 之后算出来的。这种字段只能放在 VO 里。
 *
 *  ④ 【校验注解不污染持久层】
 *     @NotBlank、@Size 这些是"接口层"的规则。
 *     放在 Entity 上会让 JPA 也去做校验，
 *     而且改接口规则时会意外影响数据库操作。
 *
 *  ★ 一句话：Entity 是"数据长什么样"，VO 是"给别人看什么"。这两件事必须分开。
 *
 *
 *  【★ 为什么这个类没有 createdAt 的时区问题？】
 *  LocalDateTime 会由 JacksonConfig 统一格式化成 "2026-11-18 20:30:00"。
 *  （见 JacksonConfig 的说明 —— 那里解释了为什么 yml 里的 date-format 不管用）
 *
 *  【现在这个类还没被用上？】
 *  是的 —— 步骤 2 只是把类建好，步骤 3 的登录接口和 /users/me 接口才会用到。
 *  先建好是因为：AuthService 需要它来组装 TokenResponse。
 */
public record UserVO(

        /** 用户 id。前端用它做路由参数（比如 /users/1） */
        Long id,

        /** 登录名。一般只在"个人资料"页展示，其他地方显示昵称 */
        String username,

        /** 昵称。界面上到处显示的就是它 */
        String nickname,

        /** 目标岗位。可为 null（用户没填） */
        String targetPosition,

        /**
         * 经验年限。可为 null？
         * ★ 实际不会 —— AppUser 的表结构是 NOT NULL DEFAULT 0，
         *   而且 @PrePersist 里也兜底了。但类型保留 Integer 以和 Entity 一致。
         */
        Integer yearsOfExperience,

        /**
         * 注册时间。
         *
         * ★ 为什么返回它？
         *   个人资料页可以显示"加入于 2026-11-01"。
         *   小细节，但让产品显得完整。
         */
        LocalDateTime createdAt,

        /**
         * 最后登录时间。
         *
         * ★ 为什么返回它？
         *   用户可以据此判断"有没有人在我不知道的时候登录过我的账号"。
         *   这是很多产品都有的安全提示。
         *
         * ★ 注意：这个字段在登录成功时才更新（AuthService.login 里 set）。
         *   所以"刚登录完看到的值"其实是【上一次】的登录时间。
         *   如果要显示"本次登录时间"，得在 update 之后再查一次。
         *   本项目的处理方式是：登录时先更新、再组装响应，
         *   所以这里拿到的是本次登录时间（Time 上差别在毫秒级，用户无感）。
         */
        LocalDateTime lastLoginAt
) {

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【Entity → VO 的转换方法】
     * ═══════════════════════════════════════════════════════════════
     *
     * 用法：
     *      AppUser user = userRepository.findById(1).orElseThrow();
     *      UserVO vo = UserVO.of(user);
     *
     * ★ 为什么写成 static 工厂方法，而不是单独的 Converter 类？
     *   1. 转换逻辑和 VO 放一起，看 VO 就知道它能从 Entity 怎么转过来
     *   2. 不需要额外的类文件
     *   3. 只做一次简单映射，不值得引入 MapStruct 之类的工具
     *
     * ★ 什么时候该用 MapStruct / 单独的 Converter？
     *   当 VO 多到几十个、字段映射关系复杂（嵌套、集合、条件转换）时。
     *   那样手写确实累，工具能自动生成。
     *   本项目规模不大，手写更直接、更好调试、面试时能讲清每一行。
     *
     * ★ 那如果 VO 多了，每个都写 of() 会不会重复？
     *   会有点。但每个 VO 的 of() 逻辑都不一样（有的要查关联表、有的要算分），
     *   强行抽象反而更绕。这是"适度重复优于错误抽象"。
     *
     * @param u 从数据库查出来的实体
     * @return 只含可暴露字段的 VO
     */
    public static UserVO of(AppUser u) {
        /*
         * ★ 为什么不判断 u == null？
         *   because 调用方都是从 Optional 里取的：
         *       repo.findById(id).map(UserVO::of)
         *   能进到 of() 说明一定不为 null。
         *
         *   如果在这里也判 null，等于重复防御，而且还要决定"返回 null 还是抛异常"，
         *   反而让调用方困惑。
         *
         *   ★ 原则：null 检查放在"数据进入系统的边界"（比如 Repository 返回 Optional），
         *     而不是每一层都检查一遍。
         */
        return new UserVO(
                u.getId(),
                u.getUsername(),
                u.getNickname(),
                u.getTargetPosition(),
                u.getYearsOfExperience(),
                u.getCreatedAt(),
                u.getLastLoginAt()
                /*
                 * ═══════════════════════════════════════════════════
                 *  ★★ 注意：这里【故意没有】传 passwordHash。
                 *
                 *  这就是 VO 的意义 —— 不是"忘了写"，是"明确地排除"。
                 *
                 *  以后如果有人要往 VO 加字段，必须问自己：
                 *      "这个字段能让前端看到吗？"
                 *  这个"必须想一想"的动作本身就是安全设计。
                 * ═══════════════════════════════════════════════════
                 */
        );
    }
}
