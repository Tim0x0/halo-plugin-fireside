package com.timxs.fireside.finders.impl;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.isNull;

import com.timxs.fireside.FiresideConst;
import com.timxs.fireside.config.DisplayConfig;
import com.timxs.fireside.extension.FriendMoment;
import com.timxs.fireside.stub.Moment;
import com.timxs.fireside.vo.FiresideMomentVo;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Counter;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.app.theme.finders.Finder;
import run.halo.app.theme.router.UrlContextListResult;

/**
 * 聚合 Finder（插件唯一对外出口）：
 * 自身瞬间实时直读 Moment CR（无缓存），友链条目读定时同步落库的 FriendMoment，
 * 内存归并按发布时间倒序，返回形状与瞬间插件传给模板的 moments 变量一致。
 */
@Finder("firesideFinder")
@RequiredArgsConstructor
public class FiresideFinderImpl {

    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 100;
    private static final String MOMENTS_PATH = "/moments";

    private static final GroupVersionKind MOMENT_GVK = GroupVersionKind.fromExtension(Moment.class);

    private static final Sort SELF_SORT =
        Sort.by(Sort.Order.desc("spec.releaseTime")).and(ExtensionUtil.defaultSort());
    private static final Sort FRIEND_SORT =
        Sort.by(Sort.Order.desc("spec.pubDate")).and(ExtensionUtil.defaultSort());

    private final ReactiveExtensionClient client;
    private final SchemeManager schemeManager;
    private final ReactiveSettingFetcher settingFetcher;

    /**
     * 混排时间线：自身瞬间 + 友链条目，按发布时间倒序归并分页。
     */
    public Mono<UrlContextListResult<FiresideMomentVo>> timeline(Integer page, Integer size) {
        return timeline(page, size, null);
    }

    /**
     * tag 重载：主题使用 ?tag= 过滤时传入。
     * tag 等于设置中的「友链动态标签」时仅返回友链条目；
     * tag 为其它非空值时仅返回自身瞬间（按本站标签过滤）。
     */
    public Mono<UrlContextListResult<FiresideMomentVo>> timeline(Integer page, Integer size,
        String tag) {
        int p = pageNullSafe(page);
        int s = sizeNullSafe(size);
        // 两源各取前 page*size 条，内存归并后截取当前页窗口
        int window = p * s;
        return displayConfig().flatMap(display -> {
            String friendTag = display.effectiveFriendTag();
            boolean friendOnly = StringUtils.isNotBlank(tag) && tag.equals(friendTag);
            boolean selfOnly = StringUtils.isNotBlank(tag) && !friendOnly;
            var selfMono = friendOnly
                ? Mono.just(new ListResult<Moment>(1, window, 0L, List.of()))
                : listSelfTop(window, selfOnly ? tag : null);
            var friendMono = selfOnly
                ? Mono.just(new ListResult<FriendMoment>(1, window, 0L, List.of()))
                : client.listBy(FriendMoment.class, friendOptions(null),
                    PageRequestImpl.of(1, window, FRIEND_SORT));
            return Mono.zip(selfMono, friendMono)
                .flatMap(tuple ->
                    assembleTimeline(tuple.getT1(), tuple.getT2(), p, s, tag, friendTag));
        });
    }

    /**
     * 仅友链条目，不含自身瞬间；linkName 可选，用于按某个友链过滤。
     */
    public Mono<ListResult<FiresideMomentVo>> listFriends(Integer page, Integer size) {
        return listFriends(page, size, null);
    }

    public Mono<ListResult<FiresideMomentVo>> listFriends(Integer page, Integer size,
        String linkName) {
        int p = pageNullSafe(page);
        int s = sizeNullSafe(size);
        return displayConfig().flatMap(display -> client.listBy(FriendMoment.class,
                friendOptions(linkName), PageRequestImpl.of(p, s, FRIEND_SORT))
            .map(list -> new ListResult<>(list.getPage(), list.getSize(), list.getTotal(),
                list.getItems().stream()
                    .map(fm -> friendVo(fm, display.effectiveFriendTag()))
                    .toList())));
    }

    /**
     * 自身瞬间：PluginMoments 未安装/未启用时 scheme 不存在，SELF 部分返回空（可选依赖不崩）。
     * 公开可见性过滤复刻瞬间插件匿名口径：未删除 && approved && PUBLIC。
     */
    private Mono<ListResult<Moment>> listSelfTop(int window, String tag) {
        if (schemeManager.fetch(MOMENT_GVK).isEmpty()) {
            return Mono.just(new ListResult<>(1, window, 0L, List.of()));
        }
        var query = and(
            isNull("metadata.deletionTimestamp"),
            equal("spec.approved", Boolean.TRUE),
            equal("spec.visible", Moment.MomentVisible.PUBLIC.name())
        );
        if (StringUtils.isNotBlank(tag)) {
            query = and(query, equal("spec.tags", tag));
        }
        var options = ListOptions.builder().fieldQuery(query).build();
        return client.listBy(Moment.class, options, PageRequestImpl.of(1, window, SELF_SORT));
    }

