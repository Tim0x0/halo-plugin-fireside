package com.timxs.fireside.vo;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Data;
import run.halo.app.extension.MetadataOperator;

/**
 * 聚合时间线条目，字段路径与瞬间插件 MomentVo 完全兼容
 * （metadata / spec.content.* / spec.releaseTime / owner.* / stats.*），
 * 主题现有的瞬间渲染代码可直接消费；另附加 external / sourceUrl / authorUrl / title 扩展字段。
 * 所有嵌套对象一律非 null（空值给零值对象/空集合），主题模板普遍不写安全导航。
 */
@Data
@Builder
public class FiresideMomentVo {

    private MetadataOperator metadata;

    private SpecVo spec;

    private OwnerVo owner;

    private StatsVo stats;

    /** 扩展：是否友链条目（false = 自身瞬间） */
    private boolean external;

    /** 扩展：自身瞬间为 /moments/{name} 详情页，友链条目为原文链接 */
    private String sourceUrl;

    /** 扩展：友链作者站点地址，自身瞬间为 null */
    private String authorUrl;

    /** 扩展：RSS 条目标题，可空；自身瞬间为 null */
    private String title;

    @Data
    @Builder
    public static class SpecVo {

        private ContentVo content;

        private Instant releaseTime;

        private Visible visible;

        private String owner;

        private Set<String> tags;

        private Boolean approved;

        private Instant approvedTime;
    }

    @Data
    @Builder
    public static class ContentVo {

        private String raw;

        private String html;

        private List<MediaVo> medium;
    }

    @Data
    @Builder
    public static class MediaVo {

        private MediaType type;

        private String url;

        private String originType;
    }

    /**
     * 与瞬间插件 MomentMediaType 同名同值，模板中 == 'PHOTO' 与 .name() 两种写法行为一致。
     */
    public enum MediaType {
        PHOTO,
        VIDEO,
        POST,
        AUDIO
    }

    /**
     * 与瞬间插件 MomentVisible 同名同值。
     */
    public enum Visible {
        PUBLIC,
        PRIVATE
    }

    /**
     * 与瞬间插件 ContributorVo 字段一致。
     */
    @Data
    @Builder
    public static class OwnerVo {

        private String name;

        private String avatar;

        private String bio;

        private String displayName;
    }

    /**
     * 与瞬间插件 Stats 字段一致；友链条目恒为零值对象而非 null。
     */
    @Data
    @Builder
    public static class StatsVo {

        private Integer upvote;

        private Integer totalComment;

        private Integer approvedComment;

        public static StatsVo empty() {
            return StatsVo.builder()
                .upvote(0)
                .totalComment(0)
                .approvedComment(0)
                .build();
        }
    }
}
