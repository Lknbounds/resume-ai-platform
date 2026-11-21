package com.jianli.common;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  "业务异常"。业务代码发现问题时，抛一个它，然后什么都不用管了。
 *
 *  用法只有一行：
 *      throw new BizException(ErrorCode.USERNAME_EXISTS);
 *
 *  抛出去之后，GlobalExceptionHandler 会接住它，并转成统一格式的响应：
 *      HTTP 409 + {"code":20001, "message":"用户名已被占用", ...}
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：异常在链路里是怎么走的】
 *
 *      Service 里发现用户名重复
 *         ↓
 *      throw new BizException(ErrorCode.USERNAME_EXISTS)
 *         ↓  方法在这里【中断】，后面一行都不执行，也没 return
 *         ↓  异常对象开始沿着调用栈往上"冒泡"
 *      Controller  ← 【不会接住】，让它继续往上
 *         ↓
 *      DispatcherServlet 拦住，开始查 @ExceptionHandler
 *         ↓  在 GlobalExceptionHandler 里找到 @ExceptionHandler(BizException.class)
 *      GlobalExceptionHandler.handleBiz(e)
 *         ↓  从 e 里取出 ErrorCode → 用它决定 HTTP 状态码
 *         ↓  从 e 里取出 data → 塞进 ApiResponse 的 data
 *      ResponseEntity(status=409, body=ApiResponse)
 *         ↓  Jackson 序列化
 *      HTTP 响应：{"code":20001, "message":"用户名已被占用", "data":null, "traceId":"..."}
 *
 *  ★ 关键认识：Service 只管"发现问题并抛出"，
 *    完全不需要知道"HTTP 状态码该用多少"、"JSON 长什么样"。
 *    这些由 GlobalExceptionHandler 统一决定。
 *    这就是"业务逻辑和对外表现解耦"。
 *
 *
 *  【★ 和 ApiResponse 的分工（容易混）】
 *
 *      ApiResponse  → 用于【正常返回】，Controller 手动构造
 *      BizException → 用于【异常情况】，Service 抛出，处理器自动转成 ApiResponse
 *
 *  两者最终都会变成同一个 JSON 结构，只是产生的路径不同。
 *
 *
 *  【为什么不用 if/else 层层返回错误？】
 *  看一个对比。假设注册流程有 4 步校验：
 *
 *  ── 写法 A：返回错误码（不用异常）──
 *      @Transactional
 *      public Result register(req) {
 *          if (exists(req.username)) return Result.fail(USERNAME_EXISTS);
 *          if (badPassword(req.password)) return Result.fail(PARAM_INVALID);
 *          if (!captchaOk(req)) return Result.fail(PARAM_INVALID);
 *          User u = save(req);
 *          return Result.ok(u);
 *      }
 *   问题：每个调用方都必须检查返回值。中间任何一层忘了检查，
 *        错误就被悄悄吞掉，继续往下执行，最后写进脏数据。
 *
 *  ── 写法 B：抛异常（我们用这个）──
 *      @Transactional
 *      public UserVO register(req) {
 *          if (exists(req.username)) throw new BizException(USERNAME_EXISTS);
 *          if (badPassword(req.password)) throw new BizException(PARAM_INVALID);
 *          if (!captchaOk(req)) throw new BizException(PARAM_INVALID);
 *          return UserVO.of(save(req));      // 能走到这行，说明前面全过了
 *      }
 *   好处：
 *      1. "正常流程"和"异常流程"彻底分开。读代码时只看正常流程就很清楚。
 *      2. 抛出去之后一定会被兜底处理，不可能"漏检"。
 *      3. ★ 事务自动回滚。Spring 的事务默认遇到 RuntimeException 就回滚，
 *         这是异常机制的隐藏福利（下面第 2 点详细讲）。
 *
 *
 *  【1. 为什么继承 RuntimeException，而不是 Exception？】
 *
 *   Java 有两类异常：
 *      Exception（受检异常）      → 方法要么 try-catch，要么在签名上写 throws，
 *                                   否则编译不过。
 *      RuntimeException（非受检） → 不需要声明，想抛就抛。
 *
 *   如果我们继承 Exception，那每个用到它的方法都得写：
 *       public UserVO register(...) throws BizException
 *   而且中间每一层（Controller → Service → Repository）都得声明 throws，
 *   整个项目会被 throws 声明污染得没法看。
 *
 *   用 RuntimeException 就不需要任何声明，异常会自己往上冒，
 *   直到被 GlobalExceptionHandler 接住。
 *
 *
 *  【2. 为什么一定要是 RuntimeException 才能触发事务回滚？★重点】
 *
 *   Spring 的 @Transactional 默认规则是：
 *      遇到 RuntimeException 或 Error  → 回滚
 *      遇到普通 Exception              → 不回滚（提交！）
 *
 *   这个设计经常把新人坑惨：业务校验失败抛了个受检异常，
 *   事务不但没回滚，还把半截数据提交了。
 *   我们全部用 RuntimeException，就规避了这个坑。
 *
 *   顺便提一句：如果想精确控制，可以在 @Transactional(rollbackFor = XxxException.class) 里指定，
 *   但更简单的做法就是统一用 RuntimeException。
 */
public class BizException extends RuntimeException {

    /** 这个异常对应哪一条错误码（决定了返回给前端的 code、HTTP 状态码、默认提示语） */
    private final ErrorCode errorCode;

    /**
     * 附加数据。会被放进 ApiResponse 的 data 字段返回给前端。
     *
     * 为什么需要它？举两个真实场景：
     *
     *   场景一：发布帖子时审核不通过
     *     异常里带上 {"hits":[{type:"PHONE", start:132, end:143, maskedSnippet:"138****5678"}]}
     *     前端拿到后弹出对话框，用户点一条就把光标定位到对应位置。
     *
     *   场景二：参数非法但想说明具体原因
     *     比如"内容与已有版本相同，未创建新版本"。
     *
     * ⚠️ 为什么加了 transient 关键字？
     *   异常对象有可能被序列化（比如某些日志框架、链路追踪工具会把异常整个写出去）。
     *   加了 transient 表示"序列化时跳过这个字段" —— 因为业务数据不该跟着异常到处跑，
     *   而且如果 data 里含敏感信息，跟着序列化出去就是泄露。
     */
    private final transient Object data;

    /**
     * 【最常用】只有错误码，没有附加数据。
     * 提示语用 ErrorCode 里定义的默认文案。
     */
    public BizException(ErrorCode ec) {
        this(ec, null, ec.getDefaultMessage());
    }

    /**
     * 【带附加数据】比如审核不通过时要返回命中项。
     * this(...) 是调用下面那个构造器，避免重复写赋值代码。
     */
    public BizException(ErrorCode ec, Object data) {
        this(ec, data, ec.getDefaultMessage());
    }

    /**
     * 【全部参数】错误码 + 附加数据 + 自定义提示语。
     * 最终真正干活的构造器 —— 上面两个都委托给它。
     *
     * super(message) 是把提示语传给父类 RuntimeException，
     * 这样 e.getMessage() 拿到的就是这句话，日志里也能看到。
     */
    public BizException(ErrorCode ec, Object data, String message) {
        super(message);
        this.errorCode = ec;
        this.data = data;
    }

    /** GlobalExceptionHandler 会调这个方法来知道"该返回什么码" */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** GlobalExceptionHandler 会调这个方法来取附加数据，塞进响应的 data 字段 */
    public Object getData() {
        return data;
    }
}
