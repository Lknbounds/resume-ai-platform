package com.jianli.common;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  "全局异常捕获器"。项目里任何地方抛出的异常，最后都会跑到这里，
 *  被翻译成统一格式的 JSON 返回给前端。
 *
 *  一句话理解它的作用：
 *      业务代码只管 throw，转成什么样的响应由这里统一决定。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：异常从产生到变成 JSON 的完整路径】
 *
 *      任意位置（Controller / Service / Domain Service）
 *         │  throw new BizException(...) 或 throw new IllegalStateException(...)
 *         ↓
 *      异常沿着调用栈向上冒泡（中间层都不接）
 *         ↓
 *      DispatcherServlet 在调用完 Controller 后捕获到异常
 *         ↓  遍历所有 @RestControllerAdvice 类
 *         ↓  在本类里找 @ExceptionHandler(异常类型.class)
 *         ↓  ★ 按"最具体匹配"选：BizException 优先于 Exception
 *      本类的某个方法（handleBiz / handleValid / handleOther ...）
 *         │  ★ 返回值不是 JSON，是 ResponseEntity<ApiResponse>
 *         ↓
 *      Jackson 序列化成 JSON
 *         ↓
 *      HTTP 响应（状态码由这里决定，不是 200！）
 *
 *  ★ 注意：本类【不会拦截正常的返回值】。
 *    正常流程是 Controller → Jackson → 响应，不经过这里。
 *    只有抛了异常才会绕到本类。
 *
 *
 *  【★ 为什么本类的方法都返回 ResponseEntity 而不是 ApiResponse】
 *
 *  如果直接 return ApiResponse：
 *      方法返回什么，HTTP 状态码就永远是 200
 *      → 用户看到"404 资源不存在"，但浏览器/监控认为"请求成功"
 *      → Nginx 日志里全是 200，出事了根本发现不了
 *
 *  用 ResponseEntity 才能"状态码 + 响应体"分开控制：
 *      ResponseEntity.status(404).body(ApiResponse.fail(...))
 *                     ↑ 状态码       ↑ 响应体
 *
 *  这对应我们的双轨设计：HTTP 码表达大类，业务码表达具体原因。
 *
 *
 *  【@RestControllerAdvice 是什么意思？】
 *  它是一个组合注解：
 *      @ControllerAdvice  → "我是一个专门处理全局事情的切面"，
 *                           Spring 会让它作用于所有 Controller。
 *      @ResponseBody      → "我返回的对象要转成 JSON 写进响应体"
 *                          （和我们 Controller 上那个 @RestController 里的含义一样）。
 *
 *  没有它的话，你只能在每个 Controller 方法里写 try-catch，
 *  100 个接口就写 100 遍，漏一个就把 Java 堆栈直接吐给前端 ——
 *  既难看，又泄露内部类名、文件路径、SQL 语句，是可以被攻击者利用的情报。
 *
 *
 *  【@ExceptionHandler 是怎么"分配"的？】
 *  Spring 拿着抛出来的异常类型，去这个类里找【最匹配】的方法：
 *      - 抛出 BizException          → 命中 handleBiz（因为它写的就是 BizException.class）
 *      - 抛出校验异常               → 命中 handleValid
 *      - 抛出上面都没列出的类型     → 命中 handleOther（因为它是 Exception.class，最宽泛）
 *
 *  "最匹配"= 类型最具体。Spring 会自动选出最接近实际异常类型的那一个，
 *  所以你不用手动写 @Order 排序。
 *
 *  ★ 这解释了为什么必须有一个 Exception.class 的兜底方法：
 *    如果没有它，任何没预料到的异常（NullPointerException、数组越界...）
 *    都会直接抛给 Tomcat，返回一个非 JSON 的默认错误页，前端解析就崩了。
 *
 *
 *  【@Slf4j 是什么】
 *  Lombok 注解，自动在这个类里生成一个 logger 对象：
 *      private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
 *  所以下面可以直接写 log.warn(...) / log.error(...)，不用手写那一行。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 【业务异常】→ 我们在代码里主动抛的那一类。
     *
     * 处理逻辑：从异常里取出 ErrorCode，用它自带的 httpStatus 作为 HTTP 状态码。
     * 所以同一个方法能处理所有业务错误 —— 43002 自动变 422，20001 自动变 409，不用写 if。
     *
     * 为什么用 log.warn 而不是 log.error？
     *   业务异常是"预料之中的失败"（用户输错密码、内容不合规），
     *   不是系统故障，不应该污染错误日志（否则真正的故障会被淹没）。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> handleBiz(BizException e) {
        ErrorCode ec = e.getErrorCode();
        log.warn("biz error code={} msg={}", ec.getCode(), e.getMessage());
        return ResponseEntity.status(ec.getHttpStatus())
                .body(ApiResponse.fail(ec, e.getData(), e.getMessage()));
    }

    /**
     * 【参数校验失败】→ Controller 参数上写了 @Valid，但字段不符合校验注解（比如 @NotBlank 传了空）。
     *
     * Spring 会把每个字段的错误收集在 BindingResult 里，
     * 我们把它转成一个"字段名 + 错误信息"的列表返回，前端就能精确提示
     * "用户名不能为空"而不是笼统的"参数错误"。
     *
     * 返回的 data 长这样：
     *   [{"field":"username","message":"不能为空"},
     *    {"field":"password","message":"长度必须在 8 到 32 之间"}]
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValid(MethodArgumentNotValidException e) {
        List<FieldErrorItem> items = e.getBindingResult().getFieldErrors().stream()
                .map(this::toItem)
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, items));
    }

    /**
     * 【请求体不是合法 JSON】→ 前端传的 JSON 格式错了（比如少了个引号、多打了个逗号）。
     *
     * 这种情况要单独处理，因为如果不接住，Spring 默认会返回一大段
     * 带 Jackson 内部类名的错误信息，对用户毫无意义还泄露实现细节。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnreadable(HttpMessageNotReadableException e) {
        // 用 debug 级别，因为这条对排查有帮助（能看到具体解析位置），但不该刷屏
        log.debug("unreadable request body: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, null));
    }

    /**
     * 【访问了不存在的路径】→ 比如浏览器访问 /api/not-exist。
     *
     * 为什么是 NoResourceFoundException 而不是 NoHandlerFoundException？
     *   因为 Spring Boot 3.2 之后，找不到路径时抛的是前者。
     *   网上大量老教程写的是后者，照抄会不生效 —— 你会发现这个 handler 从来没被触发过。
     *
     * 不接住的话，Spring 会返回它默认的 JSON：
     *   {"timestamp":...,"status":404,"error":"Not Found","path":"/api/not-exist"}
     * 格式和我们的统一响应体不一样，前端解析器就会出错。
     * 验证命令里"期望返回 code=10003 而不是 Spring 默认格式"就是在检查这一点。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(ApiResponse.fail(ErrorCode.NOT_FOUND, null));
    }

    /**
     * 【没权限】→ 已经登录了，但没资格做这件事。
     *
     * 注意区分这两个概念，面试常考：
     *   401 Unauthorized = "你是谁？"（没登录 / token 无效）→ ErrorCode.UNAUTHORIZED
     *   403 Forbidden    = "我知道你是谁，但你不能干这个"   → ErrorCode.FORBIDDEN
     *
     * Spring Security 的 @PreAuthorize 鉴权失败时抛的就是 AccessDeniedException（步骤 3 之后会用到）。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Object>> handleDenied(AccessDeniedException e) {
        return ResponseEntity.status(403).body(ApiResponse.fail(ErrorCode.FORBIDDEN, null));
    }

    /**
     * 【兜底】→ 所有没被上面接住的异常最后都到这里。
     *
     * ★ 这个方法体现了"对外隐藏，对内保留"的安全原则：
     *   对用户：只回一句"系统繁忙，请稍后再试"，看不出任何内部结构
     *   对开发：完整的堆栈写进日志，配合 traceId 能精确定位到是哪次请求
     *
     * 如果把 e.printStackTrace() 的内容直接返回给前端，
     * 攻击者就能看到你的包名、类名、用了什么框架、甚至 SQL 语句和数据库地址。
     *
     * log.error 的四个参数：("消息模板", 参数1, 参数2, 异常对象)
     *   最后一个参数是异常对象时，logback 会自动打印完整堆栈。
     *   这就是验证里"日志里有 ERROR 堆栈、响应里没有"的实现方式。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleOther(Exception e) {
        log.error("unexpected error, traceId={}", MDC.get("traceId"), e);
        return ResponseEntity.status(500)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, null));
    }

    /**
     * 把 Spring 的 FieldError 转换成我们要返回的结构。
     * 直接用 FieldError 也行，但它字段太多（还带着 rejectedValue 等内部信息），
     * 精简一下对前端更友好。
     */
    private FieldErrorItem toItem(FieldError f) {
        return new FieldErrorItem(f.getField(), f.getDefaultMessage());
    }

    /**
     * 字段级错误的对外结构。用 record 而不是内部类，因为它是纯数据。
     *
     * 为什么定义为 private static 的嵌套 record？
     *   它只在当前类里用，没必要单独建文件；record 也可以这样定义。
     */
    public record FieldErrorItem(String field, String message) {}
}
