package com.jianli.common;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  统一的分页响应结构。所有"列表"接口都返回它。
 *
 *  前端拿到的 JSON：
 *      {
 *        "records": [ {...}, {...} ],
 *        "total": 42,
 *        "page": 1,
 *        "size": 10,
 *        "pages": 5
 *      }
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么需要它？Spring Data 自带的 Page 不能直接用吗？】
 *
 *  Spring Data 的 Page 对象直接序列化会有几个问题：
 *
 *  1. 【字段太多且冗余】
 *     Page 序列化后会有 pageable、sort、first、last、numberOfElements、
 *     empty 等十几个字段，其中大部分前端用不到，徒增体积。
 *
 *  2. 【page 从 0 开始】
 *     Spring 的页码是 0-based（第 1 页是 0），但前端和用户习惯是 1-based。
 *     如果不转换，前端显示"第 0 页"很奇怪。
 *
 *  3. 【格式不稳定】
 *     Page 的序列化格式在不同 Spring 版本间可能变化。
 *     自己定义一个 record，格式就永远固定。
 *
 *  ★ 这就是为什么要写一个"转换层"：
 *     后端内部用 Spring 的 Page（功能全），
 *     对外用 PageResult（干净、稳定、1-based）。
 *
 *
 *  【★ 数据流向】
 *
 *      Service 里：
 *         Page<Resume> page = resumeRepository.findByUserId(..., pageable);
 *         return PageResult.of(page.map(ResumeVO::of));
 *              ↓
 *      Controller：ApiResponse.ok(pageResult)
 *              ↓
 *      JSON：{records:[...], total:42, page:1, size:10, pages:5}
 *              ↓
 *      前端 ElPagination 组件直接吃这五个字段
 */
public record PageResult<T>(

        /** 当前页的数据 */
        List<T> records,

        /** 总记录数（用于计算页数、显示"共 42 条"） */
        long total,

        /** 当前页码，从 1 开始 */
        int page,

        /** 每页条数 */
        int size,

        /** 总页数 */
        int pages
) {

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【从 Spring 的 Page 转换】最常用的方法
     * ═══════════════════════════════════════════════════════════════
     *
     * 用法：
     *      Page<Resume> entityPage = repo.findByUserId(userId, pageable);
     *      Page<ResumeSummaryVO> voPage = entityPage.map(ResumeSummaryVO::of);
     *      return PageResult.of(voPage);
     *
     * ★ 注意 Page.map(...) 这一步：
     *   它把 Page<Entity> 转成 Page<VO>，同时保留所有分页信息。
     *   所以调用方要先 map 成 VO，再传给本方法 ——
     *   这样 Service 不会把 Entity 直接暴露出去。
     *
     * ★ 为什么这里 page 要 +1？
     *   Spring 的 getNumber() 返回 0-based（第一页是 0）。
     *   我们对外统一用 1-based（第一页是 1）。
     *
     *   ★ 这个转换只写一次，就避免了"前端显示第 0 页"这种低级 bug。
     */
    public static <T> PageResult<T> of(Page<T> page) {
        return new PageResult<>(
                page.getContent(),           // 当前页的数据列表
                page.getTotalElements(),      // 总条数
                page.getNumber() + 1,         // ★ 0-based → 1-based
                page.getSize(),
                page.getTotalPages()
        );
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【不分页的情况】比如"一次返回所有板块得分"
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 为什么不直接返回 List，非要用 PageResult 包一层？
     *   因为前端可能写了一个通用的列表处理逻辑（都从 records 里取）。
     *   统一结构能减少前端的特判分支。
     *
     * ★ 但也别滥用 —— 如果确定永远不分页（比如"5 个板块得分"），
     *   直接返回 List 更简单。这个方法只给"结构上可能分页、目前数据少"的场景用。
     */
    public static <T> PageResult<T> of(List<T> records) {
        return new PageResult<>(records, records.size(), 1, records.size(), 1);
    }
}
