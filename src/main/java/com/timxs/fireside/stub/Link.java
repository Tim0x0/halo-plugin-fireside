package com.timxs.fireside.stub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 链接插件 Link 模型的同 GVK 桩类，只声明本插件需要读取的字段。
 * scheme 由 PluginLinks 注册（硬依赖保证先启动），本插件仅用于反序列化读取，不回写。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "core.halo.run", version = "v1alpha1",
    kind = "Link", plural = "links", singular = "link")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Link extends AbstractExtension {

    private LinkSpec spec;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LinkSpec {

        private String url;

        private String displayName;

        private String logo;

        private String description;

        private Integer priority;

        private String groupName;
    }
}
