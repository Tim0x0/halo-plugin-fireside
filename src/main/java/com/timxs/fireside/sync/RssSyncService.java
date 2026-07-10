package com.timxs.fireside.sync;

import static run.halo.app.extension.index.query.Queries.equal;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.timxs.fireside.FiresideConst;
import com.timxs.fireside.config.SyncConfig;
import com.timxs.fireside.extension.FeedStatus;
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
import java.util.concurrent.locks.ReentrantLock;
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
 * RSS 同步服务：抓取填写了动态 RSS 地址的友链，清洗后落库 FriendMoment，
 * 并把每个友链的同步状态回写到 FeedStatus（供管理页展示）。
 * postLink 去重（首见定格）、每链接保留 retainPerLink 条、pubDate 未来时间钳制、
 * 标记暂停（fireside_paused=true）的友链跳过同步但保留数据。
 * 运行在调度线程/boundedElastic（非 NIO 线程），使用阻塞 ExtensionClient。
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

    /**
     * 防重叠：定时轮与手动触发不并发执行（并发同步同一友链会造成重复入库竞态）。
     */
    private final ReentrantLock syncLock = new ReentrantLock();

    /**
     * 单个友链的同步结果摘要。
     */
    public record LinkSyncResult(String linkName, String displayName, int created, int trimmed,
                                 String error) {
        public boolean success() {
            return error == null;
        }
    }

    /**
     * 定时同步：跳过暂停的友链，每轮最多处理 maxLinksPerRun 个（轮转批次）。
     */
    public List<LinkSyncResult> syncScheduled(SyncConfig config) {
        return runLocked(() -> {
            List<Link> links = listSyncableLinks();
            if (links.isEmpty()) {
                log.debug("[围炉] 没有可同步的友链订阅，跳过本轮");
                return List.of();
            }
            List<Link> batch = rotateBatch(links, config.effectiveMaxLinksPerRun());
            log.info("[围炉] 定时同步开始：共 {} 个订阅，本轮处理 {} 个", links.size(), batch.size());
            return syncBatch(batch, config);
        });
    }

    /**
     * 手动全量同步：跳过暂停的友链，不做轮转与数量限制。
     */
    public List<LinkSyncResult> syncAll(SyncConfig config) {
        return runLocked(() -> {
            List<Link> links = listSyncableLinks();
            if (links.isEmpty()) {
                return List.of();
            }
            log.info("[围炉] 手动全量同步开始：共 {} 个订阅", links.size());
            return syncBatch(links, config);
        });
    }

    /**
     * 手动同步单个友链。
     */
    public LinkSyncResult syncLink(String linkName, SyncConfig config) {
        Link link = client.fetch(Link.class, linkName).orElse(null);
        if (link == null) {
            return new LinkSyncResult(linkName, linkName, 0, 0, "友链不存在");
        }
        String displayName = displayNameOf(link);
        if (StringUtils.isBlank(rssUrlOf(link))) {
            return new LinkSyncResult(linkName, displayName, 0, 0, "未配置动态 RSS 地址");
        }
        if (isPaused(link)) {
            return new LinkSyncResult(linkName, displayName, 0, 0, "该订阅已暂停");
        }
        return runLocked(() -> syncOne(link, config));
    }

    private <T> T runLocked(java.util.function.Supplier<T> action) {
        if (!syncLock.tryLock()) {
            throw new IllegalStateException("已有同步任务正在进行，请稍后再试");
        }
        try {
            return action.get();
        } finally {
            syncLock.unlock();
        }
    }

    private List<LinkSyncResult> syncBatch(List<Link> batch, SyncConfig config) {
        List<LinkSyncResult> results = Flux.fromIterable(batch)
            .flatMap(link -> Mono.fromCallable(() -> syncOne(link, config))
                .subscribeOn(Schedulers.boundedElastic()), CONCURRENCY)
            .collectList()
            .block(ROUND_TIMEOUT);
        return results == null ? List.of() : results;
    }

    /**
     * 同步单个友链：结果与状态都落 FeedStatus，异常不外抛。
     */
    private LinkSyncResult syncOne(Link link, SyncConfig config) {
        String linkName = link.getMetadata().getName();
        String displayName = displayNameOf(link);
        String rssUrl = rssUrlOf(link);
        if (rssUrl == null) {
            return new LinkSyncResult(linkName, displayName, 0, 0, "未配置动态 RSS 地址");
        }
        Instant fetchedAt = Instant.now();
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
            TrimResult trim = trimToRetain(linkName, config.effectiveRetainPerLink());
            upsertFeedStatus(linkName, rssUrl, fetchedAt, null, trim);
            log.info("[围炉] 同步友链 [{}] 完成：新增 {} 条，清理 {} 条", linkName, created,
                trim.trimmed());
            return new LinkSyncResult(linkName, displayName, created, trim.trimmed(), null);
        } catch (Exception e) {
            String error = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
            upsertFeedStatus(linkName, rssUrl, fetchedAt, error, null);
            log.warn("[围炉] 同步友链 [{}] 失败（{}）：{}", linkName, rssUrl, error);
            return new LinkSyncResult(linkName, displayName, 0, 0, error);
        }
    }

    /**
     * 同步状态回写：成功清空错误并归零失败计数，失败保留上次成功信息并累加计数。
     */
    private void upsertFeedStatus(String linkName, String rssUrl, Instant fetchedAt, String error,
        TrimResult trim) {
        try {
            var existing = client.fetch(FeedStatus.class, linkName);
            FeedStatus status = existing.orElseGet(() -> {
                var fs = new FeedStatus();
                var metadata = new Metadata();
                metadata.setName(linkName);
                fs.setMetadata(metadata);
                fs.setSpec(new FeedStatus.FeedStatusSpec());
                return fs;
            });
            if (status.getSpec() == null) {
                status.setSpec(new FeedStatus.FeedStatusSpec());
            }
            var spec = status.getSpec();
            spec.setLinkName(linkName);
            spec.setRssUrl(rssUrl);
            spec.setLastFetchedAt(fetchedAt);
            if (error == null) {
                spec.setLastSuccessAt(fetchedAt);
                spec.setLastError(null);
                spec.setFailureCount(0);
                if (trim != null) {
                    spec.setItemCount(trim.itemCount());
                    spec.setLatestPublishedAt(trim.latestPublishedAt());
                }
            } else {
                spec.setLastError(error);
                int failures = spec.getFailureCount() == null ? 0 : spec.getFailureCount();
                spec.setFailureCount(failures + 1);
            }
            if (existing.isPresent()) {
                client.update(status);
            } else {
                client.create(status);
            }
        } catch (Exception e) {
            // 状态回写失败不影响同步结果本身
            log.warn("[围炉] 回写友链 [{}] 同步状态失败: {}", linkName, e.getMessage());
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
        // 图片从原始 HTML 提取进 images（映射为九宫格 medium），正文清洗用不含 img 的
        // Safelist.basic()（a 自动强制 rel=nofollow、相对 URL 绝对化）——若正文也保留 img，
        // 主题会在正文和九宫格各渲染一次，图片重复
        List<String> images = extractImages(rawHtml, baseUri);
        String contentHtml = rawHtml == null ? ""
            : Jsoup.clean(rawHtml, postLink, Safelist.basic());
        String contentText = rawHtml == null ? ""
            : StringUtils.abbreviate(Jsoup.parse(rawHtml).text(), CONTENT_TEXT_MAX_LENGTH);

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

    /**
     * 从原始 HTML 提取图片地址（absUrl 以 baseUri 绝对化，去重保序）。
     */
    private List<String> extractImages(String rawHtml, String baseUri) {
        if (StringUtils.isBlank(rawHtml)) {
            return List.of();
        }
        var srcSet = new LinkedHashSet<String>();
        for (var img : Jsoup.parse(rawHtml, baseUri).select("img[src]")) {
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

    private record TrimResult(int trimmed, long itemCount, Instant latestPublishedAt) {
    }

    /**
     * 每个友链最多保留 retain 条，超出按 pubDate 升序删最旧；
     * 顺带返回保留条数与最新发布时间供状态回写。
     */
    private TrimResult trimToRetain(String linkName, int retain) {
        var options = ListOptions.builder()
            .fieldQuery(equal("spec.linkName", linkName))
            .build();
        List<FriendMoment> all = client.listAll(FriendMoment.class, options,
            Sort.by(Sort.Order.asc("spec.pubDate")));
        int excess = all.size() - retain;
        for (int i = 0; i < excess; i++) {
            client.delete(all.get(i));
        }
        long itemCount = Math.min(all.size(), retain);
        Instant latest = all.isEmpty() ? null
            : all.get(all.size() - 1).getSpec().getPubDate();
        return new TrimResult(Math.max(excess, 0), itemCount, latest);
    }

    /**
     * 可同步的友链：填写了 RSS 地址且未被标记暂停。
     */
    private List<Link> listSyncableLinks() {
        var options = ListOptions.builder()
            .fieldQuery(ExtensionUtil.notDeleting())
            .build();
        return client.listAll(Link.class, options, Sort.by(Sort.Order.asc("metadata.name")))
            .stream()
            .filter(link -> StringUtils.isNotBlank(rssUrlOf(link)))
            .filter(link -> !isPaused(link))
            .toList();
    }

    private static String rssUrlOf(Link link) {
        return StringUtils.trimToNull(
            MetadataUtil.nullSafeAnnotations(link).get(FiresideConst.RSS_URL_ANNO));
    }

    private static boolean isPaused(Link link) {
        return Boolean.parseBoolean(
            MetadataUtil.nullSafeAnnotations(link).get(FiresideConst.PAUSED_ANNO));
    }

    private static String displayNameOf(Link link) {
        var spec = link.getSpec();
        if (spec != null && StringUtils.isNotBlank(spec.getDisplayName())) {
            return spec.getDisplayName();
        }
        return link.getMetadata().getName();
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
