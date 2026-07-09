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
     * Link 上的暂停同步注解键：值为 "true" 时跳过同步，但保留已抓取的数据
     * （区别于清空 RSS 地址——那会级联删除该友链全部动态）。
     */
    public static final String PAUSED_ANNO = "fireside_paused";

    /**
     * 瞬间插件 Counter 命名格式：plural.group/name（与 plugin-moments 的 MeterUtils 一致）。
     */
    public static final String MOMENT_COUNTER_PREFIX = "moments.moment.halo.run/";

    private FiresideConst() {
    }
}
