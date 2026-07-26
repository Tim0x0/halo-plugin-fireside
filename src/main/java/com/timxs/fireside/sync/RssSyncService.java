package com.timxs.fireside.sync;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.timxs.fireside.FiresideConst;
import com.timxs.fireside.config.SyncConfig;
import com.timxs.fireside.extension.FeedStatus;
import com.timxs.fireside.extension.FriendMoment;
import com.timxs.fireside.richlink.RichLinkEnhancer;
import com.timxs.fireside.stub.Link;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
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

    private static final int CONTENT_TEXT_MAX_LENGTH = 500;
    private static final Duration FUTURE_TOLERANCE = Duration.ofDays(1);
    private static final Duration ROUND_TIMEOUT = Duration.ofMinutes(10);

    /**
     * Halo 瞬间 RSS 的机器标题格式固定为「作者 published on yyyy-MM-dd HH:mm:ss」，
     * 与发布时间信息完全重复、无展示价值——匹配则丢弃；真实文章标题不受影响。
     */
    private static final Pattern MACHINE_TITLE =
        Pattern.compile(".+ published on \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$");

    /**
     * 短代码语法：open 匹配起始/自闭合标记，close 匹配闭合标记，两者的捕获组 1 都是标记名。
     */
    private record ShortcodeSyntax(Pattern open, Pattern close) {
    }

    /**
     * 各博客系统的主题短代码只在对方前台渲染，RSS 里是原样文本（如 Typecho 的
     * {cat_gallery}...{/cat_gallery}），入库前需要剥掉。仅当同名起始+闭合标记在正文中
     * 成对出现才删除——孤立的 {config}、[转载] 这类普通文本不会被误伤。
     */
    private static final List<ShortcodeSyntax> SHORTCODE_SYNTAXES = List.of(
        // Typecho 花括号：{cat_gallery} ... {/cat_gallery}
        new ShortcodeSyntax(
            Pattern.compile("\\{([A-Za-z][\\w-]*)(?:\\s[^{}]*)?}"),
            Pattern.compile("\\{/([A-Za-z][\\w-]*)}")),
        // Hugo：{{< gallery >}} ... {{< /gallery >}}
        new ShortcodeSyntax(
            Pattern.compile("\\{\\{<\\s*([A-Za-z][\\w-]*)[^>]*>}}"),
            Pattern.compile("\\{\\{<\\s*/([A-Za-z][\\w-]*)\\s*>}}")),
        // Hexo/Jekyll 的 Liquid 标记：{% codeblock %} ... {% endcodeblock %}
        new ShortcodeSyntax(
            Pattern.compile("\\{%\\s*([A-Za-z][\\w-]*)[^%}]*%}"),
            Pattern.compile("\\{%\\s*end([A-Za-z][\\w-]*)\\s*%}")),
        // WordPress/BBCode 方括号：[caption] ... [/caption]
        new ShortcodeSyntax(
            Pattern.compile("\\[([A-Za-z][\\w-]*)(?:\\s[^\\[\\]]*)?]"),
            Pattern.compile("\\[/([A-Za-z][\\w-]*)]")));

    private final ExtensionClient client;
    private final FeedFetcher feedFetcher;

    /**
     * 轮转游标：友链数超过 maxLinksPerRun 时避免每轮总是处理同一批。
     */
    private final AtomicInteger cursor = new AtomicInteger();

    /**
     * 防重叠：定时轮与手动触发不并发执行（并发同步同一友链会造成重复入库竞态）；
     * 清空动态也共用此锁，本服务内的 FriendMoment 与 FeedStatus 写入天然串行。
     * 注意 FeedStatus 并非严格单写方：Console 隐藏/恢复条目时会直接 PUT 回写
     * itemCount（±1），与本服务并发时由乐观锁兜底，最坏是计数短暂偏差，
     * 下轮同步 trimToRetain 重算修正。
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

    /**
     * 清空已抓取的动态（linkName 为空表示全部友链，含已隐藏），并把对应 FeedStatus
     * 计数归零。与同步共用一把锁：既互斥（清空期间不会有同步往回写），也保持
     * FeedStatus 单一写入方；后端批量执行，避免 Console 逐条 DELETE 的请求风暴
     * （大量订阅时可达数万次）。
     */
    public int clearMoments(String linkName) {
        return runLocked(() -> {
            var builder = ListOptions.builder();
            if (StringUtils.isNotBlank(linkName)) {
                builder.fieldQuery(equal("spec.linkName", linkName));
            }
            List<FriendMoment> items =
                client.listAll(FriendMoment.class, builder.build(), Sort.unsorted());
            int deleted = 0;
            for (FriendMoment item : items) {
                try {
                    client.delete(item);
                    deleted++;
                } catch (Exception e) {
                    // 与管理页并发操作（如隐藏）撞乐观锁的个别条目跳过，可重试清空
                    log.warn("[围炉] 清空条目 [{}] 失败: {}",
                        item.getMetadata().getName(), e.getMessage());
                }
            }
            zeroItemCounts(linkName);
            log.info("[围炉] 清空友链动态：范围 [{}]，删除 {} 条",
                StringUtils.defaultIfBlank(linkName, "全部"), deleted);
            return deleted;
        });
    }

    private void zeroItemCounts(String linkName) {
        try {
            if (StringUtils.isNotBlank(linkName)) {
                client.fetch(FeedStatus.class, linkName).ifPresent(this::zeroItemCount);
                return;
            }
            client.listAll(FeedStatus.class, ListOptions.builder().build(), Sort.unsorted())
                .forEach(this::zeroItemCount);
        } catch (Exception e) {
            log.warn("[围炉] 清空后归零计数失败: {}", e.getMessage());
        }
    }

    private void zeroItemCount(FeedStatus status) {
        var spec = status.getSpec();
        if (spec == null || spec.getItemCount() != null && spec.getItemCount() == 0L) {
            return;
        }
        spec.setItemCount(0L);
        client.update(status);
    }

    private <T> T runLocked(Supplier<T> action) {
        if (!syncLock.tryLock()) {
            throw new SyncInProgressException("已有同步任务正在进行，请稍后再试");
        }
        try {
            return action.get();
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * 是否有同步/清空/级联清理正在持锁执行（LinkReconciler 免锁捷径的判定依据）。
     */
    public boolean isSyncing() {
        return syncLock.isLocked();
    }

    private List<LinkSyncResult> syncBatch(List<Link> batch, SyncConfig config) {
        // 串行逐链同步：友链之间无并发，exists→create 天然原子（不同友链的 feed
        // 携带同一 postLink 也不会重复入库）；单链耗时已由 FeedFetcher 的总预算封顶，
        // 整轮超预算时提前收尾，剩余订阅由下轮轮转补上
        Instant deadline = Instant.now().plus(ROUND_TIMEOUT);
        var results = new ArrayList<LinkSyncResult>(batch.size());
        for (Link link : batch) {
            if (Instant.now().isAfter(deadline)) {
                log.warn("[围炉] 本轮同步时长超过 {} 分钟上限，剩余 {} 个订阅留待下轮",
                    ROUND_TIMEOUT.toMinutes(), batch.size() - results.size());
                break;
            }
            results.add(syncOne(link, config));
        }
        return results;
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
            int retain = config.effectiveRetainPerLink();
            // 发布时间在过滤前一次性解出（datedEntries），排序比较器全程一致；
            // 先按发布时间取最新 retain 条，再做重量级映射（jsoup 解析/清洗）——
            // 超出保留窗口的条目注定被清理，避免"解析几百条只留 20 条"的白费功
            // 与"新增 74 条又删 54 条"的写放大
            List<FriendMoment.FriendMomentSpec> specs = datedEntries(feed, fetchedAt).stream()
                .sorted(Comparator.comparing(DatedEntry::pubDate, Comparator.reverseOrder()))
                .limit(retain)
                .map(dated -> mapEntry(dated.entry(), feed, rssUrl, link, dated.pubDate()))
                .filter(Objects::nonNull)
                .toList();
            int created = 0;
            for (FriendMoment.FriendMomentSpec spec : specs) {
                if (exists(spec.getLinkName(), spec.getPostLink())) {
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
            TrimResult trim = trimToRetain(linkName, retain);
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
     * 服务内同步/清空共用一把锁、写入串行；Console 隐藏/恢复会并发 PUT itemCount，
     * 撞乐观锁时本次回写失败仅记 warn，itemCount 由下轮同步重算，不会漂移；
     * 回写失败不影响同步结果本身。
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
            log.warn("[围炉] 回写友链 [{}] 同步状态失败: {}", linkName, e.getMessage());
        }
    }

    /**
     * 把 RSS 条目映射为 FriendMomentSpec；缺少可用链接（去重键）时返回 null 跳过。
     * pubDate 由调用方一次性解出后传入（{@link #resolvePubDate}）。
     */
    private FriendMoment.FriendMomentSpec mapEntry(SyndEntry entry, SyndFeed feed,
        String feedUrl, Link link, Instant pubDate) {
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
        // 只解析一次 DOM，正文清理 / 媒体提取 / 纯文本摘要共用
        Document doc = null;
        if (StringUtils.isNotBlank(rawHtml)) {
            doc = Jsoup.parse(rawHtml, baseUri);
            // 剔除正文尾部的标签链接（Halo 瞬间 RSS 特征：class 含 tag 且 href 含 tag= 参数，
            // 双条件命中才删，不误伤普通链接）——它们是对方站内的筛选入口，在本站无意义。
            // 不用 [href*=tag=] 属性选择器——值里的 "=" 可能引发解析歧义，两步过滤最稳
            for (var tagLink : doc.select("a.tag")) {
                if (tagLink.attr("href").contains("tag=")) {
                    tagLink.remove();
                }
            }
            stripShortcodes(doc);
        }
        // 媒体（图/视频/音频）从正文 DOM 与 enclosure 附件提取，对齐瞬间的 medium 模型；
        // 正文清洗用不含媒体标签的 Safelist.basic()——否则主题会在正文和九宫格各渲染一次
        List<FriendMoment.MediaItem> media = extractMedia(entry, doc, baseUri);
        if (doc != null) {
            // 已提取进九宫格的媒体标签从 DOM 移除（basic() 反正会剥掉它们）——
            // 这样短代码/标签链接删除后只剩 <br> 的段落才能被下面的清理收掉
            doc.select("img, video, audio").remove();
            // 清理删除节点后残留的段首/段尾 <br> 与空白（否则 prose 排版下呈现为大段空行），
            // 以及由此变空的段落
            for (var block : doc.select("p, div")) {
                trimEdgeBreaks(block);
                if (block.childNodeSize() == 0) {
                    block.remove();
                }
            }
        }
        String contentHtml = doc == null ? ""
            : Jsoup.clean(doc.body().html(), postLink, Safelist.basic());
        // 富链接占位须在 basic() 之后注入（basic 会剥掉自定义 div/data-*）；
        // 真封面由前台调官方 link-detail 填充
        contentHtml = RichLinkEnhancer.enhance(contentHtml);
        String contentText = doc == null ? ""
            : StringUtils.abbreviate(doc.text(), CONTENT_TEXT_MAX_LENGTH);

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
        spec.setMedia(media);
        spec.setPubDate(pubDate);
        return spec;
    }

    /**
     * 删除正文中成对出现的短代码标记（见 {@link #SHORTCODE_SYNTAXES}）。
     * code/pre 内的文本不参与配对统计也不删除——正文里贴的代码片段不会被误伤；
     * 剥掉标记后残留的空白/空段落由调用方的 trimEdgeBreaks 清理收尾。
     * 包内可见以便单测。
     */
    static void stripShortcodes(Document doc) {
        var textNodes = new ArrayList<TextNode>();
        collectTextNodes(doc.body(), textNodes);
        if (textNodes.isEmpty()) {
            return;
        }
        for (ShortcodeSyntax syntax : SHORTCODE_SYNTAXES) {
            var openNames = new HashSet<String>();
            var closeNames = new HashSet<String>();
            for (TextNode node : textNodes) {
                collectNames(syntax.open(), node.getWholeText(), openNames);
                collectNames(syntax.close(), node.getWholeText(), closeNames);
            }
            openNames.retainAll(closeNames);
            if (openNames.isEmpty()) {
                continue;
            }
            for (TextNode node : textNodes) {
                String text = node.getWholeText();
                String stripped = removeMatched(syntax.close(), openNames,
                    removeMatched(syntax.open(), openNames, text));
                if (!stripped.equals(text)) {
                    node.text(stripped);
                }
            }
        }
    }

    /**
     * 深度优先收集文本节点，整棵跳过 code/pre 子树。
     */
    private static void collectTextNodes(Node node, List<TextNode> result) {
        if (node instanceof Element element
            && ("code".equals(element.tagName()) || "pre".equals(element.tagName()))) {
            return;
        }
        for (int i = 0; i < node.childNodeSize(); i++) {
            Node child = node.childNode(i);
            if (child instanceof TextNode textNode) {
                result.add(textNode);
            } else {
                collectTextNodes(child, result);
            }
        }
    }

    private static void collectNames(Pattern pattern, String text, Set<String> names) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
    }

    /**
     * 删除文本中标记名在配对集合内的短代码标记，其余原样保留。
     */
    private static String removeMatched(Pattern pattern, Set<String> pairedNames, String text) {
        Matcher matcher = pattern.matcher(text);
        var sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, pairedNames.contains(matcher.group(1))
                ? "" : Matcher.quoteReplacement(matcher.group()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 去掉块级元素首尾的 <br> 与空白文本节点（删除标签链接后常见的残留）。
     */
    private static void trimEdgeBreaks(Element block) {
        while (block.childNodeSize() > 0 && isBlankOrBreak(block.childNode(0))) {
            block.childNode(0).remove();
        }
        while (block.childNodeSize() > 0
            && isBlankOrBreak(block.childNode(block.childNodeSize() - 1))) {
            block.childNode(block.childNodeSize() - 1).remove();
        }
    }

    private static boolean isBlankOrBreak(Node node) {
        if (node instanceof TextNode textNode) {
            return textNode.isBlank();
        }
        return node instanceof Element element
            && "br".equals(element.tagName());
    }

    /**
     * 取出条目 HTML：description 与 contents（含 content:encoded）都可能有值。
     * 博客 RSS 常见「description=摘要、contents=全文」——取更长的一份作正文/抽媒体，
     * 避免只存摘要导致丢全文与配图；瞬间类源通常全文只在一侧，结果不变。
     */
    private String extractRawHtml(SyndEntry entry) {
        String description = null;
        SyndContent desc = entry.getDescription();
        if (desc != null && StringUtils.isNotBlank(desc.getValue())) {
            description = desc.getValue();
        }
        String content = entry.getContents().stream()
            .map(SyndContent::getValue)
            .filter(StringUtils::isNotBlank)
            .findFirst()
            .orElse(null);
        if (description == null) {
            return content;
        }
        if (content == null) {
            return description;
        }
        // 长度接近时优先 contents（全文槽位）；明显更长的一侧胜出
        return content.length() >= description.length() ? content : description;
    }


    /**
     * 提取条目媒体（对齐瞬间的 medium 模型）：
     * 正文 DOM 的 img/video/audio 标签（absUrl 绝对化，跳过追踪像素），
     * 以及 RSS enclosure 附件（按 MIME 类型归类），按出现顺序去重。
     */
    private List<FriendMoment.MediaItem> extractMedia(SyndEntry entry, Document doc,
        String baseUri) {
        var items = new ArrayList<FriendMoment.MediaItem>();
        var seen = new LinkedHashSet<String>();
        if (doc != null) {
            for (var img : doc.select("img[src]")) {
                String src = img.absUrl("src");
                if (isTrackingPixel(img, src)) {
                    continue;
                }
                addMedia(items, seen, "PHOTO", src);
            }
            for (var video : doc.select("video[src], video > source[src]")) {
                addMedia(items, seen, "VIDEO", video.absUrl("src"));
            }
            for (var audio : doc.select("audio[src], audio > source[src]")) {
                addMedia(items, seen, "AUDIO", audio.absUrl("src"));
            }
        }
        for (SyndEnclosure enclosure : entry.getEnclosures()) {
            String url = resolveAbsolute(baseUri, enclosure.getUrl());
            if (url == null) {
                continue;
            }
            String mime = StringUtils.defaultString(enclosure.getType());
            if (mime.startsWith("image/")) {
                addMedia(items, seen, "PHOTO", url);
            } else if (mime.startsWith("video/")) {
                addMedia(items, seen, "VIDEO", url);
            } else if (mime.startsWith("audio/")) {
                addMedia(items, seen, "AUDIO", url);
            }
        }
        return items;
    }

    /**
     * 媒体入库唯一通道，http/https 白名单在此收口：jsoup 的 absUrl 对 Java 不认识的
     * 协议（javascript:/data: 等）会把属性值原样返回，而主题可能把媒体地址放进
     * a[href]（灯箱），不过滤即成存储型 XSS；将来新增提取点自动被本检查覆盖。
     */
    private static void addMedia(List<FriendMoment.MediaItem> items, LinkedHashSet<String> seen,
        String type, String url) {
        if (!HttpUrls.isHttp(url) || !seen.add(url)) {
            return;
        }
        var item = new FriendMoment.MediaItem();
        item.setType(type);
        item.setUrl(url);
        items.add(item);
    }

    /**
     * 追踪像素判定：声明 1×1/0 尺寸的图（业界追踪像素惯例），
     * 或 Halo RSS 插件的阅读统计图（/plugins/feed/assets/telemetry.gif）。
     */
    private static boolean isTrackingPixel(Element img, String src) {
        String width = img.attr("width").trim();
        String height = img.attr("height").trim();
        if ("0".equals(width) || "1".equals(width) || "0".equals(height) || "1".equals(height)) {
            return true;
        }
        return src.contains("/plugins/feed/assets/telemetry.gif");
    }

    private String cleanTitle(String title) {
        if (StringUtils.isBlank(title)) {
            return null;
        }
        String text = StringUtils.trimToNull(Jsoup.parse(title).text());
        if (text != null && MACHINE_TITLE.matcher(text).matches()) {
            return null;
        }
        return text;
    }

    /**
     * 条目与其解析后发布时间的配对：时间只解析一次，排序与入库共用。
     */
    private record DatedEntry(SyndEntry entry, Instant pubDate) {
    }

    /**
     * 过滤掉没有可用链接（去重键）的条目，并按 feed 位次为每条解出发布时间。
     */
    private static List<DatedEntry> datedEntries(SyndFeed feed, Instant fetchedAt) {
        List<SyndEntry> entries = feed.getEntries();
        var dated = new ArrayList<DatedEntry>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            SyndEntry entry = entries.get(i);
            if (StringUtils.isBlank(entry.getLink()) && StringUtils.isBlank(entry.getUri())) {
                continue;
            }
            dated.add(new DatedEntry(entry, resolvePubDate(entry, fetchedAt, i)));
        }
        return dated;
    }

    /**
     * 发布时间回退链：publishedDate → updatedDate → 按 feed 位次从抓取时刻逐毫秒递减；
     * 超过容差的未来时间同样按位次钳制，防止霸占时间线顶部。
     * 无日期条目不共享同一时间戳——游标分页对同刻并列的兜底窗口只有 2*size 条，
     * 整源同刻会让更早的并列条目永远翻不到；逐毫秒递减既消除并列，
     * 又保留 feed 原始顺序（靠前的条目视为更新）。包内可见以便单测。
     */
    static Instant resolvePubDate(SyndEntry entry, Instant fetchedAt, int feedIndex) {
        var date = entry.getPublishedDate() != null
            ? entry.getPublishedDate() : entry.getUpdatedDate();
        if (date == null) {
            return fetchedAt.minusMillis(feedIndex);
        }
        Instant pubDate = date.toInstant();
        if (pubDate.isAfter(fetchedAt.plus(FUTURE_TOLERANCE))) {
            return fetchedAt.minusMillis(feedIndex);
        }
        return pubDate;
    }

    private String resolveAbsolute(String base, String maybeRelative) {
        if (StringUtils.isBlank(maybeRelative)) {
            return null;
        }
        try {
            URI resolved = URI.create(base.trim()).resolve(maybeRelative.trim());
            if (!HttpUrls.isHttpScheme(resolved.getScheme())) {
                return null;
            }
            return resolved.toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 去重限定在本友链范围内：postLink 若全局唯一，恶意 feed 可抢注他人文章 URL，
     * 导致真实条目无法入库且署名错误。
     */
    private boolean exists(String linkName, String postLink) {
        var options = ListOptions.builder()
            .fieldQuery(and(equal("spec.linkName", linkName),
                equal("spec.postLink", postLink)))
            .build();
        return !client.listAll(FriendMoment.class, options, Sort.unsorted()).isEmpty();
    }

    private record TrimResult(int trimmed, long itemCount, Instant latestPublishedAt) {
    }

    /**
     * 每个友链的可见条目最多保留 retain 条，超出按 pubDate 升序删最旧；
     * 隐藏条目不参与清理——用户手动标记的条目豁免保留策略（官方链接插件 retention
     * 的 unsavedFilter 同款原则：favorite/readLater 条目不被清理），且它们是
     * postLink 去重的墓碑，被清掉后若同条目仍在 feed 窗口内会以可见状态复活；
     * 随取消订阅的级联清理删除。顺带返回保留条数与最新发布时间供状态回写。
     */
    private TrimResult trimToRetain(String linkName, int retain) {
        var options = ListOptions.builder()
            .fieldQuery(equal("spec.linkName", linkName))
            .build();
        List<FriendMoment> all = client.listAll(FriendMoment.class, options,
            Sort.by(Sort.Order.asc("spec.pubDate")));
        List<FriendMoment> visible = all.stream()
            .filter(fm -> fm.getSpec() == null
                || !Boolean.TRUE.equals(fm.getSpec().getHidden()))
            .toList();
        int excess = visible.size() - retain;
        int trimmed = 0;
        for (int i = 0; i < excess; i++) {
            String name = visible.get(i).getMetadata().getName();
            try {
                // 删除前取最新版本，避免与其它写入撞乐观锁；失败留给下轮清理。
                // 仅在确实执行 delete 时计入 trimmed（条目已被并发删掉则不加）
                var latest = client.fetch(FriendMoment.class, name);
                if (latest.isPresent()) {
                    client.delete(latest.get());
                    trimmed++;
                }
            } catch (OptimisticLockingFailureException e) {
                log.debug("[围炉] 清理条目 [{}] 版本冲突，留待下轮", name);
            } catch (Exception e) {
                log.warn("[围炉] 清理条目 [{}] 失败: {}", name, e.getMessage());
            }
        }
        // itemCount 只计可见条目（隐藏是去重墓碑，不计入侧边栏「条数」）
        long itemCount = (long) visible.size() - trimmed;
        Instant latestPub = all.isEmpty() ? null
            : all.get(all.size() - 1).getSpec().getPubDate();
        return new TrimResult(trimmed, itemCount, latestPub);
    }

    /**
     * 友链删除或取消订阅时的级联清理：删该链全部 FriendMoment 与 FeedStatus。
     * 与同步/清空共用一把锁，避免「同步 create 与级联 delete」交错留下孤儿数据。
     * 调用方若收到 {@link SyncInProgressException} 应稍后重试。
     */
    public void cleanupLinkSubscription(String linkName) {
        runLocked(() -> {
            var options = ListOptions.builder()
                .fieldQuery(equal("spec.linkName", linkName))
                .build();
            List<FriendMoment> items =
                client.listAll(FriendMoment.class, options, Sort.unsorted());
            for (FriendMoment item : items) {
                try {
                    client.delete(item);
                } catch (Exception e) {
                    log.warn("[围炉] 级联清理条目 [{}] 失败: {}",
                        item.getMetadata().getName(), e.getMessage());
                }
            }
            var status = client.fetch(FeedStatus.class, linkName);
            status.ifPresent(fs -> {
                try {
                    client.delete(fs);
                } catch (Exception e) {
                    log.warn("[围炉] 级联清理同步状态 [{}] 失败: {}", linkName, e.getMessage());
                }
            });
            if (items.isEmpty() && status.isEmpty()) {
                // 无数据空转（Reconciler 在同步进行中保守放进来的场景），不值一条 INFO
                log.debug("[围炉] 友链 [{}] 无围炉数据，级联清理空转", linkName);
            } else {
                log.info("[围炉] 友链 [{}] 已删除或取消订阅，级联清理 {} 条动态",
                    linkName, items.size());
            }
            return null;
        });
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
