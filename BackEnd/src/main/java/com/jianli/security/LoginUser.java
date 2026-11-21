package com.jianli.security;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  表示"当前登录的是谁"。只有两个字段：id 和 username。
 *
 *  它是认证信息的载体，会在请求处理过程中被放进 Spring Security 的上下文里，
 *  之后任何地方都能取出来。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：它在哪儿产生、在哪儿被用】
 *
 *      JwtAuthenticationFilter 解析 token
 *         ├─ 从 Claims 里取出 userId 和 username
 *         ├─ new LoginUser(userId, username)          ← ★ 在这里创建
 *         └─ SecurityContextHolder.setAuthentication(...)   ← 放进"当前请求的保险箱"
 *              ↓
 *      Controller 的参数上写 @CurrentUserId Long userId
 *         └─ CurrentUserIdResolver 从保险箱里取出 LoginUser，返回它的 id
 *              ↓
 *      Service 收到 userId，用它做归属校验
 *         └─ resumeRepository.findByIdAndUserId(resumeId, userId)
 *
 *  ★ 它只活在一次请求内。请求结束，Spring 自动清空上下文，
 *    下一个请求是全新的（正因为如此，每次请求都要重新验 token）。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么只有两个字段？为什么不把整个 AppUser 放进来？】
 *
 *  1. 【性能】每个请求都要验 token，如果每次查一遍数据库拿完整用户对象，
 *     并发高了数据库扛不住。而用户 id 和用户名在 token 里就有，不用查库。
 *
 *  2. 【安全性】如果把 AppUser 放进来（里面有 passwordHash），
 *     一旦某处代码不小心把它返回给前端，密码哈希就泄露了。
 *     只放必要信息，从源头减少风险。
 *
 *  3. 【时效性】即使数据库里用户改了昵称，请求上下文里还是旧值 ——
 *     但这没关系，因为需要最新数据的地方会主动查库（比如查个人资料）。
 *
 *  ★ 需要完整用户信息时怎么办？
 *    在 Service 里按 id 查：userRepository.findById(loginUser.id())。
 *    多一次查询，但换来"数据一定是新的"，这是合理的取舍。
 *
 *
 *  【★ 为什么用 record 而不是普通 class？】
 *
 *  1. 不可变：认证信息一旦确定，在这次请求内就不该被改。
 *     如果可变，某个 Filter 改了它，后面的代码就会被骗。
 *
 *  2. 代码短：两个字段如果用 class，要写构造器 + 两个 getter + equals
 *     + hashCode + toString，一共 30 行。record 一行搞定。
 *
 *  3. 没有 setter：杜绝了"不小心改了当前用户"的可能。
 *
 *
 *  【★ 为什么字段类型 userId 是 Long，不是 String？】
 *  token 的 sub 里存的确实是字符串 "1"，但业务代码里到处用的是 Long。
 *  在"进入系统"的边界上（JwtTokenProvider.userId）就把类型转换做好，
 *  之后的代码就都用 Long，不需要到处 Long.valueOf(...)。
 *
 *  这叫"在边界处做类型转换，让内部类型干净"。
 */
public record LoginUser(

        /**
         * 用户 id（数据库主键）。
         *
         * ★ 这是最重要的字段 —— 所有归属校验都靠它：
         *       "这份简历是不是你的"       → resume.userId == loginUser.id()
         *       "这个帖子是不是你发的"     → post.userId == loginUser.id()
         *       "你不能给自己的帖子点赞"   → post.userId == loginUser.id()
         */
        Long id,

        /**
         * 用户名。
         *
         * ★ 为什么认证只需要 id，还要带 username？
         *   纯粹是为了【日志排查方便】。
         *   日志里打 "userId=7" 你还得去数据库查是谁；
         *   打 "userId=7(alice)" 一眼就明白了。
         *
         * ★ 它不参与任何权限判断 —— 权限只认 id。
         */
        String username
) {

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【为什么这个类没有 @Component / @Service 注解？】
     *
     *  因为它不是"由 Spring 创建和管理的对象"，而是"每次请求临时创建的"。
     *  Spring 容器里不应该有一个 LoginUser 的 Bean —— 那意味着"全局只有一个当前用户"，
     *  显然不对。
     *
     *  ★ 区分两类对象：
     *    Spring 管理的 Bean（有 @Component 等）→ 全局唯一，多请求共享
     *    数据对象（无注解）                    → 每次 new，只属于当前请求
     * ═══════════════════════════════════════════════════════════════
     */

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 关于二期 RBAC：为什么这里没有权限字段？】
     *
     *  你可能会想：管理员和普通用户，不都在这个类里区分吗？
     *
     *  ★ 现在确实没有，这是【刻意的】，理由很重要：
     *
     *  如果把角色写进 JWT：
     *      用户在 10:00 登录 → token 里写着 roles=["ADMIN"]
     *      你在 10:30 撤销了他的管理员权限
     *      → 他手里的 token 在 12:00 之前【依然是 ADMIN】
     *      → 撤权失效 2 小时
     *
     *  正确的做法（附B 文档里有完整设计）：
     *      JWT 只放身份（userId、jti、类型、过期）
     *      每次请求从服务器（DB + Redis 缓存）加载权限
     *      → 权限变更立即生效
     *
     *  所以二期做 RBAC 时，这个类会变成：
     *      public record LoginUser(Long id, String username, Set<String> authorities)
     *  而 authorities 由 JwtAuthenticationFilter 在每次请求时填充，
     *  不是从 token 里读出来的。
     * ═══════════════════════════════════════════════════════════════
     */
}
