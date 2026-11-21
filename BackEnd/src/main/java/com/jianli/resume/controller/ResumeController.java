package com.jianli.resume.controller;

import com.jianli.common.ApiResponse;
import com.jianli.common.CurrentUserId;
import com.jianli.common.PageResult;
import com.jianli.resume.dto.AddVersionRequest;
import com.jianli.resume.dto.CreateResumeRequest;
import com.jianli.resume.dto.RenameResumeRequest;
import com.jianli.resume.dto.ResumeDetailVO;
import com.jianli.resume.dto.ResumeSummaryVO;
import com.jianli.resume.dto.VersionDetailVO;
import com.jianli.resume.dto.VersionSummaryVO;
import com.jianli.resume.service.ResumeService;
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
 *  简历相关接口。8 个：
 *      POST   /api/resumes                        新建简历
 *      GET    /api/resumes                        我的简历列表
 *      GET    /api/resumes/{id}                   简历详情
 *      PUT    /api/resumes/{id}                   改标题
 *      DELETE /api/resumes/{id}                   删除
 *      POST   /api/resumes/{id}/versions          新增版本
 *      GET    /api/resumes/{id}/versions          版本列表
 *      GET    /api/resumes/versions/{versionId}   版本详情（全文）
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 路由顺序：为什么 /versions/{versionId} 写在 {id} 后面？】
 *
 *  /api/resumes/{id}                 → 一段路径变量
 *  /api/resumes/versions/{versionId} → 两段，第一段是字面量 "versions"
 *
 *  ★ 这两个【不会冲突】，因为段数不同：
 *      /api/resumes/10              → 匹配第 1 条
 *      /api/resumes/versions/35     → 匹配第 3 条
 *
 *  ★ 但 Spring 的匹配优先级是"更具体的优先"，字面量高于变量。
 *    所以即使把 /versions/... 写在前面也没问题。
 *    这里按"从粗到细"的顺序写，读起来更自然。
 *
 *
 *  【★ 所有方法都带 @CurrentUserId】
 *
 *  因为所有操作都是"对当前登录用户的简历"。
 *  ★ 客户端永远不能指定 userId —— 它由 token 推导。
 *    这是"用 API 设计消除越权"的又一例。
 */
