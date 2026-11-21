package com.jianli.privacy.controller;

import com.jianli.common.ApiResponse;
import com.jianli.common.CurrentUserId;
import com.jianli.privacy.PrivacyDetector;
import com.jianli.privacy.PrivacyHit;
import com.jianli.privacy.service.PrivacyReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  发布前的个人信息预检。
 *
 *      POST /api/privacy/precheck
 *      {"resumeContent": "...", "title": "...", "content": "..."}
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 预检和正式审核的区别】
 *
 *  预检：
 *      - 只读，不落库
 *      - HTTP 200 + {passed: false, hits: [...]}
 *      - 用于前端"即时检查"，用户就地修改
 *
 *  正式发布：
 *      - 落库（PASSED 或 REJECTED 都记）
 *      - 不通过返回 HTTP 422 + code 43002
 *
 *  ★ 为什么 HTTP 码不一样？
 *    预检是"查询"—— 查询成功就是 200，结果说"不通过"也是成功返回。
 *    发布是"创建"—— 创建被拒绝才是失败，用 4xx。
 *
 *  ★ 这是"语义正确"的一个好例子：
 *    同样的"内容不合规"，在两个不同动作里的 HTTP 码应该不同。
 */
@RestController
@RequestMapping("/api/privacy")
@Tag(name = "个人信息审核")
@RequiredArgsConstructor
public class PrivacyController {

    private final PrivacyReviewService privacyReviewService;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【预检】POST /api/privacy/precheck
     * ═══════════════════════════════════════════════════════════════
     *
     * 响应（通过）：
     *      {"code":0,"data":{"passed":true,"hits":[],"ruleVersion":"v1"}}
     *
     * 响应（命中）：
     *      {"code":0,"data":{"passed":false,"ruleVersion":"v1",
     *        "hits":[{"type":"PHONE","start":132,"end":143,
     *                 "maskedSnippet":"138****5678","advice":"..."}]}}
     *
     * ★ 注意 start/end 是【拼接口径】的偏移 ——
     *   前端要按"简历正文"定位的话，需要减去 title/content 的长度。
     *   ★ 更好的做法是分字段检测（各返回各的偏移）。
     *     本项目简化处理：主要用来提示"有敏感信息"，
     *     精确定位靠用户自己扫一眼（内容通常不长）。
     */
    @PostMapping("/precheck")
    @Operation(summary = "发布前预检", description = "只检测不落库，用于前端即时反馈")
    public ApiResponse<Map<String, Object>> precheck(
            @CurrentUserId Long userId,
            @RequestBody PrecheckRequest req) {

        String toCheck = String.join("\n",
                req.title() == null ? "" : req.title(),
                req.content() == null ? "" : req.content(),
                req.resumeContent() == null ? "" : req.resumeContent());

        List<PrivacyHit> hits = privacyReviewService.precheck(toCheck);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("passed", hits.isEmpty());
        data.put("ruleVersion", PrivacyDetector.RULE_VERSION);
        data.put("hits", hits);

        return ApiResponse.ok(data);
    }

    public record PrecheckRequest(String title, String content, String resumeContent) {}
}
