package com.jianli.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  修改个人资料的请求体。
 *
 *      PUT /api/users/me
 *      {"nickname":"Alice","targetPosition":"Java 后端开发","yearsOfExperience":3}
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向】
 *
 *      前端个人资料页表单
 *         ↓  http.put('/users/me', form)
 *      UserController.updateMe(@CurrentUserId Long userId,
 *                              @RequestBody @Valid UpdateProfileRequest req)
 *         ├─ Jackson 反序列化（JSON → 本对象）
 *         └─ @Valid 校验
 *         ↓
 *      UserService.updateProfile(userId, req)
 *         ├─ 查用户 → 找不到抛 USER_NOT_FOUND
 *         ├─ set 三个字段
 *         └─ 返回 UserVO（不用显式 save，脏检查会自动 UPDATE）
 *         ↓
 *      前端拿到新的 UserVO，刷新页面上的昵称
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么字段用"包装类型 + 可为 null"？】
 *
 *  看 yearsOfExperience：Integer 而不是 int。
 *
 *  如果前端某个版本没传这个字段（比如老版本前端），
 *  包装类型会得到 null，Service 里可以判断"没传就不改"：
 *      if (req.yearsOfExperience() != null) user.setYearsOfExperience(...);
 *
 *  如果是 int，没传就是 0，会把用户原本的 3 年经验改成 0 ——
 *  这是很隐蔽的数据损坏。**用包装类型表达"可以没有"。**
 */
public record UpdateProfileRequest(

        /**
         * 昵称。必填。
         *
         * ★ 为什么这里 @NotBlank 而不是允许不改？
         *   因为前端总是会把整份表单提交上来（包含当前昵称），
         *   空着说明用户删掉了 —— 这是不合法的，昵称不能为空。
         *
         *   如果要支持"局部更新"（只改一个字段），应该用 PATCH 语义，
         *   那样所有字段都允许 null。本项目用 PUT（整体替换），
         *   所以必填字段必须传。
         */
        @NotBlank(message = "昵称不能为空")
        @Size(max = 16, message = "昵称最长 16 个字符")
        String nickname,

        /**
         * 目标岗位。选填 —— 用户可以清空。
         *
         * ★ 为什么允许 null 和空字符串？
         *   用户可能还没想好投什么岗位，这时候应该允许留空。
         *   ★ 但要注意区分 null 和 ""：
         *     null → 没传这个字段（保持原值？还是清空？）
         *     ""   → 明确要清空
         *   本项目简化处理：两者都当作"清空"。
         */
        @Size(max = 64, message = "目标岗位最长 64 个字符")
        String targetPosition,

        /**
         * 经验年限。0-50。
         *
         * ★ 为什么是 Integer（包装类型）而不是 int？
         *   见类说明 —— 为了区分"没传"和"传了 0"。
         *
         * ★ 为什么上限是 50？
         *   正常人的职业生涯不会超过 40 年。50 是个宽松上限，
         *   防止用户手滑输入 9999 导致显示异常。
         *
         * ★ @Min/@Max 只对非 null 生效 —— 传 null 时不会校验。
         *   所以 Service 里要自己判断 null。
         */
        @Min(value = 0, message = "经验年限不能为负数")
        @Max(value = 50, message = "经验年限不能超过 50 年")
        Integer yearsOfExperience
) {}
