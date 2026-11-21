package com.jianli.common;

import org.slf4j.MDC;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  统一响应体。所有接口返回给前端的数据，外面都套着这一层壳。
 *
 *  前端最终收到的 JSON 长这样：
 *  {
 *    "code": 0,
 *    "message": "ok",
 *    "data": "hello",
 *    "traceId": "d70beb5bd41d47c1",
 *    "timestamp": 1795006553458
 *  }
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：这个东西在链路里的哪一环】
 *
 *      MySQL 结果集
 *         ↓  JPA 映射
 *      Entity（比如 Resume）
 *         ↓  Service 里手动转（ResumeDetailVO.of(entity)）
 *      VO（比如 ResumeDetailVO）        ← 这一层是"能给前端看的形状"
 *         ↓  Controller 里包一层
 *      ApiResponse<ResumeDetailVO>     ← ★ 本类，最外层壳
 *         ↓  Jackson 自动序列化（Spring 干的，不用你写代码）
 *      JSON 字符串
 *         ↓  HTTP 响应体
 *      浏览器
 *
 *  ★ 为什么要多这一层壳？
 *  见下面【为什么要给响应套一层壳】。
 *
 *
 *  【注意：本类只是"数据容器"，它不做任何逻辑】
 *  除了几个 static 工厂方法（ok / fail），它没有别的行为。
 *  这是刻意的 —— 数据类和逻辑类要分开，否则以后想复用这个壳就带上一堆依赖。
 *
 *
 *  【为什么要给响应套一层壳？直接返回数据不行吗？】
 *  如果每个接口各返回各的：
 *       /api/user/1   → {"id":1,"name":"张三"}
 *      /api/user/2   → {"id":2,"name":"李四"}
 *      出错时        → {"timestamp":...,"status":500,"error":"Internal Server Error"}  ← Spring 默认格式
 *
 *  前端就得为每个接口写不同的解析逻辑，出错时还要判断"这个响应到底是数据还是错误"。
 *
 *  统一之后，前端只在 http.js 里写一次拦截器：
 *      code === 0  → 取出 data 往下传
 *      code !== 0  → 弹错误提示 / 跳登录页
 *  所有页面都不用再关心这些。
 *
 *
 *  【record 是什么？为什么不写 class？】
 *  record 是 Java 14 引入的"不可变数据载体"，编译后会帮你自动生成：
 *      - 7 个私有 final 字段 + 全参构造器
 *      - 每个字段的 getter（注意：叫 code() 不是 getCode()）
 *      - equals() / hashCode() / toString()
 *
 *  用 record 而不是 class 的理由：
 *      1. 响应体一旦创建就不该被修改 —— record 天然不可变，从语言层面杜绝了
 *         "某处代码偷偷改了返回结果"这种 bug。
 *      2. 少写几十行样板代码。
 *      3. Jackson（Spring 用来转 JSON 的库）2.12+ 原生支持 record，不需要额外配置。
 *
 *  ⚠️ 注意：record 里的 <T> 是"泛型" —— T 表示"这里放的 data 是什么类型，
 *     由使用方决定"。可以是 String、可以是 UserVO、可以是 List<PostVO>，
 *     甚至可以是 null。这样一套壳就能套所有接口。
 *
 *
 *  【traceId 为什么要出现在响应里？】
 *  用户遇到错误时，只会跟你说"我点了发布，它报错了"。
 *  如果响应里带了 traceId，用户可以把这个 id 发给你，
 *  你在日志里 grep 这个 id，就能看到这一次请求经过的每一行日志 —— 完整还原现场。
 *
 *  MDC（Mapped Diagnostic Context）是 SLF4J/logback 提供的"线程本地变量"，
 *  TraceIdFilter 会往里面塞 traceId，日志格式里写 [%X{traceId}] 自动打出来。
 *  所以这里 MDC.get("traceId") 拿到的，就是当前请求线程上那个 id。
 */
public record ApiResponse<T>(

        /** 业务码：0 = 成功，其他 = 失败（具体含义见 ErrorCode） */
        int code,

        /** 提示语，用户可以直接看到 */
        String message,

        /** 真正的业务数据。失败时可能是 null，也可能带"为什么失败"的详情 */
        T data,

        /** 请求追踪号，排查问题用 */
        String traceId,

        /** 服务器时间戳（毫秒），前端可以用来算耗时、处理缓存 */
        long timestamp
) {

    /**
     * 【成功】的快捷构造方法。
     *
     * 为什么用 static 工厂方法而不是每次都 new ApiResponse<>(0, "ok", data, ...)？
     *   1. 调用处更短：ApiResponse.ok(user) 比 new ApiResponse<>(0,"ok",user,MDC.get(...),System.currentTimeMillis()) 好读太多
     *   2. 如果以后要在成功响应里统一加字段（比如 costs），只改这里，不用改 100 个调用点
     *   3. 语义清晰：看到 ok() 就知道是成功
     *
     * <T> 写在 static 前面是 Java 语法要求：
     * static 方法不属于某个具体实例，所以没法继承类上的 T，必须自己声明一个。
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data, MDC.get("traceId"), System.currentTimeMillis());
    }

    /**
     * 【成功但没有数据】。比如"删除成功"、"退出登录"，只关心成没成功。
     * 内部就是调用 ok(null)。
     */
    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    /**
     * 【失败】的快捷构造方法。提示语用 ErrorCode 里定义的默认文案。
     *
     * @param ec   从 ErrorCode 里挑一个（比如 ErrorCode.POST_PRIVACY_REJECTED）
     * @param data 附加信息。比如审核不通过时，这里放"命中了哪些信息、在什么位置"，
     *             前端拿到后就能高亮定位。不需要附加信息时传 null。
     */
    public static <T> ApiResponse<T> fail(ErrorCode ec, T data) {
        return new ApiResponse<>(ec.getCode(), ec.getDefaultMessage(), data,
                MDC.get("traceId"), System.currentTimeMillis());
    }

    /**
     * 【失败 + 自定义提示语】。
     * 比如同样是 PARAM_INVALID，有时想说得更具体："内容与已有版本相同，未创建新版本"。
     */
    public static <T> ApiResponse<T> fail(ErrorCode ec, T data, String message) {
        return new ApiResponse<>(ec.getCode(), message, data,
                MDC.get("traceId"), System.currentTimeMillis());
    }
}
