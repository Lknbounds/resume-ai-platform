package com.jianli.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  注册接口的"请求体"。前端提交的 JSON 会被 Jackson 反序列化成这个对象。
 *
 *  前端发：
 *      POST /api/auth/register
 *      Content-Type: application/json
 *      {"username":"alice","password":"Passw0rd1","nickname":"Alice"}
 *
 *  后端收到：
 *      RegisterRequest req = new RegisterRequest("alice", "Passw0rd1", "Alice")
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：JSON 是怎么变成这个对象的】
 *
 *      前端 http.post('/auth/register', { username: 'alice', ... })
 *         ↓  序列化成 JSON 文本
 *      请求体: {"username":"alice","password":"Passw0rd1","nickname":"Alice"}
 *         ↓
 *      Controller 参数 @RequestBody @Valid RegisterRequest req
 *         ├─ @RequestBody → Jackson 按【字段名】把 JSON 键映射到构造器参数
 *         │      "username" → 第一个参数 username
 *         │      "password" → 第二个参数 password
 *         │      "nickname" → 第三个参数 nickname
 *         │      ★ 名字必须完全一致（区分大小写），否则那个字段就是 null
 *         │
 *         └─ @Valid → 检查下面那些约束注解，任何一个不满足就抛异常
 *               → GlobalExceptionHandler.handleValid 接住
 *               → 返回 400 + {"code":10000,"data":[{"field":"username","message":"..."}]}
 *         ↓
 *      方法体能执行，说明所有校验都通过了
 *         ↓
 *      AuthService.register(req)
 *         └─ 从 req 里取值：req.username()、req.password()、req.nickname()
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么用 record？】
 *
 *  1. 不可变：请求数据读进来就不该被改。如果某处代码改了 req.password()，
 *     后面的校验就和用户实际提交的不一致了。
 *
 *  2. Jackson 反序列化 record 的机制：
 *      record 没有 setter，Jackson 会调用【全参构造器】，
 *      按【参数名】匹配 JSON 键。
 *      ★ 这就是为什么字段顺序要和前端 JSON "自然对应" ——
 *        其实顺序无所谓（靠名字匹配），但参数名必须和 JSON 键一致。
 *
 *      ★ 有个前提：Java 编译时默认【不保留参数名】。
 *        Spring Boot 的 maven 插件默认开启了 -parameters 参数（pom.xml 里有），
 *        所以我们不需要在每个参数上加 @JsonProperty("username")。
 *        如果哪天换了构建配置，发现所有字段都是 null，先怀疑这个开关。
 *
 *  3. 自带 toString（但不含敏感字段的问题见下）
 *
 *  【★ 为什么不直接复用 AppUser 实体？】
 *      1. AppUser 有 id、createdAt、updatedAt 等字段，但注册时前端根本不会传这些
 *      2. AppUser 的 passwordHash 是哈希，而注册时前端传的是明文密码，语义不同
 *      3. 如果把 Entity 当请求体，等于把数据库结构暴露给前端 ——
 *         哪天表加了一列，前端就能传这个字段进来，是个越权风险
 */
