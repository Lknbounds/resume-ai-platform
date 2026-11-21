package com.jianli.user.controller;

import com.jianli.common.ApiResponse;
import com.jianli.common.CurrentUserId;
import com.jianli.user.dto.ChangePasswordRequest;
import com.jianli.user.dto.UpdateProfileRequest;
import com.jianli.user.dto.UserVO;
import com.jianli.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  用户资料接口。三个：
 *      GET /api/users/me            查资料
 *      PUT /api/users/me            改资料
 *      PUT /api/users/me/password   改密码
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 三条路径的共同点：都以 /me 结尾/开头】
 *
 *  ★ 为什么不设计成 /api/users/{id}？
 *
 *      ❌ PUT /api/users/7
 *          用户传 7，服务端必须校验"7 是不是你" —— 漏了就成越权漏洞。
 *
 *      ✅ PUT /api/users/me
 *          没有 id 可传，服务端从 token 自己推导。
 *          ★ 这是"用 API 设计消除安全隐患"：
 *            与其写代码防止错误用法，不如让错误用法表达不出来。
 *
 *  ★ 这就是 REST 里"资源标识由服务端决定"的思路。
 *    真正需要看别人资料时（社区个人主页）才用 /{id}，
 *    而且那时返回的字段要更少。
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "用户")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【查当前登录用户资料】GET /api/users/me
     * ═══════════════════════════════════════════════════════════════
     *
     * 响应：
     *      {"code":0,"data":{"id":1,"username":"alice","nickname":"Alice",...}}
     *
     * ★ 参数 @CurrentUserId Long userId：
     *   由 CurrentUserIdResolver 从 SecurityContext 取出，
     *   客户端无法伪造（没有传 id 的入口）。
     */
    @GetMapping("/me")
    @Operation(summary = "获取当前登录用户资料")
    public ApiResponse<UserVO> me(@CurrentUserId Long userId) {
        return ApiResponse.ok(userService.getProfile(userId));
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【修改资料】PUT /api/users/me
     * ═══════════════════════════════════════════════════════════════
     *
     * 请求：
     *      {"nickname":"Alice","targetPosition":"Java 后端开发","yearsOfExperience":3}
     *
     * 响应：更新后的完整资料（前端可直接用来刷新界面）
     *
     * ★ 为什么用 PUT 而不是 PATCH？
     *   PUT 语义是"整体替换"，PATCH 是"局部更新"。
     *   本接口要求 nickname 必填，相当于整体替换 —— 用 PUT 更准确。
     *   ★ 如果想支持局部更新（只传要改的字段），改成 PATCH 并把
     *     所有字段都设为可选即可，Service 里逐字段判 null。
     *
     * 错误：
     *      400 + 10000  昵称为空 / 年限超范围
     *      404 + 20005  用户不存在
     */
    @PutMapping("/me")
    @Operation(summary = "修改个人资料")
    public ApiResponse<UserVO> updateMe(@CurrentUserId Long userId,
                                        @RequestBody @Valid UpdateProfileRequest req) {
        return ApiResponse.ok(userService.updateProfile(userId, req));
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【修改密码】PUT /api/users/me/password
     * ═══════════════════════════════════════════════════════════════
     *
     * 请求：
     *      {"oldPassword":"Passw0rd1","newPassword":"NewPassw0rd2"}
     *
     * 响应：
     *      {"code":0,"message":"ok","data":null}
     *
     * 错误：
     *      401 + 20002  原密码错误
     *      400 + 10000  新密码不符合规则
     *
     * ★★ 成功后发生什么（前端必须配合）：
     *
     *   服务端：清空该用户所有 refresh token → 所有设备下次刷新会失败
     *
     *   前端：调用成功之后应该【立刻清空本地 token 并跳登录页】。
     *        因为 access token 虽然还能用 2 小时，但 refresh 已经失效了，
     *        用户再操作一会儿就会突然被踢 —— 不如主动引导重新登录。
     *
     *    ★ 见前端 ProfileView.vue 里的 changePassword 方法：
     *      await userApi.changePassword(pwd)
     *      user.clear()
     *      window.location.href = '/login'
     *
     * ★ 为什么返回 ApiResponse<Void> 而不是 ApiResponse<UserVO>？
     *   因为改密码不产生新的用户数据可展示，前端只需要知道成功与否。
     */
    @PutMapping("/me/password")
    @Operation(summary = "修改密码", description = "成功后所有设备会被强制下线")
    public ApiResponse<Void> changePassword(@CurrentUserId Long userId,
                                            @RequestBody @Valid ChangePasswordRequest req) {
        userService.changePassword(userId, req);
        return ApiResponse.ok();
    }
}
