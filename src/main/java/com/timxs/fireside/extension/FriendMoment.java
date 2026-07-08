package com.timxs.fireside.extension;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 友链动态条目：定时抓取友链 RSS 后落库的单条动态。
 * 去重键为 spec.postLink；spec.linkName 关联来源 Link，用于级联清理与按友链过滤。
 */
@GVK(group = "fireside.timxs.com", version = "v1alpha1", kind = "FriendMoment",
    plural = "friendmoments", singular = "friendmoment")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class FriendMoment extends AbstractExtension {

    @Schema(requiredMode = REQUIRED)
    private FriendMomentSpec spec;

    @Data
    public static class FriendMomentSpec {

        @Schema(requiredMode = REQUIRED, description = "来源 Link 的 metadata.name")
        private String linkName;

        @Schema(description = "作者昵称，冗余自 Link 的 displayName")
        private String author;

        @Schema(description = "作者站点地址，冗余自 Link 的 url")
        private String authorUrl;

        @Schema(description = "作者简介，冗余自 Link 的 description")
        private String authorBio;

        @Schema(description = "作者头像，冗余自 Link 的 logo")
        private String logo;

        @Schema(description = "RSS 条目标题，可空，原样存储不做语义判断")
        private String title;

        @Schema(requiredMode = REQUIRED, description = "RSS 条目链接，去重键")
        private String postLink;

        @Schema(description = "清洗后的正文 HTML")
        private String contentHtml;

        @Schema(description = "纯文本摘要，约 500 字符")
        private String contentText;

        @Schema(description = "正文中提取的图片地址列表（已绝对化）")
        private List<String> images;

        @Schema(requiredMode = REQUIRED, description = "发布时间，排序键；缺失时回退抓取时刻")
        private Instant pubDate;
    }
}