@RestController
@RequestMapping("/api/resumes")
@Tag(name = "简历")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【新建简历】POST /api/resumes
     * ═══════════════════════════════════════════════════════════════
     *
     * 请求：
     *      {"title":"Java 后端简历","content":"（不少于 50 字的简历正文）"}
     *
     * 响应（201 Created）：
     *      {"code":0,"data":{"id":10,"title":"...","currentVersionId":31,
     *                        "versionCount":1,"versions":[{"id":31,"versionNo":1,...}]}}
     *
     * 错误：
     *      400 + 10000  标题为空 / 正文为空
     *      400 + 40004  正文去掉空白后不足 50 字
     *
     * ★ 为什么返回 201？
     *   创建了资源用 201 Created 更规范（同注册接口）。
     *
     * ★ 为什么返回完整详情（含版本列表）而不是只返回 id？
     *   前端创建后要立刻跳详情页，直接给完整数据省一次请求。
     *   ★ 这里数据量很小（只有一个 v1，且不含正文），代价可接受。
     */
    @PostMapping
    @Operation(summary = "新建简历", description = "创建简历并生成 v1")
    public ResponseEntity<ApiResponse<ResumeDetailVO>> create(
            @CurrentUserId Long userId,
            @RequestBody @Valid CreateResumeRequest req) {

        ResumeDetailVO vo = resumeService.create(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(vo));
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【我的简历列表】GET /api/resumes?page=1&size=10
     * ═══════════════════════════════════════════════════════════════
     *
     * 响应：
     *      {"code":0,"data":{"records":[...],"total":3,"page":1,"size":10,"pages":1}}
     *
     * ★ @RequestParam(defaultValue = "1") 是什么？
     *   前端不传时用默认值。
     *   ★ 为什么参数是 int 而不是 String？
     *     Spring 会自动做类型转换（"1" → 1）。
     *     如果传了 "abc"，会抛 MethodArgumentTypeMismatchException
     *     → 被 GlobalExceptionHandler 兜底成 500。
     *
     *     ★ 更好的做法是让它变成 400（参数错误）。
     *       可以在 GlobalExceptionHandler 里加一个 handler 处理
     *       MethodArgumentTypeMismatchException → PARAM_INVALID。
     *       本项目暂时没加，因为前端不会传非法值。
     */
    @GetMapping
    @Operation(summary = "我的简历列表", description = "可用 keyword 搜索标题或目标岗位")
    public ApiResponse<PageResult<ResumeSummaryVO>> list(
            @CurrentUserId Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {

        return ApiResponse.ok(resumeService.list(userId, page, size, keyword));
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【简历详情】GET /api/resumes/{id}
     * ═══════════════════════════════════════════════════════════════
     *
     * 响应：简历信息 + 版本列表（摘要，不含正文）
     *
     * ★ 正文在哪取？
     *   前端拿到 currentVersionId 后，再调版本详情接口取那一份的全文。
     *   见下面的 getVersion。
     *
     * 错误：404 + 40001（不存在或不是你的）
     */
    @GetMapping("/{id}")
    @Operation(summary = "简历详情", description = "含版本列表，不含正文")
    public ApiResponse<ResumeDetailVO> detail(@CurrentUserId Long userId,
                                              @PathVariable Long id) {
        return ApiResponse.ok(resumeService.detail(userId, id));
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【改简历信息】PUT /api/resumes/{id}
     * ═══════════════════════════════════════════════════════════════
     *
     * 请求：{"title":"新标题","targetPosition":"Java 后端开发工程师"}
     *
     * ★ 注意：这个接口【不能改正文】。
     *   改正文必须走"新增版本"（POST /{id}/versions）。
     *   见 RenameResumeRequest 的说明。
     *
     * ★ targetPosition 传 null 或空串表示清除岗位。
     */
    @PutMapping("/{id}")
    @Operation(summary = "修改简历信息", description = "标题 + 目标岗位，不能改正文")
    public ApiResponse<ResumeDetailVO> updateInfo(@CurrentUserId Long userId,
                                                   @PathVariable Long id,
                                                   @RequestBody @Valid RenameResumeRequest req) {
        return ApiResponse.ok(
                resumeService.updateInfo(userId, id, req.title(), req.targetPosition()));
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【删除简历】DELETE /api/resumes/{id}
     * ═══════════════════════════════════════════════════════════════
     *
     * 响应：{"code":0,"message":"ok","data":null}
     *
     * ★★ 删除会级联带走什么？
     *    resume_version  → 数据库外键 ON DELETE CASCADE
     *    analysis_report → 同上
     *    post            → report_id 置 NULL，但帖子内容靠快照保留
     *
     * ★ 前端应该弹二次确认（ElMessageBox.confirm），
     *   因为这是不可恢复操作。
     *   见 ResumeListView 的 remove 方法。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除简历", description = "级联删除所有版本与分析报告")
    public ApiResponse<Void> delete(@CurrentUserId Long userId, @PathVariable Long id) {
        resumeService.delete(userId, id);
        return ApiResponse.ok();
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【新增版本】POST /api/resumes/{id}/versions
     * ═══════════════════════════════════════════════════════════════
     *
     * 请求：
     *      {"content":"（完整正文）","changeNote":"补充量化数据","source":"EDIT"}
     *
     * 响应（201）：新版本的完整详情（含正文）
     *
     * 错误：
     *      404 + 40001  简历不存在
     *      400 + 40004  正文太短
     *      400 + 10000  内容与已有版本相同
     */
    @PostMapping("/{id}/versions")
    @Operation(summary = "新增简历版本", description = "内容不变则拒绝创建")
    public ResponseEntity<ApiResponse<VersionDetailVO>> addVersion(
            @CurrentUserId Long userId,
            @PathVariable Long id,
            @RequestBody @Valid AddVersionRequest req) {

        VersionDetailVO vo = resumeService.addVersion(userId, id, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(vo));
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【版本列表】GET /api/resumes/{id}/versions
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 这个接口和"简历详情里的 versions 字段"重复了吗？
     *
     *   确实重复。为什么还保留？
     *     1. 简历详情接口将来可能变得很重（加统计、加最新得分），
     *        而版本列表可能需要单独刷新
     *     2. 前端可以"只刷新版本列表"而不重新拉整个详情
     *
     *   ★ 本项目的前端实际用的是详情里的 versions（更省请求）。
     *     这个接口先留着，如果确定不用可以删掉。
     *
     *   ★ 这是"接口设计"里常见的取舍：
     *     多一个接口可能冗余，但保留了灵活性。
     *     判断标准：如果它让代码变复杂却没带来价值，就删掉。
     */
    @GetMapping("/{id}/versions")
    @Operation(summary = "版本列表", description = "按版本号倒序，不含正文")
    public ApiResponse<List<VersionSummaryVO>> versions(@CurrentUserId Long userId,
                                                        @PathVariable Long id) {

        /*
         * ★ 直接复用 detail 的结果取 versions 字段。
         *   这样不用在 Service 里再加一个方法，
         *   而且保证"详情里的版本列表"和"单独查的版本列表"完全一致。
         *
         *   ★ 代价是多查了一次简历主体（detail 里会查）。
         *     数据量小，可接受。
         *     如果要优化，可以在 Service 里加一个只查版本列表的方法。
         */
        return ApiResponse.ok(resumeService.detail(userId, id).versions());
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【版本详情】GET /api/resumes/versions/{versionId}  ★ 含正文
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 这是全项目唯一返回简历正文的接口。
     *   所以它必须做严格的归属校验（Service 里用 findOwnedVersion）。
     *
     * ★ 为什么路径里没有 resumeId？
     *   前端点时间线上的版本时只知道 versionId。
     *   Service 会通过"版本 → 简历 → userId"来校验，不需要 resumeId 参数。
     *
     *   ★ 这也防止了一种攻击：用户传了自己的 resumeId + 别人的 versionId。
     *     如果不校验 versionId 是否属于该 resumeId，就能读到别人的正文。
     *     我们的实现只看 versionId 查出来之后校验它的简历归属，更安全。
     */
    @GetMapping("/versions/{versionId}")
    @Operation(summary = "版本详情", description = "含完整正文")
    public ApiResponse<VersionDetailVO> getVersion(@CurrentUserId Long userId,
                                                   @PathVariable Long versionId) {
        return ApiResponse.ok(resumeService.getVersion(userId, versionId));
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ Controller 的职责边界（第三次强调）】
     *
     *  ✅ 本类做的：
     *      - 从 token 取 userId（@CurrentUserId）
     *      - 从路径/请求体取参数
     *      - 触发校验（@Valid）
     *      - 调 Service 一个方法
     *      - 包成 ApiResponse / ResponseEntity
     *
     *  ❌ 本类不做的：
     *      - 任何 if/else 业务判断
     *      - 直接注入 Repository
     *      - try/catch
     *      - 组装 VO（那是 Service 的事）
     *
     *  ★ 8 个方法，每个都是"一行调用 + 一行返回"。
     *    这就是健康的 Controller 长什么样。
     * ═══════════════════════════════════════════════════════════════
     */
}