    private Mono<UrlContextListResult<FiresideMomentVo>> assembleTimeline(
        ListResult<Moment> self, ListResult<FriendMoment> friends, int page, int size,
        String tag, String friendTag) {
        long total = self.getTotal() + friends.getTotal();
        List<TimelineItem> merged = mergeByTimeDesc(self.getItems(), friends.getItems());

        int fromIndex = (page - 1) * size;
        int toIndex = Math.min(page * size, merged.size());
        List<TimelineItem> pageItems = fromIndex >= merged.size()
            ? List.of() : merged.subList(fromIndex, toIndex);

        return preloadUsers(pageItems)
            .flatMap(users -> Flux.fromIterable(pageItems)
                .concatMap(item -> item.self() != null
                    ? selfVo(item.self(), users)
                    : Mono.just(friendVo(item.friend(), friendTag)))
                .collectList())
            .map(vos -> new UrlContextListResult.Builder<FiresideMomentVo>()
                .listResult(new ListResult<>(page, size, total, vos))
                .prevUrl(pageUrl(Math.max(page - 1, 1), tag))
                .nextUrl(pageUrl(Math.min(page + 1, Math.max(totalPages(total, size), 1)), tag))
                .build());
    }

    /**
     * 双指针归并两个已按时间倒序的列表。
     */
    private List<TimelineItem> mergeByTimeDesc(List<Moment> selfItems,
        List<FriendMoment> friendItems) {
        var merged = new ArrayList<TimelineItem>(selfItems.size() + friendItems.size());
        int i = 0;
        int j = 0;
        while (i < selfItems.size() || j < friendItems.size()) {
            if (j >= friendItems.size()) {
                merged.add(TimelineItem.of(selfItems.get(i++), null));
            } else if (i >= selfItems.size()) {
                merged.add(TimelineItem.of(null, friendItems.get(j++)));
            } else if (!selfTime(selfItems.get(i)).isBefore(friendTime(friendItems.get(j)))) {
                merged.add(TimelineItem.of(selfItems.get(i++), null));
            } else {
                merged.add(TimelineItem.of(null, friendItems.get(j++)));
            }
        }
        return merged;
    }

