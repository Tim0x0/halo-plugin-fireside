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
 * 去重键为 spec.linkName + spec.postLink（限定友链内，防跨友链抢注）；
 * spec.linkName 关联来源 Link，用于级联清理与按友链过滤。
 * 作者信息四字段（author/authorUrl/authorBio/logo）是入库时刻的 Link 快照：
 * 输出层（Finder/公开 API）以 Link 现值优先，仅在 Link 已删除
 * （级联清理完成前的窗口期）时回退这些快照字段。
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

        @Schema(description = "作者昵称，入库时快照自 Link 的 displayName（输出以 Link 现值优先）")
        private String author;

        @Schema(description = "作者站点地址，入库时快照自 Link 的 url（输出以 Link 现值优先）")
        private String authorUrl;

        @Schema(description = "作者简介，入库时快照自 Link 的 description（输出以 Link 现值优先）")
        private String authorBio;

        @Schema(description = "作者头像，入库时快照自 Link 的 logo（输出以 Link 现值优先）")
        private String logo;

        @Schema(description = "RSS 条目标题，可空，原样存储不做语义判断")
        private String title;

        @Schema(requiredMode = REQUIRED, description = "RSS 条目链接，去重键")
        private String postLink;

        @Schema(description = "清洗后的正文 HTML")
        private String contentHtml;

        @Schema(description = "纯文本摘要，约 500 字符")
        private String contentText;

        @Schema(description = "条目媒体列表（图片/视频/音频），对齐瞬间的 medium 模型；"
            + "来自正文媒体标签与 RSS enclosure 附件")
        private List<MediaItem> media;

        @Schema(description = "清洗后内容的 SHA-256（标题、正文、摘要、媒体地址）。"
            + "同步时与新抓取结果比对，相同则跳过；为空表示哈希机制上线前的旧数据")
        private String contentHash;

        @Schema(description = "是否隐藏：后台手动标记。前台与后台正常列表都不展示，"
            + "收在「已隐藏」里可恢复；postLink 仍保留用于去重，不会被重新抓取")
        private Boolean hidden;

        @Schema(description = "作者已删除：上次同步时该条目仍在保留窗口内，但整份 feed 已不再包含它。"
            + "前台不展示、不占保留名额，后台正常列表以红点标出；重新出现在 feed 中即清除")
        private Boolean authorDeleted;

        @Schema(description = "是否已读：仅后台使用。新入库为未读（绿点），手动标记后清除；"
            + "作者已删除与已隐藏的条目不计入未读")
        private Boolean read;

        @Schema(requiredMode = REQUIRED, description = "发布时间，排序键；缺失时回退抓取时刻")
        private Instant pubDate;
    }

    /**
     * 媒体项，type 取值与瞬间的 MomentMediaType 对齐：PHOTO / VIDEO / AUDIO。
     */
    @Data
    public static class MediaItem {

        @Schema(description = "媒体类型：PHOTO / VIDEO / AUDIO")
        private String type;

        @Schema(description = "媒体地址（已绝对化）")
        private String url;
    }
}
