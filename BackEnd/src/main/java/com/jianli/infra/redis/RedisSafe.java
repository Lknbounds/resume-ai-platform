package com.jianli.infra.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  Redis 的安全包装。★ 把"Redis 挂了怎么办"收口到一个地方。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么需要这个类？】
 *
 *  Redis 在本项目里是【加速层】，不是唯一真相源。
 *  如果每个调用点都写 try-catch：
 *      1. 重复几十次
 *      2. 总有人忘记写 → Redis 挂了整个功能报 500
 *      3. 降级策略不统一（有人返回 null，有人返回空列表）
 *
 *  ★ 用这个包装类：
 *      - 所有 Redis 操作都走这里
 *      - 异常统一捕获、记录 WARN、返回降级值
 *      - 业务代码不需要知道 Redis 会挂
 *
 *  【★ 为什么用 @Slf4j 的 WARN 而不是 ERROR？】
 *    Redis 挂了是"能力降级"，不是"系统故障"。
 *    业务照常运行，只是慢一点。
 *    ★ ERROR 应该留给"真的出问题了"的场景，
 *      否则日志里全是 Redis 警告，真正的故障被淹没。
 *
 *  【★ 为什么不重试？】
 *    Redis 操作是"可选加速"。
 *    重试只会增加延迟，而失败后降级的成本很低（回源数据库）。
 *    ★ 宁快速失败并降级，也不要让用户等 3 秒重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSafe {

    private final StringRedisTemplate redis;

    /**
     * 读字符串。失败返回 null。
     */
    public String get(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis GET 失败, key={}: {}", key, e.getMessage());
            return null;
        }
    }

    /** 读字符串，失败或不存在时返回默认值 */
    public String get(String key, String defaultValue) {
        String v = get(key);
        return v == null ? defaultValue : v;
    }

    /**
     * 读对象（JSON）。失败或反序列化失败都返回 null。
     *
     * @param type 目标类型（record 或 POJO）
     */
    public <T> T getObject(String key, Class<T> type, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        String json = get(key);
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("Redis 缓存反序列化失败, key={}", key);
            // ★ 反序列化失败说明缓存数据坏了，直接删掉，下次会重新写入
            delete(key);
            return null;
        }
    }

    /**
     * 写字符串。失败返回 false（调用方通常不需要关心）。
     */
    public boolean set(String key, String value, Duration ttl) {
        try {
            if (ttl == null) {
                redis.opsForValue().set(key, value);
            } else {
                redis.opsForValue().set(key, value, ttl);
            }
            return true;
        } catch (Exception e) {
            log.warn("Redis SET 失败, key={}: {}", key, e.getMessage());
            return false;
        }
    }

    /** 删除。失败忽略 */
    public void delete(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("Redis DEL 失败, key={}: {}", key, e.getMessage());
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【通用降级包装】
     * ═══════════════════════════════════════════════════════════════
     *
     * 用法：
     *      return redisSafe.execute("读热度榜",
     *              () -> redis.opsForZSet().reverseRange(KEY, 0, 9),
     *              () -> List.of()                     // Redis 挂了返回空
     *      );
     *
     * ★ 这个模式叫"围绕执行"（execute around）：
     *   把 try-catch + 日志 + 降级 三件事收口成一次调用。
     */
    public <T> T execute(String action, Supplier<T> redisCall, Supplier<T> fallback) {
        try {
            return redisCall.get();
        } catch (Exception e) {
            log.warn("Redis 操作失败（{}）: {}，已降级", action, e.getMessage());
            return fallback.get();
        }
    }

    /**
     * 检查 Redis 是否可用。
     *
     * ★ 用在哪：健康检查、启动日志。
     *   不要在每个业务调用前调它（那是额外的往返）。
     *   直接调业务方法 + 捕获异常更高效。
     */
    public boolean isAvailable() {
        try {
            String pong = redis.getConnectionFactory().getConnection().ping();
            return "PONG".equals(pong);
        } catch (Exception e) {
            return false;
        }
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 这个类不封装什么？】
     *
     *  1. ZSET / HASH 等复杂操作
     *     ★ 理由：封装它们需要为每种操作写一个方法，
     *       这个类会膨胀成 200 行。
     *       简单操作（get/set/delete）封装，
     *       复杂操作直接用 execute(...) 包一层。
     *
     *  2. 业务语义
     *     ★ 比如"缓存报告"、"更新热度榜"不属于这里。
     *       这个类只负责"Redis 调用不会炸"，不负责业务逻辑。
     * ═══════════════════════════════════════════════════════════════
     */
}
