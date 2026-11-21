package com.jianli.community.post.controller;

import com.jianli.common.ApiResponse;
import com.jianli.common.CurrentUserId;
import com.jianli.common.PageResult;
import com.jianli.community.post.dto.CreatePostRequest;
import com.jianli.community.post.dto.PostVO;
import com.jianli.community.post.service.PostService;
import com.jianli.community.vote.service.VoteService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  社区帖子接口。
 *
 *      POST   /api/posts             发布（★ 强制个人信息审核）
 *      GET    /api/posts             列表（公开，支持筛选/搜索）
 *      GET    /api/posts/{id}        详情（公开）
 *      DELETE /api/posts/{id}        删除（仅作者）
 *      POST   /api/posts/{id}/votes  投票
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么列表和详情是公开的？】
 *
 *  社区的价值在于"没登录的人也能看"——
 *  能搜到、能分享给朋友、能被搜索引擎收录。
 *
 *  ★ 但登录后体验更好：
 *    能看到"我投过票吗"（myVote）、"这条是我发的吗"（isOwner）。
 *
 *  ★ 所以用 @CurrentUserId(required = false)：
 *    未登录时 userId 为 null，Service 跳过"我的投票"查询。
 */
@RestController
@RequestMapping("/api/posts")
@Tag(name = "社区-帖子")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final VoteService voteService;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【发布帖子】POST /api/posts
     * ═══════════════════════════════════════════════════════════════
     *
     * 响应 201：帖子详情
     *
     * 错误：
     *      403 + 43009  报告不属于你
     *      400 + 42004  报告还没算完
     *      409 + 43003  这份报告已经发过了
     *      ★ 422 + 43002  命中个人信息（data 里有 hits 供前端高亮）
     *
     * ★ 为什么 43002 用 422 而不是 400？
     *   用户填的格式完全正确，只是内容不可接受 ——
     *   422（Unprocessable Entity）的语义正好是"格式没问题，但内容我处理不了"。
     *   而且前端要专门针对它弹"命中项 + 高亮"对话框，需要独立码。
     */
    @PostMapping
    @Operation(summary = "发布帖子", description = "发布前强制做个人信息审核，命中则返回 43002")
    public ResponseEntity<ApiResponse<PostVO>> publish(
            @CurrentUserId Long userId,
            @RequestBody @Valid CreatePostRequest req) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(postService.publish(userId, req)));
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【社区列表】GET /api/posts
     * ═══════════════════════════════════════════════════════════════
     *
     * 查询参数全部可选：
     *      position  岗位名（等值匹配）
     *      expMin    最低年限
     *      expMax    最高年限
     *      keyword   搜索标题/正文
     *      sort      hot（热度）/ new（最新，默认）
     *      page      页码，1 开始
     *      size      每页条数
     *
     * ★ required = false 表示未登录也能访问（返回的 isOwner/myVote 都是 false/null）。
     */
    @GetMapping
    @Operation(summary = "帖子列表", description = "公开接口，支持岗位/年限筛选与关键词搜索")
    public ApiResponse<PageResult<PostVO>> list(
            @CurrentUserId(required = false) Long userId,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) Integer expMin,
            @RequestParam(required = false) Integer expMax,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "new") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {

        return ApiResponse.ok(postService.list(
                position, expMin, expMax, keyword, sort, page, size, userId));
    }

    /**
     * 【帖子详情】GET /api/posts/{id}
     *
     * ★ 详情返回完整内容：简历正文 + 报告快照。
     *   列表不带这些（省流量）。
     */
    @GetMapping("/{id}")
    @Operation(summary = "帖子详情")
    public ApiResponse<PostVO> detail(
            @CurrentUserId(required = false) Long userId,
            @PathVariable Long id) {

        return ApiResponse.ok(postService.detail(id, userId));
    }

    /** 【删除帖子】DELETE /api/posts/{id} —— 仅作者 */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除帖子", description = "软删除，仅作者可操作")
    public ApiResponse<Void> delete(@CurrentUserId Long userId, @PathVariable Long id) {
        postService.delete(userId, id);
        return ApiResponse.ok();
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【给帖子投票】POST /api/posts/{id}/votes
     * ═══════════════════════════════════════════════════════════════
     *
     * 请求：{"voteType": "UP"}  或  {"voteType": "DOWN"}
     *
     * 语义：
     *      没投过 → 新增
     *      投了同类型 → 取消（再点一次=撤销）
     *      投了相反类型 → 改票
     *
     * 错误：
     *      400 + 43007  不能给自己的帖子投票
     */
    @PostMapping("/{id}/votes")
    @Operation(summary = "帖子投票", description = "赞/踩互斥，再点一次取消")
    public ApiResponse<VoteService.VoteResult> vote(
            @CurrentUserId Long userId,
            @PathVariable Long id,
            @RequestBody VoteRequest req) {

        return ApiResponse.ok(voteService.votePost(userId, id, req.voteType()));
    }

    /**
     * 【我的帖子】GET /api/users/me/posts
     *
     * 放在这个 Controller 里而不是 UserController，
     * 因为它本质是"查帖子"，只是过滤条件是"我的"。
     */
    @GetMapping("/mine")
    @Operation(summary = "我的帖子", description = "含匿名帖（作者自己看得见）")
    public ApiResponse<PageResult<PostVO>> mine(
            @CurrentUserId Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {

        return ApiResponse.ok(postService.myPosts(userId, page, size));
    }

    public record VoteRequest(String voteType) {}
}