    /**
     * SELF 条目的作者按 owner 去重后批量预载，避免逐条 N+1。
     */
    private Mono<Map<String, User>> preloadUsers(List<TimelineItem> pageItems) {
        var owners = pageItems.stream()
            .map(TimelineItem::self)
            .filter(java.util.Objects::nonNull)
            .map(moment -> moment.getSpec() == null ? null : moment.getSpec().getOwner())
            .filter(StringUtils::isNotBlank)
            .distinct()
            .toList();
        return Flux.fromIterable(owners)
            .flatMap(owner -> client.fetch(User.class, owner)
                .map(user -> Map.entry(owner, user)))
            .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private Mono<FiresideMomentVo> selfVo(Moment moment, Map<String, User> users) {
        var metadata = moment.getMetadata();
        var momentSpec = moment.getSpec();
        var spec = FiresideMomentVo.SpecVo.builder()
            .content(selfContent(momentSpec))
            .releaseTime(momentSpec.getReleaseTime() != null
                ? momentSpec.getReleaseTime() : metadata.getCreationTimestamp())
            .visible(FiresideMomentVo.Visible.PUBLIC)
            .owner(momentSpec.getOwner())
            .tags(momentSpec.getTags() == null ? Set.of() : momentSpec.getTags())
            .approved(true)
            .approvedTime(momentSpec.getApprovedTime())
            .build();
        var owner = ownerVo(users.get(momentSpec.getOwner()), momentSpec.getOwner());
        return counterStats(metadata.getName())
            .map(stats -> FiresideMomentVo.builder()
                .metadata(metadata)
                .spec(spec)
                .owner(owner)
                .stats(stats)
                .external(false)
                .sourceUrl(MOMENTS_PATH + "/" + metadata.getName())
                .build());
    }

    private FiresideMomentVo.ContentVo selfContent(Moment.MomentSpec momentSpec) {
        var content = momentSpec.getContent();
        if (content == null) {
            return emptyContent();
        }
        var medium = content.getMedium() == null ? List.<FiresideMomentVo.MediaVo>of()
            : content.getMedium().stream()
                .map(media -> FiresideMomentVo.MediaVo.builder()
                    .type(media.getType() == null ? FiresideMomentVo.MediaType.PHOTO
                        : FiresideMomentVo.MediaType.valueOf(media.getType().name()))
                    .url(media.getUrl())
                    .originType(media.getOriginType())
                    .build())
                .toList();
        return FiresideMomentVo.ContentVo.builder()
            .raw(StringUtils.defaultString(content.getRaw()))
            .html(StringUtils.defaultString(content.getHtml()))
            .medium(medium)
            .build();
    }

    private FiresideMomentVo.ContentVo emptyContent() {
        return FiresideMomentVo.ContentVo.builder()
            .raw("")
            .html("")
            .medium(List.of())
            .build();
    }

    private FiresideMomentVo friendVo(FriendMoment friendMoment, String friendTag) {
        var spec = friendMoment.getSpec();
        var medium = spec.getImages() == null ? List.<FiresideMomentVo.MediaVo>of()
            : spec.getImages().stream()
                .map(url -> FiresideMomentVo.MediaVo.builder()
                    .type(FiresideMomentVo.MediaType.PHOTO)
                    .url(url)
                    .build())
                .toList();
        var specVo = FiresideMomentVo.SpecVo.builder()
            .content(FiresideMomentVo.ContentVo.builder()
                .raw(StringUtils.defaultString(spec.getContentText()))
                .html(StringUtils.defaultString(spec.getContentHtml()))
                .medium(medium)
                .build())
            .releaseTime(spec.getPubDate() != null
                ? spec.getPubDate() : friendMoment.getMetadata().getCreationTimestamp())
            .visible(FiresideMomentVo.Visible.PUBLIC)
            .owner(spec.getLinkName())
            .tags(StringUtils.isBlank(friendTag) ? Set.of() : Set.of(friendTag))
            .approved(true)
            .build();
        var owner = FiresideMomentVo.OwnerVo.builder()
            .name(spec.getLinkName())
            .displayName(StringUtils.defaultIfBlank(spec.getAuthor(), spec.getLinkName()))
            .avatar(spec.getLogo())
            .bio(spec.getAuthorBio())
            .build();
        return FiresideMomentVo.builder()
            .metadata(friendMoment.getMetadata())
            .spec(specVo)
            .owner(owner)
            .stats(FiresideMomentVo.StatsVo.empty())
            .external(true)
            .sourceUrl(spec.getPostLink())
            .authorUrl(spec.getAuthorUrl())
            .title(spec.getTitle())
            .build();
    }

    private FiresideMomentVo.OwnerVo ownerVo(User user, String fallbackName) {
        if (user == null || user.getSpec() == null) {
            return FiresideMomentVo.OwnerVo.builder()
                .name(fallbackName)
                .displayName(fallbackName)
                .build();
        }
        return FiresideMomentVo.OwnerVo.builder()
            .name(user.getMetadata().getName())
            .displayName(StringUtils.defaultIfBlank(user.getSpec().getDisplayName(),
                fallbackName))
            .avatar(user.getSpec().getAvatar())
            .bio(user.getSpec().getBio())
            .build();
    }

    private Mono<FiresideMomentVo.StatsVo> counterStats(String momentName) {
        return client.fetch(Counter.class, FiresideConst.MOMENT_COUNTER_PREFIX + momentName)
            .map(counter -> FiresideMomentVo.StatsVo.builder()
                .upvote(counter.getUpvote())
                .totalComment(counter.getTotalComment())
                .approvedComment(counter.getApprovedComment())
                .build())
            .defaultIfEmpty(FiresideMomentVo.StatsVo.empty());
    }

    private Mono<DisplayConfig> displayConfig() {
        return settingFetcher.fetch(DisplayConfig.GROUP, DisplayConfig.class)
            .defaultIfEmpty(new DisplayConfig())
            .onErrorReturn(new DisplayConfig());
    }

    private ListOptions friendOptions(String linkName) {
        var query = isNull("metadata.deletionTimestamp");
        if (StringUtils.isNotBlank(linkName)) {
            query = and(query, equal("spec.linkName", linkName));
        }
        return ListOptions.builder().fieldQuery(query).build();
    }

    /**
     * prevUrl/nextUrl 按瞬间插件写死的路由规则构建：/moments 与 /moments/page/N，
     * 页数按混排后的 total 计算；tag 场景附加 ?tag= 参数。
     */
    private String pageUrl(int page, String tag) {
        String path = page <= 1 ? MOMENTS_PATH : MOMENTS_PATH + "/page/" + page;
        if (StringUtils.isNotBlank(tag)) {
            path += "?tag=" + UriUtils.encode(tag, StandardCharsets.UTF_8);
        }
        return path;
    }

    private static int totalPages(long total, int size) {
        return (int) ((total + size - 1) / size);
    }

    private static int pageNullSafe(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private static int sizeNullSafe(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private static Instant selfTime(Moment moment) {
        if (moment.getSpec() != null && moment.getSpec().getReleaseTime() != null) {
            return moment.getSpec().getReleaseTime();
        }
        return moment.getMetadata().getCreationTimestamp();
    }

    private static Instant friendTime(FriendMoment friendMoment) {
        if (friendMoment.getSpec() != null && friendMoment.getSpec().getPubDate() != null) {
            return friendMoment.getSpec().getPubDate();
        }
        return friendMoment.getMetadata().getCreationTimestamp();
    }

    /**
     * 归并中间结构：self 与 friend 恰有一个非 null。
     */
    private record TimelineItem(Moment self, FriendMoment friend) {
        static TimelineItem of(Moment self, FriendMoment friend) {
            return new TimelineItem(self, friend);
        }
    }
}
