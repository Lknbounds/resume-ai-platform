package com.jianli.user.controller;

import com.jianli.common.ApiResponse;
import com.jianli.user.dto.LoginRequest;
import com.jianli.user.dto.RefreshRequest;
import com.jianli.user.dto.RegisterRequest;
import com.jianli.user.dto.TokenResponse;
import com.jianli.user.dto.UserVO;
import com.jianli.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  认证相关的 4 个接口：注册、登录、刷新、登出。
 *
 *  ★ 这个类几乎没有逻辑 —— 全部委托给 AuthService。
 *    这是 Controller 的正确形态：它只负责"接活"和"交货"。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：它站在哪个位置】
 *
 *      浏览器 POST /api/auth/login
 *         ↓
 *      过滤器链（TraceIdFilter 贴 id、JwtAuthenticationFilter 尝试认人——
 *               登录请求本来就没 token，认不出，不影响）
 *         ↓
 *      不需要登录（SecurityConfig 里放行了 /api/auth/login）
 *         ↓
 *      DispatcherServlet 路由匹配 → 【本类】login()
 *         ├─ @RequestBody + @Valid 把 JSON 变成 LoginRequest 并校验
 *         ├─ 调 authService.login(req)
 *         └─ 包成 ApiResponse<TokenResponse>
 *         ↓
 *      Jackson 序列化 → HTTP 响应
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【@RestController + @RequestMapping("/api/auth") 干什么】
 *
 *  @RestController
 *      = @Controller + @ResponseBody，返回值会被转成 JSON 写进响应体
 *      （详细解释见 DemoController 的类注释）
 *
 *  @RequestMapping("/api/auth")
 *      给本类所有接口加路径前缀。
 *      方法上的 @PostMapping("/login") 最终路径 = /api/auth/login
 *
 *  【@Tag(name = "认证") 干什么】
 *      Swagger 分组名。在 Swagger 页面上会看到"认证"这一组，
 *      里面就是这 4 个接口。纯文档作用，不影响功能。
 *
 *
 *  【★ 为什么这 4 个接口都不需要登录？】
 *
 *  ★ 关键：它们必须在 SecurityConfig 里被放行，否则会死锁：
 *      - 登录需要 token 吗？不需要（还没登录哪来的 token）
 *      - 但如果 /api/auth/login 需要认证，用户就永远登录不了
 *
 *  所以 SecurityConfig 里有：
 *      .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh").permitAll()
 *
 *  ★ 但 logout 是例外 —— 它需要登录（或者至少需要传一个 token）。
 *    不过我们也把它放行了，因为登出时 token 可能已过期，
 *    这时候也应该让用户"成功登出"（前端清掉本地状态）。
 *    所以 logout 在 SecurityConfig 里也是 permitAll，
 *    它自己内部判断 token 有效就拉黑、无效就忽略。
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证")
@RequiredArgsConstructor
public class AuthController {

