package com.timxs.fireside.sync;

import java.net.URI;
import org.apache.commons.lang3.StringUtils;

/**
 * http/https 协议白名单的唯一权威实现：抓取入口（FeedFetcher）、地址绝对化
 * （resolveAbsolute）与媒体入库（addMedia）共用，避免多处拷贝导致口径漂移。
 */
final class HttpUrls {

    private HttpUrls() {
    }

    static boolean isHttpScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /**
     * 是否为可安全存储/展示的绝对 http(s) 地址。
     */
    static boolean isHttp(String url) {
        if (StringUtils.isBlank(url)) {
            return false;
        }
        try {
            return isHttpScheme(URI.create(url).getScheme());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
