package com.timxs.fireside.richlink;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 支持的富链接平台：用 host/path 正则识别，零 HTTP。
 * 短链只识别平台，不解析跳转（v1）。
 * <p>
 * {@link #GENERIC} 不参与 host 白名单匹配，仅作「非音视频平台」的通用网页卡回退。
 */
@Getter
public enum RichLinkPlatform {

    NETEASE(RichLinkKind.MUSIC, "netease", "网易云音乐",
        Pattern.compile("(?i)(^|\\.)(music\\.163\\.com|y\\.music\\.163\\.com|163cn\\.tv)$")),

    QQMUSIC(RichLinkKind.MUSIC, "qqmusic", "QQ音乐",
        Pattern.compile("(?i)(^|\\.)((i\\.)?y\\.qq\\.com|c6\\.y\\.qq\\.com)$")),

    KUGOU(RichLinkKind.MUSIC, "kugou", "酷狗音乐",
        Pattern.compile("(?i)(^|\\.)(.*\\.)?kugou\\.com$")),

    BILIBILI(RichLinkKind.VIDEO, "bilibili", "哔哩哔哩",
        Pattern.compile("(?i)(^|\\.)((www|m|space)\\.)?bilibili\\.com$|(^|\\.)(b23\\.tv|bili2233\\.cn)$")),

    DOUYIN(RichLinkKind.VIDEO, "douyin", "抖音",
        Pattern.compile("(?i)(^|\\.)(.*\\.)?(douyin\\.com|iesdouyin\\.com)$")),

    YOUTUBE(RichLinkKind.VIDEO, "youtube", "YouTube",
        Pattern.compile("(?i)(^|\\.)((www|m)\\.)?youtube\\.com$|(^|\\.)youtu\\.be$")),

    QQVIDEO(RichLinkKind.VIDEO, "qqvideo", "腾讯视频",
        Pattern.compile("(?i)(^|\\.)(v\\.qq\\.com|m\\.v\\.qq\\.com)$")),

    /** 通用网页：不靠 host 正则匹配，由 {@link #matchShare(String)} 在白名单未命中时回退 */
    GENERIC(RichLinkKind.LINK, "generic", "网页", null);

    private final RichLinkKind kind;
    private final String id;
    private final String displayName;
    private final Pattern hostPattern;

    RichLinkPlatform(RichLinkKind kind, String id, String displayName, Pattern hostPattern) {
        this.kind = kind;
        this.id = id;
        this.displayName = displayName;
        this.hostPattern = hostPattern;
    }

    /**
     * 仅匹配音视频等平台白名单；非 http(s) 或不匹配返回 empty。
     * 通用网页请用 {@link #matchShare(String)}。
     */
    public static Optional<RichLinkPlatform> match(String url) {
        if (StringUtils.isBlank(url)) {
            return Optional.empty();
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return Optional.empty();
        }
        String host = hostOf(trimmed);
        if (host == null) {
            return Optional.empty();
        }
        // 去掉端口
        int colon = host.indexOf(':');
        if (colon > 0) {
            host = host.substring(0, colon);
        }
        for (RichLinkPlatform platform : values()) {
            if (platform == GENERIC || platform.hostPattern == null) {
                continue;
            }
            if (platform.hostPattern.matcher(host).find()) {
                // B 站主站还要 path 像视频/短链；纯 bilibili.com 首页不升级
                if (platform == BILIBILI && !looksLikeBilibiliShare(trimmed)) {
                    continue;
                }
                if (platform == YOUTUBE && !looksLikeYoutubeShare(trimmed)) {
                    continue;
                }
                if (platform == QQMUSIC && !looksLikeQqMusicShare(trimmed)) {
                    continue;
                }
                if (platform == NETEASE && !looksLikeNeteaseShare(trimmed)) {
                    continue;
                }
                return Optional.of(platform);
            }
        }
        return Optional.empty();
    }

    /**
     * 分享链识别：白名单平台优先，否则 http(s) 可解析 host 的独立链走通用网页卡。
     */
    public static Optional<RichLinkPlatform> matchShare(String url) {
        Optional<RichLinkPlatform> known = match(url);
        if (known.isPresent()) {
            return known;
        }
        if (StringUtils.isBlank(url)) {
            return Optional.empty();
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return Optional.empty();
        }
        String host = hostOf(trimmed);
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        // 相对链经 baseUri 会变成 localhost，不当作可分享外链
        if ("localhost".equals(host) || "127.0.0.1".equals(host)) {
            return Optional.empty();
        }
        return Optional.of(GENERIC);
    }

    /**
     * 展示用主机名（去 www.、小写）；解析失败返回「网页」。
     */
    public static String hostLabel(String url) {
        String host = hostOf(url);
        if (host == null || host.isBlank()) {
            return "网页";
        }
        int colon = host.indexOf(':');
        if (colon > 0) {
            host = host.substring(0, colon);
        }
        if (host.regionMatches(true, 0, "www.", 0, 4)) {
            host = host.substring(4);
        }
        return host.isEmpty() ? "网页" : host;
    }

    private static String hostOf(String url) {
        try {
            var uri = java.net.URI.create(url.trim());
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean looksLikeBilibiliShare(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("b23.tv") || u.contains("bili2233.cn")
            || u.contains("/video/") || u.contains("/bangumi/")
            || u.contains("bvid=") || u.contains("/bv");
    }

    private static boolean looksLikeYoutubeShare(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("youtu.be/") || u.contains("watch") || u.contains("shorts/")
            || u.contains("youtu.be");
    }

    private static boolean looksLikeQqMusicShare(String url) {
        // host 已匹配 y.qq.com；path 再收窄到歌曲/歌单等，避免官网首页误升
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("song") || u.contains("songmid") || u.contains("songid")
            || u.contains("/playsong") || u.contains("playlist") || u.contains("album")
            || u.contains("singer") || u.contains("mv");
    }

    private static boolean looksLikeNeteaseShare(String url) {
        // host 已匹配 music.163.com / 163cn.tv；勿用笼统 id=（几乎所有链接都有）
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains("163cn.tv")) {
            return true;
        }
        return u.contains("/song") || u.contains("song?id=") || u.contains("song/media")
            || u.contains("/program") || u.contains("/playlist") || u.contains("/album")
            || u.contains("/dj") || u.contains("/mv") || u.contains("#/song");
    }

    /** 默认封面（插件静态资源，前端可被 link-detail 覆盖） */
    public String defaultCoverPath() {
        return switch (kind) {
            case MUSIC -> "/plugins/plugin-fireside/assets/static/richlink/music.svg";
            case VIDEO -> "/plugins/plugin-fireside/assets/static/richlink/video.svg";
            case LINK -> "/plugins/plugin-fireside/assets/static/richlink/link.svg";
        };
    }
}
