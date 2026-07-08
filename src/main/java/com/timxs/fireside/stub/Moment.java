package com.timxs.fireside.stub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 瞬间插件 Moment 模型的同 GVK 桩类，字段结构与 halo-sigs/plugin-moments 一致。
 * scheme 由 PluginMoments 注册（可选依赖）——查询前必须先经 SchemeManager 确认 scheme 存在。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "moment.halo.run", version = "v1alpha1",
    kind = "Moment", plural = "moments", singular = "moment")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Moment extends AbstractExtension {

    private MomentSpec spec;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MomentSpec {

        private MomentContent content;

        private Instant releaseTime;

        private MomentVisible visible;

        private String owner;

        private Set<String> tags;

        private Boolean approved;

        private Instant approvedTime;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MomentContent {

        private String raw;

        private String html;

        private List<MomentMedia> medium;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MomentMedia {

        private MomentMediaType type;

        private String url;

        private String originType;
    }

    public enum MomentMediaType {
        PHOTO,
        VIDEO,
        POST,
        AUDIO
    }

    public enum MomentVisible {
        PUBLIC,
        PRIVATE
    }

    public boolean isApproved() {
        return spec != null && Boolean.TRUE.equals(spec.getApproved());
    }

    public boolean isPubliclyVisible() {
        return spec != null && MomentVisible.PUBLIC.equals(spec.getVisible());
    }
}
