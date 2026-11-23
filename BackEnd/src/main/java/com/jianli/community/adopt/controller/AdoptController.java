package com.jianli.community.adopt.controller;

import com.jianli.common.ApiResponse;
import com.jianli.common.CurrentUserId;
import com.jianli.community.adopt.dto.AdoptRequest;
import com.jianli.community.adopt.dto.AdoptResultVO;
import com.jianli.community.adopt.service.AdoptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/comments")
@Tag(name = "社区-采纳")
@RequiredArgsConstructor
public class AdoptController {

    private final AdoptService adoptService;

    @PostMapping("/{id}/adopt")
    @Operation(summary = "采纳建议", description = "仅帖子作者可采纳；可只标记，也可生成 ADOPT_COMMENT 简历版本")
    public ApiResponse<AdoptResultVO> adopt(
            @CurrentUserId Long userId,
            @PathVariable Long id,
            @RequestBody(required = false) @Valid AdoptRequest req) {
        return ApiResponse.ok(adoptService.adopt(userId, id, req));
    }
}
