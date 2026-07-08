package com.timxs.fireside.sync;

import static run.halo.app.extension.index.query.Queries.equal;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.timxs.fireside.FiresideConst;
import com.timxs.fireside.config.SyncConfig;
import com.timxs.fireside.extension.FriendMoment;
import com.timxs.fireside.stub.Link;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.MetadataUtil;

/**
 * RSS 同步服务：抓取填写了动态 RSS 地址的友链，清洗后落库 FriendMoment。
 * postLink 去重（首见定格）、每链接保留 retainPerLink 条、pubDate 未来时间钳制。
 * 运行在调度线程（非 NIO 线程），使用阻塞 ExtensionClient。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RssSyncService {

    private static final int CONCURRENCY = 2;
    private static final int CONTENT_TEXT_MAX_LENGTH = 500;
    private static final Duration FUTURE_TOLERANCE = Duration.ofDays(1);
    private static final Duration ROUND_TIMEOUT = Duration.ofMinutes(10);

    private final ExtensionClient client;
    private final FeedFetcher feedFetcher;

    /**
     * 轮转游标：友链数超过 maxLinksPerRun 时避免每轮总是处理同一批。
     */
    private final AtomicInteger cursor = new AtomicInteger();

    public void syncAll(SyncConfig config) {
        List<Link> links = listRssLinks();
        if (links.isEmpty()) {
            log.debug("[围炉] 没有填写动态 RSS 地址的友链，跳过本轮同步");
            return;
        }
        List<Link> batch = rotateBatch(links, config.effectiveMaxLinksPerRun());
        log.info("[围炉] 开始同步友链动态：共 {} 个订阅，本轮处理 {} 个", links.size(), batch.size());
        Flux.fromIterable(batch)
            .flatMap(link -> Mono.fromRunnable(() -> syncOne(link, config))
                .subscribeOn(Schedulers.boundedElastic()), CONCURRENCY)
            .then()
            .block(ROUND_TIMEOUT);
    }

    /**
     * 同步单个友链，失败只记日志不中断整轮。
     */
    private void syncOne(Link link, SyncConfig config) {
        String linkName = link.getMetadata().getName();
        String rssUrl = StringUtils.trimToNull(
            MetadataUtil.nullSafeAnnotations(link).get(FiresideConst.RSS_URL_ANNO));
        if (rssUrl == null) {
            return;
        }
        try {
            SyndFeed feed = feedFetcher.fetch(rssUrl, config.effectiveTimeoutSeconds());
            int created = 0;
            for (SyndEntry entry : feed.getEntries()) {
                FriendMoment.FriendMomentSpec spec = mapEntry(entry, feed, rssUrl, link);
                if (spec == null || exists(spec.getPostLink())) {
                    continue;
                }
                var friendMoment = new FriendMoment();
                var metadata = new Metadata();
                metadata.setGenerateName("friend-moment-");
                friendMoment.setMetadata(metadata);
                friendMoment.setSpec(spec);
                client.create(friendMoment);
                created++;
            }
            int trimmed = trimToRetain(linkName, config.effectiveRetainPerLink());
            log.info("[围炉] 同步友链 [{}] 完成：新增 {} 条，清理 {} 条", linkName, created, trimmed);
        } catch (Exception e) {
            log.warn("[围炉] 同步友链 [{}] 失败（{}）：{}", linkName, rssUrl, e.getMessage());
        }
    }

    /**
     * 把 RSS 条目映射为 FriendMomentSpec；缺少可用链接（去重键）时返回 null 跳过。
     */
    private FriendMoment.FriendMomentSpec mapEntry(SyndEntry entry, SyndFeed feed,
        String feedUrl, Link link) {
        String siteBase = resolveAbsolute(feedUrl, feed.getLink());
        String postLink = resolveAbsolute(feedUrl, entry.getLink());
        if (postLink == null) {
            postLink = resolveAbsolute(feedUrl, entry.getUri());
        }
        if (postLink == null) {
            return null;
        }
        String baseUri = siteBase != null ? siteBase : feedUrl;

        String rawHtml = extractRawHtml(entry);
        // Safelist.basicWithImages = basic 白名单 + img[src]，a 自动强制 rel=nofollow；
        // 传入 baseUri 后相对 URL 会被绝对化（无法绝对化且不满足协议白名单的属性被移除）
        String contentHtml = rawHtml == null ? ""
            : Jsoup.clean(rawHtml, postLink, Safelist.basicWithImages());
        String contentText = rawHtml == null ? ""
            : StringUtils.abbreviate(Jsoup.parse(rawHtml).text(), CONTENT_TEXT_MAX_LENGTH);
        List<String> images = extractImages(contentHtml, baseUri);

        var spec = new FriendMoment.FriendMomentSpec();
        spec.setLinkName(link.getMetadata().getName());
        var linkSpec = link.getSpec();
        if (linkSpec != null) {
            spec.setAuthor(StringUtils.defaultIfBlank(linkSpec.getDisplayName(),
                link.getMetadata().getName()));
            spec.setAuthorUrl(linkSpec.getUrl());
            spec.setAuthorBio(linkSpec.getDescription());
            spec.setLogo(linkSpec.getLogo());
        } else {
            spec.setAuthor(link.getMetadata().getName());
        }
        spec.setTitle(cleanTitle(entry.getTitle()));
        spec.setPostLink(postLink);
        spec.setContentHtml(contentHtml);
        spec.setContentText(contentText);
        spec.setImages(images);
        spec.setPubDate(resolvePubDate(entry));
        return spec;
    }

    private String extractRawHtml(SyndEntry entry) {
        SyndContent description = entry.getDescription();
        if (description != null && StringUtils.isNotBlank(description.getValue())) {
            return description.getValue();
        }
        return entry.getContents().stream()
            .map(SyndContent::getValue)
            .filter(StringUtils::isNotBlank)
            .findFirst()
            .orElse(null);
    }

    private List<String> extractImages(String contentHtml, String baseUri) {
        if (StringUtils.isBlank(contentHtml)) {
            return List.of();
        }
        var srcSet = new LinkedHashSet<String>();
        for (var img : Jsoup.parse(contentHtml, baseUri).select("img[src]")) {
            String src = img.absUrl("src");
            if (StringUtils.isNotBlank(src)) {
                srcSet.add(src);
            }
        }
        return List.copyOf(srcSet);
    }

    private String cleanTitle(String title) {
        if (StringUtils.isBlank(title)) {
            return null;
        }
        return StringUtils.trimToNull(Jsoup.parse(title).text());
    }

    /**
     * 发布时间回退链：publishedDate → updatedDate → 抓取时刻；
     * 超过容差的未来时间钳制为抓取时刻，防止霸占时间线顶部。
     */
    private Instant resolvePubDate(SyndEntry entry) {
        var date = entry.getPublishedDate() != null
            ? entry.getPublishedDate() : entry.getUpdatedDate();
        Instant now = Instant.now();
        if (date == null) {
            return now.truncatedTo(ChronoUnit.SECONDS);
        }
        Instant pubDate = date.toInstant();
        if (pubDate.isAfter(now.plus(FUTURE_TOLERANCE))) {
            return now.truncatedTo(ChronoUnit.SECONDS);
        }
        return pubDate;
    }

    private String resolveAbsolute(String base, String maybeRelative) {
        if (StringUtils.isBlank(maybeRelative)) {
            return null;
        }
        try {
            URI resolved = URI.create(base.trim()).resolve(maybeRelative.trim());
            String scheme = resolved.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            return resolved.toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean exists(String postLink) {
        var options = ListOptions.builder()
            .fieldQuery(equal("spec.postLink", postLink))
            .build();
        return !client.listAll(FriendMoment.class, options, Sort.unsorted()).isEmpty();
    }

    /**
     * 每个友链最多保留 retain 条，超出按 pubDate 升序删最旧。
     */
    private int trimToRetain(String linkName, int retain) {
        var options = ListOptions.builder()
            .fieldQuery(equal("spec.linkName", linkName))
            .build();
        List<FriendMoment> all = client.listAll(FriendMoment.class, options,
            Sort.by(Sort.Order.asc("spec.pubDate")));
        int excess = all.size() - retain;
        for (int i = 0; i < excess; i++) {
            client.delete(all.get(i));
        }
        return Math.max(excess, 0);
    }

    private List<Link> listRssLinks() {
        var options = ListOptions.builder()
            .fieldQuery(ExtensionUtil.notDeleting())
            .build();
        return client.listAll(Link.class, options, Sort.by(Sort.Order.asc("metadata.name")))
            .stream()
            .filter(link -> StringUtils.isNotBlank(
                MetadataUtil.nullSafeAnnotations(link).get(FiresideConst.RSS_URL_ANNO)))
            .toList();
    }

    private List<Link> rotateBatch(List<Link> links, int max) {
        if (links.size() <= max) {
            return links;
        }
        int start = Math.floorMod(cursor.getAndAdd(max), links.size());
        var batch = new ArrayList<Link>(max);
        for (int i = 0; i < max; i++) {
            batch.add(links.get((start + i) % links.size()));
        }
        return batch;
    }
}
