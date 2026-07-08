package com.timxs.fireside;

/**
 * 插件级常量。
 */
public final class FiresideConst {

    /**
     * Link 上的动态 RSS 地址注解键（AnnotationSetting 注入链接编辑表单的字段名）。
     * 采用简单键跟随社区实践（plugin-friends 的 rss_url 同形态）。
     */
    public static final String RSS_URL_ANNO = "fireside_rss_url";

    /**
     * 瞬间插件 Counter 命名格式：plural.group/name（与 plugin-moments 的 MeterUtils 一致）。
     */
    public static final String MOMENT_COUNTER_PREFIX = "moments.moment.halo.run/";

    private FiresideConst() {
    }
}
