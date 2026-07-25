package com.timxs.fireside.richlink;

/**
 * 富链接大类：与前台 data-kind、CSS 修饰符一致。
 * <ul>
 *   <li>{@link #MUSIC} / {@link #VIDEO}：平台白名单（仿朋友圈音视频分享）</li>
 *   <li>{@link #LINK}：其余独立分享链的通用网页卡</li>
 * </ul>
 */
public enum RichLinkKind {
    MUSIC,
    VIDEO,
    LINK;

    public String cssSuffix() {
        return name().toLowerCase();
    }
}
