package com.timxs.fireside.config;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;

/**
 * settings.yaml 中 display 组的配置项。
 * tab 内以 $formkit: group 分节：基础字段平铺，外观/互动嵌套在
 * appearance / interaction 子对象下（与表单分节一一对应）。
 */
@Data
public class DisplayConfig {

    public static final String GROUP = "display";

    public static final String DEFAULT_FRIEND_TAG = "友链";

    public static final String DEFAULT_PAGE_TITLE = "围炉";

    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 内置 /fireside 页面的标题。
     */
    private String pageTitle;

    /**
     * 详情页标题；空则跟随页面标题。
     */
    private String detailPageTitle;

    /**
     * 内置页每页展示的动态条数。
     */
    private Integer pageSize;

    /**
     * 浏览器标题分隔符（"页面标题 分隔符 站点名"），可选 - 或 |。
     */
    private String titleSeparator;

    /**
     * 赋给每条友链动态的标签名；主题可用 ?tag=该标签 仅筛出友链动态。
     */
    private String friendTag;

    /**
     * 外观分节；初始化默认实例，存量配置无该节点时模板取值不为 null。
     */
    private Appearance appearance = new Appearance();

    /**
     * 互动分节；初始化默认实例，存量配置无该节点时模板取值不为 null。
     */
    private Interaction interaction = new Interaction();

    public String effectiveFriendTag() {
        // 井号由展示层统一添加；配置值带 # 会导致双井号，且 # 是 URL 锚点符会破坏
        // ?tag= 筛选——无论填不填 # 都归一为纯标签名
        String value = StringUtils.trim(StringUtils.stripStart(
            StringUtils.trim(friendTag), "#"));
        return StringUtils.defaultIfBlank(value, DEFAULT_FRIEND_TAG);
    }

    public String effectivePageTitle() {
        return StringUtils.defaultIfBlank(
            StringUtils.trim(pageTitle), DEFAULT_PAGE_TITLE);
    }

    /**
     * 详情页标题：未配置时跟随页面标题。
     */
    public String effectiveDetailPageTitle() {
        return StringUtils.defaultIfBlank(
            StringUtils.trim(detailPageTitle), effectivePageTitle());
    }

    /**
     * 每页条数：默认 20，钳制到 10~100（大值会拉长首屏渲染）。
     */
    public int effectivePageSize() {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.max(10, Math.min(100, pageSize));
    }

    /**
     * 标题分隔符：仅接受 - 或 |，其余值（含未配置）回退 -。
     */
    public String effectiveTitleSeparator() {
        return "|".equals(StringUtils.trim(titleSeparator)) ? "|" : "-";
    }

    /**
     * 外观分节（$formkit: group name=appearance）：封面、签名、配色。
     */
    @Data
    public static class Appearance {

        /** 顶部封面图；兼作列表 og:image。空则默认底且不输出分享图。 */
        private String coverImage;

        /** 封面下签名；兼作列表 SEO/分享描述。空则不显示也不输出描述。 */
        private String signature;

        /**
         * 内置页默认配色方案：auto=跟随系统 / light / dark；
         * 访客切换（若允许）保存在其浏览器本地并优先于此默认值。
         */
        private String colorScheme;

        /**
         * 是否在内置页右下角显示访客配色切换按钮。
         */
        private Boolean allowSchemeToggle;

        /**
         * 配色方案：仅接受 light/dark，其余（含未配置）回退 auto。
         */
        public String effectiveColorScheme() {
            String value = StringUtils.trim(colorScheme);
            return "light".equals(value) || "dark".equals(value) ? value : "auto";
        }

        /**
         * 访客配色切换按钮：默认显示。
         */
        public boolean effectiveAllowSchemeToggle() {
            return !Boolean.FALSE.equals(allowSchemeToggle);
        }

        /** 列表 SEO 描述：签名压空白后约 160 字；空串表示不输出 meta。 */
        public String effectiveSeoDescription() {
            String value = StringUtils.normalizeSpace(StringUtils.trimToEmpty(signature));
            return value.isEmpty() ? "" : StringUtils.abbreviate(value, 160);
        }

        /** 列表分享图：封面 URL（可为相对路径）；空串表示不输出。 */
        public String effectiveSeoImage() {
            return StringUtils.trimToEmpty(coverImage);
        }
    }

    /**
     * 互动分节（$formkit: group name=interaction）：评论预览、发瞬间入口。
     */
    @Data
    public static class Interaction {

        public static final int DEFAULT_COMMENT_PREVIEW_COUNT = 10;

        /**
         * 列表页自身瞬间卡片下方的评论预览条数：0 关闭，-1 全部，正数为上限。
         */
        private Integer commentPreviewCount;

        /**
         * 是否在内置页封面显示「发瞬间」入口（仅对有发布权限的登录用户可见）。
         */
        private Boolean showComposeEntry;

        /**
         * 评论预览条数：默认 10，钳制到 -1~100（-1=全部，0=关闭）。
         */
        public int effectiveCommentPreviewCount() {
            if (commentPreviewCount == null) {
                return DEFAULT_COMMENT_PREVIEW_COUNT;
            }
            return Math.max(-1, Math.min(100, commentPreviewCount));
        }

        /**
         * 发瞬间入口：默认开启，仅显式关闭时隐藏。
         */
        public boolean effectiveShowComposeEntry() {
            return !Boolean.FALSE.equals(showComposeEntry);
        }
    }
}
