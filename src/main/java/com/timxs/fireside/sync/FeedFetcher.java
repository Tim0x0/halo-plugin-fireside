package com.timxs.fireside.sync;

import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;
import run.halo.app.infra.ExternalUrlSupplier;

/**
 * RSS 抓取器：JDK HttpClient + Rome 解析。
 * 手动跟随重定向（每一跳都过 SSRF 校验），编码交给 XmlReader 自动探测。
 */
@Component
public class FeedFetcher {

    private static final int MAX_REDIRECTS = 5;

    private final ExternalUrlSupplier externalUrlSupplier;

    public FeedFetcher(ExternalUrlSupplier externalUrlSupplier) {
        this.externalUrlSupplier = externalUrlSupplier;
    }

    public SyndFeed fetch(String feedUrl, int timeoutSeconds) throws Exception {
        var timeout = Duration.ofSeconds(timeoutSeconds);
        var httpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        var uri = URI.create(feedUrl.trim());
        byte[] body = null;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            UrlSafety.requireSafe(uri);
            var request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", userAgent())
                .header("Accept",
                    "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
                .GET()
                .build();
            HttpResponse<byte[]> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    throw new IOException("重定向响应缺少 Location 头: HTTP " + status);
                }
                uri = uri.resolve(location);
                continue;
            }
            if (status != 200) {
                throw new IOException("HTTP " + status);
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
}
