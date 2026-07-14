package com.timxs.fireside.sync;

import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import io.netty.channel.ChannelOption;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.WebClient;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.infra.utils.HttpSecurityUtils;

/**
 * RSS 抓取器：基于 Halo 核心的 SSRF 安全客户端（HttpSecurityUtils，与官方链接插件的
 * SafeUrlFetcher 同源）。安全校验发生在 DNS 解析后、建连前（resolvedAddressesSelector
 * 过滤私网/保留地址），没有"先校验后连接"的 DNS rebinding 窗口，且覆盖 CGNAT、0/8、240/4 等段。
 * 手动跟随重定向（每一跳都重新走安全校验，官方 SafeUrlFetcher 同款做法）；
 * 响应体经 maxResponseSizeFilter 限制大小，读取用原始 DataBuffer 聚合
 * （绕开 codec 的 256KB 默认上限）；编码交给 XmlReader 自动探测。
 */
@Component
public class FeedFetcher {

    private static final int MAX_REDIRECTS = 5;

    /**
     * 响应体大小上限：RSS 正常在几百 KB 量级，10MB 已远超合理范围，
     * 防异常/恶意大响应打爆内存（官方链接插件同款机制，上限 20MB）。
     */
    private static final int MAX_BODY_SIZE = 10 * 1024 * 1024;

    private final ExternalUrlSupplier externalUrlSupplier;

    public FeedFetcher(ExternalUrlSupplier externalUrlSupplier) {
        this.externalUrlSupplier = externalUrlSupplier;
    }

    public SyndFeed fetch(String feedUrl, int timeoutSeconds) throws Exception {
        var timeout = Duration.ofSeconds(timeoutSeconds);
        // 含全部重定向跳与 body 传输的总预算：responseTimeout 只覆盖响应等待，
        // 没有总预算时"慢速滴漏 + 多级重定向"最坏可把单链耗时放大到批次超时之上
        long deadlineNanos = System.nanoTime() + timeout.multipliedBy(2).toNanos();
        WebClient webClient = buildWebClient(timeout);
        URI uri = URI.create(feedUrl.trim());
        byte[] body = null;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            requireHttpUrl(uri);
            Response response = execute(webClient, uri,
                Duration.ofNanos(Math.max(deadlineNanos - System.nanoTime(), 1)));
            if (response.status() >= 300 && response.status() < 400) {
                if (response.location() == null) {
                    throw new IOException("重定向响应缺少 Location 头: HTTP " + response.status());
                }
                uri = uri.resolve(response.location());
                continue;
            }
            if (response.status() != 200) {
                throw new IOException("HTTP " + response.status());
            }
            body = response.body();
            break;
        }
        if (body == null) {
            throw new IOException("重定向次数超过 " + MAX_REDIRECTS + " 次");
        }
        try (var reader = new XmlReader(new ByteArrayInputStream(body))) {
            return new SyndFeedInput().build(reader);
        }
    }

    private Response execute(WebClient webClient, URI uri, Duration remaining)
        throws IOException {
        try {
            // 用原始 DataBuffer 聚合读取 body（官方 SafeUrlFetcher 同款）：
            // bodyToMono(byte[]) 走 codec，受 maxInMemorySize 默认 256KB 限制，
            // 正常体积的全文 RSS 都会被误拒；大小上限由 maxResponseSizeFilter 统一执行
            Response response = webClient.get()
                .uri(uri)
                .header(HttpHeaders.USER_AGENT, userAgent())
                .header(HttpHeaders.ACCEPT,
                    "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
                .exchangeToMono(clientResponse ->
                    DataBufferUtils.join(clientResponse.body(BodyExtractors.toDataBuffers()))
                        .map(FeedFetcher::readAndRelease)
                        .defaultIfEmpty(new byte[0])
                        .map(bytes -> new Response(clientResponse.statusCode().value(),
                            clientResponse.headers().asHttpHeaders()
                                .getFirst(HttpHeaders.LOCATION),
                            bytes)))
                .block(remaining);
            if (response == null) {
                throw new IOException("空响应");
            }
            return response;
        } catch (DataBufferLimitException e) {
            throw new IOException("响应体超过大小上限 " + (MAX_BODY_SIZE / 1024 / 1024) + "MB");
        } catch (IllegalStateException e) {
            // block(Duration) 超时抛 IllegalStateException——转成可读的中文错误
            throw new IOException("抓取超时（含重定向的总预算为超时设置的 2 倍）");
        }
    }

    private static byte[] readAndRelease(DataBuffer dataBuffer) {
        try {
            byte[] bytes = new byte[dataBuffer.readableByteCount()];
            dataBuffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(dataBuffer);
        }
    }

    private WebClient buildWebClient(Duration timeout) {
        var httpClient = HttpSecurityUtils.secureHttpClient()
            .responseTimeout(timeout)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeout.toMillis());
        return WebClient.builder()
            .filter(HttpSecurityUtils.maxResponseSizeFilter(MAX_BODY_SIZE))
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }

    /**
     * 协议与主机名的前置校验（快速失败给出可读错误）；
     * 私网/保留地址的拦截由 secureHttpClient 在建连时完成。
     */
    private static void requireHttpUrl(URI uri) {
        if (!HttpUrls.isHttpScheme(uri.getScheme())) {
            throw new IllegalArgumentException("仅允许 http/https 协议: " + uri);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("URL 缺少主机名: " + uri);
        }
    }

    private String userAgent() {
        String site = "https://github.com/Tim0x0/halo-plugin-fireside";
        try {
            URI externalUrl = externalUrlSupplier.get();
            if (externalUrl != null) {
                site = externalUrl.toString();
            }
        } catch (Exception ignored) {
            // externalUrl 未配置时退回插件主页
        }
        return "Halo-Fireside/1.0 (+" + site + ")";
    }

    private record Response(int status, String location, byte[] body) {
    }
}
