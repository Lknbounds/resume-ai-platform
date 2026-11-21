package com.jianli.jd.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  新建 / 更新 JD 的请求体。
 *
 *      POST /api/jds
 *      {"title":"Java 后端开发工程师","company":"某科技","content":"岗位职责：..."}
 * ═══════════════════════════════════════════════════════════════════════
 */
public record SaveJdRequest(

        /**
         * 岗位名称。必填。
         *
         * ★ 为什么必填？
         *   这是用户在列表里辨认 JD 的唯一依据。
         *   不填的话，列表里全是"未命名"，完全没法用。
         *   （虽然解析器能从内容里猜出岗位名，但那是"猜"，
         *     让用户明确填一次更可靠。）
         */
        @NotBlank(message = "请填写岗位名称")
        @Size(max = 100, message = "岗位名称最长 100 个字符")
        String title,

        /** 公司名。选填 —— 用户可能不知道或者不想填 */
        @Size(max = 100, message = "公司名最长 100 个字符")
        String company,

        /**
         * JD 原文。
         *
         * ★ 为什么没有 @Size 上限？
         *   和简历一样，JD 可能很长。
         *   在 Service 里做"至少 30 字"的校验（太短没有解析价值）。
         *   上限靠 HTTP 请求体大小控制，不在这里卡。
         */
        @NotBlank(message = "请粘贴 JD 内容")
        String content
) {}