public record RegisterRequest(

        /**
         * 用户名。
         *
         * @NotBlank
         *      不能为 null，且去掉首尾空格后不能是空字符串。
         *      ★ 和 @NotNull 的区别：
         *          @NotNull  → 只挡 null，"   " 这种纯空格能通过
         *          @NotBlank → 连纯空格也挡掉
         *        用户输入必须用 @NotBlank。
         *
         * @Size(min = 4, max = 32)
         *      长度限制，和数据库的 VARCHAR(32) 对应。
         *      ★ 为什么要和数据库一致？
         *        数据库是 VARCHAR(32)，如果这里允许 100 个字符，
         *        超长的会插入失败，抛出 DataIntegrityViolationException，
         *        用户看到的是"系统繁忙"，很糟糕。
         *        在校验层就挡住，能给出明确的错误提示。
         *
         * @Pattern(regexp = "^[A-Za-z0-9_]+$")
         *      只允许字母、数字、下划线。
         *      ★ 为什么限制这些字符？
         *        1. 避免中文、空格导致的各种问题
         *        2. 用户名可能被用在 URL、日志里，特殊字符要转义，麻烦
         *        3. 也防止有人用 emoji 或特殊符号冒充他人
         *
         *      正则含义：
         *          ^       开头
         *          [...]   字符集
         *          +       一个或多个
         *          $       结尾
         *
         *      ★ message 属性：校验失败时返回给前端的提示。
         *        不写的话会用默认英文提示（"must match ..."），体验差。
         */
        @NotBlank(message = "用户名不能为空")
        @Size(min = 4, max = 32, message = "用户名长度必须在 4-32 位之间")
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "用户名只能包含字母、数字、下划线")
        String username,

        /**
         * 密码（明文）。
         *
         * ★ 注意：这里收到的是【明文】，存进数据库前必须 BCrypt 加密。
         *   加密发生在 AuthService.register 里：
         *       user.setPasswordHash(passwordEncoder.encode(req.password()));
         *
         * ★ 明文密码会出现在哪里？会不会泄露？
         *   1. 不写进数据库（存的是哈希）    ✅
         *   2. 不打进日志（我们从不 log req）✅
         *   3. 在内存里短暂存在              ⚠️ 无法避免，但请求结束就回收
         *
         *   ★ 所以有一条纪律：任何地方都不要 log.info("req={}", req)，
         *     因为 record 自动生成的 toString() 会把 password 打出来！
         *
         *     record 的 toString 长这样：
         *         RegisterRequest[username=alice, password=Passw0rd1, nickname=Alice]
         *                                                            ↑ 明文泄露
         *
         *     解决方式（如果需要打日志）：
         *         在 record 里重写 toString()，把 password 遮盖成 ***。
         *         本项目不需要打这个日志，所以没重写 —— 但你要知道这个风险。
         *
         * @Size(min = 8, max = 32)
         *      ★ 为什么最少 8 位？
         *        4 位密码大约 1 秒就能爆破完。8 位配合字母+数字，
         *        在 BCrypt 的慢哈希下要算很多年。
         *
         * @Pattern 要求同时包含字母和数字
         *      ^               开头
         *      (?=.*[A-Za-z])  后面某处至少有一个字母（前瞻断言）
         *      (?=.*\d)        后面某处至少有一个数字
         *      .+              整串至少一个字符
         *      $               结尾
         *
         *      ★ (?=...) 叫"零宽正向先行断言"：只检查条件，不消耗字符。
         *        所以在同一位置可以连续断言多次 —— 这就是"同时包含"的写法。
         *
         *      ★ 为什么不要求"必须包含特殊字符"？
         *        研究表明强制特殊字符会让用户写成 "Passw0rd!" 这种可预测的形式，
         *        反而降低强度。长度限制 + 字母数字混合已经是较好的平衡。
         */
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 32, message = "密码长度必须在 8-32 位之间")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码必须同时包含字母和数字")
        String password,

        /**
         * 昵称。展示用的名字，可以中文、可以有表情符号。
         *
         * ★ 为什么不加 @Pattern 限制字符？
         *   昵称是给人看的，中文、emoji 都应该允许。
         *   而且它不进 URL、不进日志，没有特殊字符带来的风险。
         *
         * ★ @Size(max = 16) 为什么比数据库的 VARCHAR(32) 小？
         *   数据库留了余量（32），校验层更严格（16）。
         *   这是常见做法：数据库宽容一点，防止某些历史数据或直接 SQL 写入被卡住；
         *   接口层严格一点，保证前端展示不会溢出。
         *
         * ★ 为什么没有 min？
         *   @NotBlank 已经保证至少有一个非空白字符了。
         *   再限制 min=2 会让单字昵称（比如"李"）无法使用。
         */
        @NotBlank(message = "昵称不能为空")
        @Size(max = 16, message = "昵称最长 16 个字符")
        String nickname

        /*
         * ═══════════════════════════════════════════════════════════════
         *  【★ 为什么这里没有 @Email、@Min 之类的字段？】
         *
         *  因为注册只需要这三样。手机号、邮箱、头像都是"以后再说"的功能。
         *
         *  ★ 一个重要的设计原则：DTO 只包含【这个接口真正需要】的字段。
         *    不要做一个"UserDTO"给所有接口共用 ——
         *    那样注册接口会要求填一堆用不上的东西，
         *    而且改任何一处的字段都会影响其他接口。
         *
         *    每个接口一个 DTO，宁可多几个类，也不要耦合。
         * ═══════════════════════════════════════════════════════════════
         */
) {}
