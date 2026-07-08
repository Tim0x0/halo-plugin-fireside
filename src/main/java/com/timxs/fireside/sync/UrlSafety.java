package com.timxs.fireside.sync;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF 防护：RSS 地址由可编辑链接的角色填写，抓取请求从服务器发出，
 * 必须拒绝环回/私网/链路本地等保留地址，且仅允许 http/https。
 */
final class UrlSafety {

    private UrlSafety() {
    }

    static void requireSafe(URI uri) {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("仅允许 http/https 协议: " + uri);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL 缺少主机名: " + uri);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("无法解析主机: " + host, e);
        }
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address)) {
                throw new IllegalArgumentException("拒绝访问内网/保留地址: " + host);
            }
        }
    }

    /**
     * IPv6 unique local address（fc00::/7），InetAddress 没有对应的判断方法。
     */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
}