    /**
     * 由 Spring 通过构造器注入。
     *
     * ★ Controller 只依赖 Service，【绝不直接依赖 Repository】。
     *   原因：业务规则都在 Service 里，如果 Controller 能直接查库，
     *   就会出现"某个接口跳过了归属校验"这类漏洞。
     */
    private final AuthService authService;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【一、注册】POST /api/auth/register
     * ═══════════════════════════════════════════════════════════════
     *
     * 请求：
     *      POST /api/auth/register
     *      Content-Type: application/json
     *      {"username":"alice","password":"Passw0rd1","nickname":"Alice"}
     *
     * 响应（201 Created）：
     *      {
     *        "code": 0, "message": "ok",
     *        "data": {"id":1,"username":"alice","nickname":"Alice",...},
     *        "traceId": "...", "timestamp": ...
     *      }
     *
     * 错误：
     *      400 + code=10000  参数校验失败（用户名太短、密码没数字等）
     *      409 + code=20001  用户名已被占用
     *
     * ★ 为什么返回 201 而不是 200？
     *   HTTP 语义：201 Created 表示"创建了新资源"，并且通常带 Location 头
     *   指向新资源。200 表示"请求成功"但不特指创建。
     *   ★ 严格说返回 201 更规范，面试时能讲出这个区别是加分项。
     *     不过前端一般不区分（都是 2xx），所以实际影响很小。
     *
     * ★ 为什么返回 ResponseEntity 而不是直接返回 ApiResponse？
     *   因为要自定义状态码（201）。直接返回 ApiResponse 的话，
     *   Spring 默认给 200，无法表达"创建成功"这个语义。
     *   本类只有这个方法需要 201，其他三个用默认 200 就够了。
     */
    @PostMapping("/register")
    @Operation(summary = "注册", description = "创建新账号，注册后需要单独调登录接口")
    public ResponseEntity<ApiResponse<UserVO>> register(
            @RequestBody @Valid RegisterRequest req) {

        /*
         * @RequestBody
         *     告诉 Spring："从请求体里读 JSON，反序列化成 RegisterRequest"。
         *     ★ 如果少了这个注解，Spring 会以为 req 是查询参数，得到 null。
         *
         * @Valid
         *     触发 RegisterRequest 里那些 @NotBlank / @Size / @Pattern 的校验。
         *     ★ 少了它，校验注解完全不生效 —— 空用户名也能注册进来。
         *     这是很常见的疏漏，加注解时一定要成对加。
         *
         * ★ 校验失败会怎样？
         *   在进入方法体之前就抛 MethodArgumentNotValidException，
         *   被 GlobalExceptionHandler.handleValid 接住，返回 400。
         *   所以方法体里不需要写任何判空代码。
         */
        UserVO vo = authService.register(req);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(vo));
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【二、登录】POST /api/auth/login
     * ═══════════════════════════════════════════════════════════════
     *
     * 请求：
     *      {"username":"alice","password":"Passw0rd1"}
     *
     * 响应（200）：
     *      {
     *        "code": 0,
     *        "data": {
     *          "accessToken":  "eyJhbGciOi...",
     *          "refreshToken": "eyJhbGciOi...",
     *          "expiresIn": 7200,
     *          "user": {"id":1,"username":"alice",...}
     *        }
     *      }
     *
     * 错误：
     *      401 + code=20002  用户名或密码错误
     *      （★ 故意不区分是用户名错还是密码错，防枚举）
     *
     * ★ 注意这个方法返回的 token 里【明文可解出 userId 和 username】。
     *   所以不要把 token 当秘密 —— 它是"防伪凭证"，不是"加密容器"。
     */
    @PostMapping("/login")
    @Operation(summary = "登录", description = "返回 access token(2小时) 与 refresh token(7天)")
    public ApiResponse<TokenResponse> login(@RequestBody @Valid LoginRequest req) {

        /*
         * ★ 这里直接返回 ApiResponse（不包 ResponseEntity），
         *   所以 HTTP 状态码是默认的 200。
         *
         * ★ 为什么不在这里打日志？
         *   AuthService 里已经打了（登录成功/失败都会打）。
         *   Controller 再打一遍就重复了。
         *   ★ 原则：同一个事件只在一处记录，否则日志会重复且难读。
         */
        return ApiResponse.ok(authService.login(req));
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【三、刷新 token】POST /api/auth/refresh
     * ═══════════════════════════════════════════════════════════════
     *
     * 请求：
     *      {"refreshToken":"eyJhbGciOi..."}
     *
     * 响应（200）：和登录一样的结构（一对全新的 token）
     *
     * 错误：
     *      401 + code=20003  token 无效（过期、被轮换、已登出、类型不对）
     *
     * ★ 这个接口什么时候被调用？
     *   前端拦截器收到 401 时自动调用，用户完全无感。
     *   详见 04 文档里 http.js 的"刷新单例"实现。
     *
     * ★★ 为什么返回新的 refreshToken，而不是沿用旧的？
     *   因为"轮换"：旧的已经在 AuthService.refresh 里被删了。
     *   如果这里返回旧的，前端下次刷新就会失败。
     *   前端必须用响应里的新 refreshToken 替换本地的。
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新令牌", description = "用 refresh token 换一对新 token，旧的立即作废")
    public ApiResponse<TokenResponse> refresh(@RequestBody @Valid RefreshRequest req) {
        return ApiResponse.ok(authService.refresh(req.refreshToken()));
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【四、登出】POST /api/auth/logout
     * ═══════════════════════════════════════════════════════════════
     *
     * 请求：
     *      POST /api/auth/logout
     *      Authorization: Bearer eyJ...          ← access token 在请求头
     *      {"refreshToken":"eyJ..."}             ← refresh token 在请求体
     *
     * 响应（200）：
     *      {"code":0,"message":"ok","data":null}
     *
     * ★★ 关键设计：登出需要【两个】token
     *
     *   只传 access：access 被拉黑，但 refresh 还能换新 access → 登出不彻底
     *   只传 refresh：refresh 被删，但 access 在 2 小时内还能用 → 不安全
     *   两个都传：   access 进黑名单 + refresh 出白名单 → 立即完全失效
     *
     *   这是安全检查清单里必查的一条。
     *
     * ★ 为什么 access 从请求头取，refresh 从请求体取？
     *   1. access 本来就是每个请求都放请求头，登出请求也不例外，
     *      从请求头取是"顺其自然"
     *   2. refresh token 不应该出现在请求头里（它只该出现在刷新接口的请求体），
     *      这是个安全约定 —— 减少它被中间设备记录的机会
     *
     * ★ @RequestHeader(required = false) 为什么要 false？
     *   如果用户 token 已过期还想登出，前端可能没带 Authorization 头。
     *   这时候也应该让他"成功登出"（清掉本地状态）。
     *   所以 access 允许为空，AuthService 内部会判断。
     *
     * ★ @RequestBody(required = false) 同理 ——
     *   万一前端没传请求体，也不该 400 报错，而应该按"只处理 access"来。
     *   ★ 这样设计让登出接口"永远成功"，符合用户体验预期：
     *     用户点了"退出"，就一定要退出，不能因为技术原因失败。
     */
    @PostMapping("/logout")
    @Operation(summary = "登出", description = "access 进黑名单、refresh 出白名单，两者都必须处理")
    public ApiResponse<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestBody(required = false) RefreshRequest req) {

        /*
         * 【从请求头里剥出裸 token】
         *
         * Authorization 头长这样：
         *      "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.xxx"
         *
         * ★ 为什么要判断 startsWith("Bearer ")？
         *   万一前端传了个格式不对的头（或者别的认证方案），
         *   直接 substring(7) 会截出错误的内容。
         *   判一下更安全，格式不对就当"没传"。
         *
         * ★ 用三元表达式而不是 if：
         *   这是一个简单的二选一赋值，三元更紧凑。
         *   如果里面有多个逻辑分支，还是用 if 更清楚。
         */
        String access = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7)
                : null;

        /*
         * 【refresh 从请求体里取】
         *
         * ★ req == null 的判断必要，因为 @RequestBody(required = false)。
         *   前端可能压根没传请求体。
         */
        String refresh = (req == null) ? null : req.refreshToken();

        /*
         * 【交给 Service 处理】
         *
         * ★ 注意：这个方法【永远返回成功】。
         *   即使两个 token 都无效，也返回 code=0。
         *   因为"登出"这个动作的目标是"让本地状态清空"，
         *   服务端这边能做的都做了（能拉黑就拉黑），
         *   没必要因为 token 已过期就报错让用户困惑。
         */
        authService.logout(access, refresh);

        /*
         * ★ ApiResponse.ok() 不传参数 → data 为 null
         *   前端只需要看 code === 0 就知道成功了。
         */
        return ApiResponse.ok();
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ Controller 的职责边界（再强调一次）】
     *
     *  ✅ 该做：
     *      - 用 @RequestBody / @PathVariable / @RequestParam 接参数
     *      - 用 @Valid 触发校验
     *      - 调 Service 的【一个】方法
     *      - 包成 ApiResponse / ResponseEntity
     *
     *  ❌ 不该做：
     *      - if/else 业务判断（"这个用户能不能注册"）
     *      - 直接调 Repository
     *      - try/catch（GlobalExceptionHandler 管）
     *      - 拼装复杂的数据结构（那是 Service 的事）
     *
     *  ★ 本类 4 个方法都是"一行调用 + 一行返回"，
     *    这就是健康的 Controller 长什么样。
     *    如果你发现某个 Controller 方法超过 20 行，多半是业务逻辑漏在这儿了。
     * ═══════════════════════════════════════════════════════════════
     */
}
