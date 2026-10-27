package com.timxs.fireside.extension;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 每个订阅友链一份的同步状态（metadata.name = 来源 Link 的 metadata.name），
 * 字段设计对照链接插件的 Link.RssStatus，供管理页状态徽章/详情展示。
 * 本插件不能回写 Link.status（他插件资源），故独立成自有模型。
 */
@GVK(group = "fireside.timxs.com", version = "v1alpha1", kind = "FeedStatus",
    plural = "feedstatuses", singular = "feedstatus")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class FeedStatus extends AbstractExtension {

    @Schema(requiredMode = REQUIRED)
    private FeedStatusSpec spec;

    @Data
    public static class FeedStatusSpec {

        @Schema(requiredMode = REQUIRED, description = "来源 Link 的 metadata.name")
        private String linkName;

        @Schema(description = "本次使用的 RSS 地址")
        private String rssUrl;

        @Schema(description = "最近一次尝试同步的时间")
        private Instant lastFetchedAt;

        @Schema(description = "最近一次同步成功的时间")
        private Instant lastSuccessAt;

        @Schema(description = "最近一次同步失败的原因，成功时清空")
        private String lastError;

        @Schema(description = "连续失败次数，成功后归零")
        private Integer failureCount;

        @Schema(description = "已观测到的最新条目发布时间")
        private Instant latestPublishedAt;

        @Schema(description = "当前对外展示的条目数（不含已隐藏与作者已删除）")
        private Long itemCount;

        @Schema(description = "未读条目数（仅后台：对外展示且未读的条目）")
        private Long unreadCount;
    }
}
