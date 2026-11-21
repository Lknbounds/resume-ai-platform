package com.jianli.jd.controller;

import com.jianli.common.ApiResponse;
import com.jianli.common.CurrentUserId;
import com.jianli.jd.dto.JdVO;
import com.jianli.jd.dto.SaveJdRequest;
import com.jianli.jd.service.JdService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  JD 相关接口。5 个，全部委托给 JdService。
 * ═══════════════════════════════════════════════════════════════════════
 */
@RestController
@RequestMapping("/api/jds")
@Tag(name = "岗位JD")
@RequiredArgsConstructor
public class JdController {

    private final JdService jdService;

    /**
     * 新建 JD。保存时同步解析（规则版几毫秒）。
     *
     * 响应 201 + JdVO（含解析出的技能列表）
     */
    @PostMapping
    @Operation(summary = "保存 JD", description = "保存后立即用规则解析出技能要求")
    public ResponseEntity<ApiResponse<JdVO>> create(@CurrentUserId Long userId,
                                                     @RequestBody @Valid SaveJdRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(jdService.create(userId, req)));
    }

    /**
     * 我的 JD 列表。
     *
     * ★ 不分页 —— 用户保存的 JD 通常 < 20 个，
     *   一次全查比"分页 + 前端再请求"简单。Service 里限制最多 100 条。
     */
    @GetMapping
    @Operation(summary = "我的 JD 列表", description = "可用 keyword 搜索岗位名或公司名")
    public ApiResponse<List<JdVO>> list(@CurrentUserId Long userId,
                                        @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(jdService.list(userId, keyword));
    }

    @GetMapping("/{id}")
    @Operation(summary = "JD 详情")
    public ApiResponse<JdVO> detail(@CurrentUserId Long userId, @PathVariable Long id) {
        return ApiResponse.ok(jdService.detail(userId, id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新 JD", description = "内容变更会重新解析")
    public ApiResponse<JdVO> update(@CurrentUserId Long userId,
                                     @PathVariable Long id,
                                     @RequestBody @Valid SaveJdRequest req) {
        return ApiResponse.ok(jdService.update(userId, id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除 JD", description = "级联删除相关分析报告")
    public ApiResponse<Void> delete(@CurrentUserId Long userId, @PathVariable Long id) {
        jdService.delete(userId, id);
        return ApiResponse.ok();
    }
}
