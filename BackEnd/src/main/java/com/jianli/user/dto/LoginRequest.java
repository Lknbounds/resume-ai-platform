package com.jianli.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  登录接口的请求体。只有两个字段：用户名和密码。
 *
 *      POST /api/auth/login
 *      {"username":"alice","password":"Passw0rd1"}
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向】
 *
 *      前端登录表单
 *         └─ http.post('/auth/login', { username, password })
 *              ↓
 *      请求体 JSON
 *              ↓
 *      AuthController.login(@RequestBody @Valid LoginRequest req)
 *              ├─ Jackson 反序列化
 *              └─ @Valid 校验（两个字段都非空）
 *              ↓
 *      AuthService.login(req)
 *         ├─ userRepository.findByUsername(req.username())
 *         ├─ passwordEncoder.matches(req.password(), user.getPasswordHash())
 *         ├─ 成功 → 签发 token → 存 Redis 白名单
 *         └─ 失败 → throw BizException(BAD_CREDENTIALS)
 *              ↓
 *      返回 TokenResponse { accessToken, refreshToken, expiresIn, user }
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么这里只是 @NotBlank，不像注册那样限制长度和格式？】
 *
 *  这是刻意的，有两个重要原因：
 *
 *  ① 【防用户名枚举】
 *     如果在登录时校验"用户名必须是 4-32 位、只能字母数字"，
 *     攻击者就能通过"提示信息不同"来推断：
 *          返回"用户名格式不对" → 说明这个账号不存在（或者格式特殊）
 *          返回"用户名或密码错误" → 说明账号存在
 *     从而批量筛选出有效用户名。
 *
 *     登录接口对输入【越宽松越好】—— 只要求非空，
 *     剩下的判断全部统一成"用户名或密码错误"。
 *
 *  ② 【向后兼容】
 *     万一以后用户名规则放宽了（比如允许中文），
 *     老的登录接口不用改也能继续用。
 *
 *  ★ 一句话：注册接口严格校验（把好数据挡在门外），
 *    登录接口宽松校验（不给攻击者任何额外信息）。
 *
 *
 *  【★ record 的一个隐患：toString 会打印密码】
 *
 *      LoginRequest[username=alice, password=Passw0rd1]
 *                                          ↑ 明文
 *
 *  所以：绝不要 log.info("login req={}", req)。
 *  如果要打，自己重写 toString：
 *      @Override
 *      public String toString() {
 *          return "LoginRequest[username=" + username + ", password=***]";
 *      }
 *
 *  ★ record 允许重写自动生成的方法（toString / equals / hashCode），
 *    重写之后就用自己的。
 */
public record LoginRequest(

        /**
         * 用户名。
         *
         * ★ 只校验非空，理由见上面【为什么只是 @NotBlank】。
         *   但 message 还是要给的，不能默认成英文。
         */
        @NotBlank(message = "请输入用户名")
        String username,

        /**
         * 密码（明文）。
         *
         * ★ 为什么这里连 8 位长度都不校验？
         *   因为登录时密码对不对由 BCrypt 比对决定，长度校验没有意义 ——
         *   用户输了个 3 位的密码，BCrypt 比对会失败，返回"用户名或密码错误"，
         *   结果是一样的。而且不做长度校验，也不给攻击者额外信息。
         *
         * ★ 明文密码在内存里的生命周期：
         *   请求进来 → String 对象 → 传给 AuthService
         *   → passwordEncoder.matches() 比对
         *   → 方法结束，String 成为垃圾，被 GC 回收
         *
         *   注意：String 是不可变的，回收前内容会一直在内存里。
         *   高安全场景（比如银行）会用 char[] 并在使用后清零，
         *   但普通 Web 应用不需要这个级别的防护。
         */
        @NotBlank(message = "请输入密码")
        String password
) {}
