package com.timxs.fireside.finders.impl;

import static run.halo.app.extension.index.query.Queries.all;
import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.isNull;
import static run.halo.app.extension.index.query.Queries.lessThan;
import static run.halo.app.extension.index.query.Queries.or;

import com.timxs.fireside.FiresideConst;
import com.timxs.fireside.config.DisplayConfig;
import com.timxs.fireside.extension.FriendMoment;
import com.timxs.fireside.richlink.RichLinkEnhancer;
import com.timxs.fireside.stub.Link;
import com.timxs.fireside.stub.Moment;
import com.timxs.fireside.vo.FiresideMomentVo;
import com.timxs.fireside.vo.FiresideTagVo;
import com.timxs.fireside.vo.FiresideTimelineSlice;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Counter;
import run.halo.app.core.extension.User;
import run.halo.app.core.user.service.UserService;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.query.Condition;
import run.halo.app.infra.AnonymousUserConst;
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

    /**
     * 页码上限：防止恶意大页码造成 page+1 溢出等退化行为；
     * 混排窗口受 PageRequestImpl 的 1000 硬上限约束，深页本就不可达。
     */
    private static final int MAX_PAGE = 100_000;
    private static final String MOMENTS_PATH = "/moments";

    private static final GroupVersionKind MOMENT_GVK = GroupVersionKind.fromExtension(Moment.class);

    private static final Sort SELF_SORT =
        Sort.by(Sort.Order.desc("spec.releaseTime")).and(ExtensionUtil.defaultSort());
    private static final Sort FRIEND_SORT =
        Sort.by(Sort.Order.desc("spec.pubDate")).and(ExtensionUtil.defaultSort());

    private final ReactiveExtensionClient client;
    private final SchemeManager schemeManager;
    private final ReactiveSettingFetcher settingFetcher;
    private final UserService userService;

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
        // 两源各取前 page*size 条，内存归并后截取当前页窗口。
        // 用 long 计算防 int 溢出；Halo 的 PageRequestImpl 对 size 有 1000 硬上限，
        // 超限会被静默钳制导致深页排序失真，这里显式钳到同一上限，超出窗口的页返回空
        int window = (int) Math.min((long) p * s, PageRequestImpl.MAX_SIZE);
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
                    assembleTimeline(tuple.getT1(), tuple.getT2(), p, s, window, tag, friendTag));
        });
    }

    /**
     * 游标式时间线（内置 /fireside 页分页用）：返回发布时间不晚于游标位置的
     * 下一片 size 条。beforeCursor 为空/非法时视为第一页。
     * 与页码窗口方案不同：任意翻页深度每次各源只查约 2*size 条，
     * 不受单查询 1000 条上限约束。tag 语义与 timeline(page, size, tag) 一致。
     */
    public Mono<FiresideTimelineSlice> timelineSlice(String beforeCursor, Integer size,
        String tag) {
        int s = sizeNullSafe(size);
        TimelineCursor cursor = TimelineCursor.parse(beforeCursor);
        // 各源取 2*size+1：一页量出结果、一页量抵御游标边界的同毫秒并列剔除、
        // +1 供 hasNext 判定；超过 2*size 条同毫秒并列属病态数据，不再兜底
        int window = s * 2 + 1;
        return displayConfig().flatMap(display -> {
            String friendTag = display.effectiveFriendTag();
            boolean friendOnly = StringUtils.isNotBlank(tag) && tag.equals(friendTag);
            boolean selfOnly = StringUtils.isNotBlank(tag) && !friendOnly;
            var selfMono = friendOnly
                ? Mono.just(new ListResult<Moment>(1, window, 0L, List.of()))
                : listSelfTop(window, selfOnly ? tag : null, cursor);
            var friendMono = selfOnly
                ? Mono.just(new ListResult<FriendMoment>(1, window, 0L, List.of()))
                : client.listBy(FriendMoment.class, friendOptions(null, cursor),
                    PageRequestImpl.of(1, window, FRIEND_SORT));
            return Mono.zip(selfMono, friendMono).flatMap(tuple ->
                assembleSlice(tuple.getT1(), tuple.getT2(), cursor, s, friendTag));
        });
    }

    private Mono<FiresideTimelineSlice> assembleSlice(ListResult<Moment> self,
        ListResult<FriendMoment> friends, TimelineCursor cursor, int size, String friendTag) {
        // spec 为 null 的脏数据逐条跳过，同 assembleTimeline
        List<TimelineItem> merged = mergeByTimeDesc(
            self.getItems().stream().filter(m -> m.getSpec() != null).toList(),
            friends.getItems().stream().filter(fm -> fm.getSpec() != null).toList());
        List<TimelineItem> after = dropUntilAfterCursor(merged, cursor);
        boolean hasNext = after.size() > size;
        List<TimelineItem> pageItems = after.subList(0, Math.min(size, after.size()));
        String nextCursor = hasNext && !pageItems.isEmpty()
            ? cursorOf(pageItems.get(pageItems.size() - 1)) : null;
        return toVos(pageItems, friendTag)
            .map(vos -> FiresideTimelineSlice.builder()
                .items(vos)
                .hasNext(nextCursor != null)
                .nextCursor(nextCursor)
                .build());
    }

    /**
     * 剔除游标位置及其之前的条目。优先按（来源, name）精确定位游标条目
     * （查询用 <= 把同毫秒并列一并取回，因此边界不丢条目）；
     * 游标条目已被删除/隐藏的罕见场景退化为按时间严格截断，
     * 此时与游标同毫秒的未展示条目会被跳过，属可接受损失。
     */
    private List<TimelineItem> dropUntilAfterCursor(List<TimelineItem> merged,
        TimelineCursor cursor) {
        if (cursor == null) {
            return merged;
        }
        for (int i = 0; i < merged.size(); i++) {
            if (cursor.matches(merged.get(i))) {
                return merged.subList(i + 1, merged.size());
            }
        }
        for (int i = 0; i < merged.size(); i++) {
            if (itemTime(merged.get(i)).isBefore(cursor.time())) {
                return merged.subList(i, merged.size());
            }
        }
        return List.of();
    }

    private static Instant itemTime(TimelineItem item) {
        return item.self() != null ? selfTime(item.self()) : friendTime(item.friend());
    }

    private static String cursorOf(TimelineItem item) {
        boolean external = item.friend() != null;
        String name = external
            ? item.friend().getMetadata().getName()
            : item.self().getMetadata().getName();
        return itemTime(item) + "|" + (external ? "f" : "s") + "|" + name;
    }

    /**
     * 游标：上一页最后一条的（发布时间, 来源, name）。
     * 编码为 "ISO时间|s或f|条目name"，URL 层负责转义。
     */
    private record TimelineCursor(Instant time, boolean external, String name) {

        static TimelineCursor parse(String raw) {
            if (StringUtils.isBlank(raw)) {
                return null;
            }
            String[] parts = raw.split("\\|", 3);
            if (parts.length != 3 || StringUtils.isBlank(parts[2])) {
                return null;
            }
            try {
                return new TimelineCursor(Instant.parse(parts[0]), "f".equals(parts[1]),
                    parts[2]);
            } catch (DateTimeParseException e) {
                // 手改 URL 等非法游标当第一页处理，不报错
                return null;
            }
        }

        boolean matches(TimelineItem item) {
            if (external != (item.friend() != null)) {
                return false;
            }
            String itemName = external
                ? item.friend().getMetadata().getName()
                : item.self().getMetadata().getName();
            return name.equals(itemName);
        }
    }

    /**
     * 单条自身瞬间（内置详情页 /fireside/{name} 用）：
     * 可见性与 timeline 完全同口径（selfVisibilityQuery），
     * 未命中、不可见或瞬间插件未装均返回空，由调用方转 404。
     */
    public Mono<FiresideMomentVo> get(String name) {
        if (StringUtils.isBlank(name) || schemeManager.fetch(MOMENT_GVK).isEmpty()) {
            return Mono.empty();
        }
        return selfVisibilityQuery()
            .flatMap(query -> {
                var options = ListOptions.builder()
                    .fieldQuery(and(query, equal("metadata.name", name)))
                    .build();
                return client.listBy(Moment.class, options,
                    PageRequestImpl.of(1, 1, SELF_SORT));
            })
            .flatMap(list -> list.getItems().isEmpty()
                ? Mono.empty()
                : selfVoSingle(list.getItems().get(0)));
    }

    /**
     * 单条 Moment 转 VO：预载该条作者后走 selfVo，与时间线组装同一路径。
     */
    private Mono<FiresideMomentVo> selfVoSingle(Moment moment) {
        if (moment.getSpec() == null) {
            return Mono.empty();
        }
        String owner = moment.getSpec().getOwner();
        Mono<Map<String, User>> users = StringUtils.isBlank(owner)
            ? Mono.just(Map.of())
            : userService.getUserOrGhost(owner).map(user -> Map.of(owner, user));
        return users.flatMap(map -> selfVo(moment, map));
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
            .flatMap(list -> preloadLinks(list.getItems())
                .map(links -> new ListResult<>(list.getPage(), list.getSize(), list.getTotal(),
                    list.getItems().stream()
                        // 脏数据（spec=null）跳过不炸页，同 assembleTimeline
                        .filter(fm -> fm.getSpec() != null)
                        .map(fm -> friendVo(fm, display.effectiveFriendTag(),
                            links.get(linkNameOf(fm))))
                        .toList()))));
    }

    /**
     * 统一标签栏：自身瞬间的全部标签及数量（统计逻辑复刻瞬间插件的 listAllTags）
     * + 末尾一项友链标签（数量为友链条目总数，permalink 按当前配置值生成，避免主题硬编码）。
     * 返回项与瞬间的 MomentTagVo 同形状，主题标签循环可直接换数据源。
     */
    public Flux<FiresideTagVo> listAllTags() {
        return displayConfig().flatMapMany(display -> {
            String friendTag = display.effectiveFriendTag();
            var friendItem = client.listBy(FriendMoment.class, friendOptions(null),
                    PageRequestImpl.of(1, 1, FRIEND_SORT))
                .map(ListResult::getTotal)
                .filter(total -> total > 0)
                .map(total -> FiresideTagVo.builder()
                    .name(friendTag)
                    .momentCount((int) Math.min(total, Integer.MAX_VALUE))
                    .permalink(MOMENTS_PATH + "?tag="
                        + UriUtils.encode(friendTag, StandardCharsets.UTF_8))
                    .build());
            return Flux.concat(selfTags(), friendItem.flux());
        });
    }

    /**
     * 自身瞬间标签统计（原生 listAllTags 同款口径），可见性与 listSelfTop 共用同一查询。
     */
    private Flux<FiresideTagVo> selfTags() {
        if (schemeManager.fetch(MOMENT_GVK).isEmpty()) {
            return Flux.empty();
        }
        return selfVisibilityQuery()
            .flatMapMany(query -> {
                var options = ListOptions.builder()
                    .fieldQuery(and(query, all("spec.tags")))
                    .build();
                return client.listAll(Moment.class, options, SELF_SORT);
            })
            .flatMapIterable(moment -> {
                var tags = moment.getSpec() == null ? null : moment.getSpec().getTags();
                return tags == null ? List.<String>of() : tags;
            })
            // 数据已全量在内存，普通分组计数即可——groupBy+concatMap 在分组数超过
            // 缓冲上限（256）时是 Reactor 已知的挂死模式，标签多的站点会卡住渲染
            .collect(Collectors.groupingBy(tag -> tag, LinkedHashMap::new,
                Collectors.counting()))
            .flatMapMany(counts -> Flux.fromIterable(counts.entrySet())
                .map(entry -> FiresideTagVo.builder()
                    .name(entry.getKey())
                    .momentCount(entry.getValue().intValue())
                    .permalink(MOMENTS_PATH + "?tag="
                        + UriUtils.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .build()));
    }

    /**
     * 自身瞬间：PluginMoments 未安装/未启用时 scheme 不存在，SELF 部分返回空（可选依赖不崩）。
     */
    private Mono<ListResult<Moment>> listSelfTop(int window, String tag) {
        return listSelfTop(window, tag, null);
    }

    private Mono<ListResult<Moment>> listSelfTop(int window, String tag,
        TimelineCursor cursor) {
        if (schemeManager.fetch(MOMENT_GVK).isEmpty()) {
            return Mono.just(new ListResult<>(1, window, 0L, List.of()));
        }
        return selfVisibilityQuery()
            .flatMap(query -> {
                var effective = StringUtils.isNotBlank(tag)
                    ? and(query, equal("spec.tags", tag)) : query;
                if (cursor != null) {
                    // <= 取回同毫秒并列条目，游标剔除在归并后按 name 精确完成
                    effective = and(effective,
                        lessThan("spec.releaseTime", cursor.time(), true));
                }
                var options = ListOptions.builder().fieldQuery(effective).build();
                return client.listBy(Moment.class, options,
                    PageRequestImpl.of(1, window, SELF_SORT));
            });
    }

    /**
     * 自身瞬间可见性查询，完整复刻瞬间插件原生逻辑（DefaultQueryMomentPredicateResolver）：
     * 匿名只见 approved+PUBLIC；登录用户额外可见自己的私密瞬间——与原生 /moments 行为一致。
     * listSelfTop 与 selfTags 共用，保证时间线与标签栏口径永远一致。
     */
    private Mono<Condition> selfVisibilityQuery() {
        return currentUserName()
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .map(username -> {
                var baseQuery = and(
                    isNull("metadata.deletionTimestamp"),
                    equal("spec.approved", Boolean.TRUE)
                );
                var visibleQuery = equal("spec.visible", Moment.MomentVisible.PUBLIC.name());
                return username
                    .map(name -> and(baseQuery, or(visibleQuery, equal("spec.owner", name))))
                    .orElseGet(() -> and(baseQuery, visibleQuery));
            });
    }

    private Mono<String> currentUserName() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .map(Principal::getName)
            .filter(name -> !AnonymousUserConst.isAnonymousUser(name));
    }

    private Mono<UrlContextListResult<FiresideMomentVo>> assembleTimeline(
        ListResult<Moment> self, ListResult<FriendMoment> friends, int page, int size,
        int window, String tag, String friendTag) {
        long total = self.getTotal() + friends.getTotal();
        // spec 为 null 的脏数据（API 直建可绕过 schema 校验）逐条跳过而非拖垮整页——
        // 对齐链接插件对坏条目的降级处理（NitriteLinkFeedItemStore.parseDocument）；
        // total 保持如实，异常态下页内少一条可接受
        List<TimelineItem> merged = mergeByTimeDesc(
            self.getItems().stream().filter(m -> m.getSpec() != null).toList(),
            friends.getItems().stream().filter(fm -> fm.getSpec() != null).toList());

        // 归并结果只有前 window 条能保证全局有序（两源各取 top-window，之后的条目
        // 缺失中间排名），超出窗口宁可返回空页也不返回错序数据；long 计算防大页码溢出
        int reachable = (int) Math.min(window, merged.size());
        long fromIndex = (long) (page - 1) * size;
        List<TimelineItem> pageItems = fromIndex >= reachable
            ? List.of()
            : merged.subList((int) fromIndex, (int) Math.min(fromIndex + size, reachable));

        // nextUrl 止步于最后一个可达页：归并窗口受单查询 1000 条上限约束，
        // 之外只有空页，不把主题分页器引进去；total 保持如实供计数展示
        long capByWindow = (PageRequestImpl.MAX_SIZE + size - 1L) / size;
        int lastReachablePage =
            (int) Math.max(1, Math.min(totalPages(total, size), capByWindow));

        return toVos(pageItems, friendTag)
            .map(vos -> new UrlContextListResult.Builder<FiresideMomentVo>()
                .listResult(new ListResult<>(page, size, total, vos))
                .prevUrl(pageUrl(Math.max(page - 1, 1), tag))
                .nextUrl(pageUrl(Math.min(page + 1, lastReachablePage), tag))
                .build());
    }

    /**
     * 页内条目批量转 VO：作者与 Link 预载后逐条组装
     * （assembleTimeline 与 assembleSlice 共用）。
     */
    private Mono<List<FiresideMomentVo>> toVos(List<TimelineItem> pageItems,
        String friendTag) {
        List<FriendMoment> friendsOnPage = pageItems.stream()
            .map(TimelineItem::friend)
            .filter(Objects::nonNull)
            .toList();
        return Mono.zip(preloadUsers(pageItems), preloadLinks(friendsOnPage))
            .flatMap(preloaded -> Flux.fromIterable(pageItems)
                .concatMap(item -> item.self() != null
                    ? selfVo(item.self(), preloaded.getT1())
                    : Mono.just(friendVo(item.friend(), friendTag,
                        preloaded.getT2().get(linkNameOf(item.friend())))))
                .collectList());
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
     * 解析用 UserService#getUserOrGhost——用户被删时回退内置幽灵用户，
     * 与瞬间插件的处理（PR #191）同款，owner 恒非 null。
     */
    private Mono<Map<String, User>> preloadUsers(List<TimelineItem> pageItems) {
        var owners = pageItems.stream()
            .map(TimelineItem::self)
            .filter(Objects::nonNull)
            .map(moment -> moment.getSpec() == null ? null : moment.getSpec().getOwner())
            .filter(StringUtils::isNotBlank)
            .distinct()
            .toList();
        return Flux.fromIterable(owners)
            .flatMap(owner -> userService.getUserOrGhost(owner)
                .map(user -> Map.entry(owner, user)))
            .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    /**
     * FRIEND 条目的来源 Link 按 linkName 去重后批量预载（preloadUsers 同款模式）。
     * Link 已删除的条目（级联清理完成前的窗口期）fetch 为空，不入 Map，
     * 组装时回退条目入库的快照字段。Link scheme 由链接插件（硬依赖）注册，恒可查。
     */
    private Mono<Map<String, Link>> preloadLinks(List<FriendMoment> friendMoments) {
        var linkNames = friendMoments.stream()
            .map(FiresideFinderImpl::linkNameOf)
            .filter(StringUtils::isNotBlank)
            .distinct()
            .toList();
        return Flux.fromIterable(linkNames)
            .flatMap(name -> client.fetch(Link.class, name)
                .map(link -> Map.entry(name, link)))
            .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private static String linkNameOf(FriendMoment friendMoment) {
        return friendMoment.getSpec() == null ? null : friendMoment.getSpec().getLinkName();
    }

    private Mono<FiresideMomentVo> selfVo(Moment moment, Map<String, User> users) {
        var metadata = moment.getMetadata();
        var momentSpec = moment.getSpec();
        // 自身瞬间原样透传（visible/approved/releaseTime/tags 均不加工）——
        // 原生 /moments 什么行为，混排后就什么行为
        var spec = FiresideMomentVo.SpecVo.builder()
            .content(selfContent(momentSpec))
            .releaseTime(momentSpec.getReleaseTime())
            .visible(momentSpec.getVisible() == null ? null
                : FiresideMomentVo.Visible.valueOf(momentSpec.getVisible().name()))
            .owner(momentSpec.getOwner())
            .tags(momentSpec.getTags())
            .approved(momentSpec.getApproved())
            .approvedTime(momentSpec.getApprovedTime())
            .build();
        // owner 对齐原生 ContributorVo.from：用户存在原样组装，不存在保持 null（不加工）
        var owner = ownerVo(users.get(momentSpec.getOwner()));
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
        // 自身瞬间不写回存储：展示时注入富链接占位，前台再调 link-detail 填封面
        return FiresideMomentVo.ContentVo.builder()
            .raw(StringUtils.defaultString(content.getRaw()))
            .html(RichLinkEnhancer.enhance(StringUtils.defaultString(content.getHtml())))
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

    /**
     * FRIEND 条目组装（调用方已过滤 spec 为 null 的脏数据，此处 spec 恒非 null）：
     * 作者展示信息（昵称/头像/简介/站点地址）以 Link 现值优先——
     * 友链改昵称、换头像后历史条目即时跟随（对齐官方链接插件 LinkFeedFinderImpl）；
     * Link 现值整体取用而非逐字段回退：友链清空头像等"现值为空"是站长的真实意图，
     * 逐字段回退会让已删除的旧值复活。Link 取不到时才整体回退入库快照。
     */
    private FiresideMomentVo friendVo(FriendMoment friendMoment, String friendTag, Link link) {
        var spec = friendMoment.getSpec();
        var linkSpec = link == null ? null : link.getSpec();
        String author = linkSpec != null ? linkSpec.getDisplayName() : spec.getAuthor();
        String avatar = linkSpec != null ? linkSpec.getLogo() : spec.getLogo();
        String bio = linkSpec != null ? linkSpec.getDescription() : spec.getAuthorBio();
        String authorUrl = linkSpec != null ? linkSpec.getUrl() : spec.getAuthorUrl();
        List<FiresideMomentVo.MediaVo> medium = spec.getMedia() == null ? List.of()
            : spec.getMedia().stream()
                .map(item -> FiresideMomentVo.MediaVo.builder()
                    .type(mediaTypeOf(item.getType()))
                    .url(item.getUrl())
                    .build())
                .toList();
        var specVo = FiresideMomentVo.SpecVo.builder()
            .content(FiresideMomentVo.ContentVo.builder()
                .raw(StringUtils.defaultString(spec.getContentText()))
                // 富链接占位仅在 RSS 同步入库时写入，读路径不再 enhance（避免每条 Jsoup）
                .html(injectFriendTag(StringUtils.defaultString(spec.getContentHtml()),
                    friendTag))
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
            .displayName(StringUtils.defaultIfBlank(author, spec.getLinkName()))
            .avatar(avatar)
            .bio(bio)
            .build();
        return FiresideMomentVo.builder()
            .metadata(friendMoment.getMetadata())
            .spec(specVo)
            .owner(owner)
            .stats(FiresideMomentVo.StatsVo.empty())
            .external(true)
            .sourceUrl(spec.getPostLink())
            .authorUrl(authorUrl)
            .title(spec.getTitle())
            .build();
    }

    /**
     * 渲染时把友链标签以瞬间编辑器同构的形态注入正文开头：
     * 独立段落 <p><a class="tag">标签名</a></p>（文本不带 #——井号是主题给 .tag 类
     * 加的装饰），与自身瞬间的正文内嵌标签结构完全一致。
     * 不写进存储：按当前配置动态注入，改标签名后历史条目即时跟随。
     */
    private static String injectFriendTag(String contentHtml, String friendTag) {
        if (StringUtils.isBlank(friendTag)) {
            return contentHtml;
        }
        var doc = Jsoup.parse(contentHtml);
        var tagLink = doc.createElement("a")
            .attr("class", "tag")
            // 相对链接：在 /moments 或 /fireside 上点击都在当前页面筛选
            // （瞬间编辑器写入正文的标签同为 ?tag= 相对形式）
            .attr("href", "?tag=" + UriUtils.encode(friendTag,
                StandardCharsets.UTF_8))
            .text(friendTag);
        var paragraph = doc.createElement("p");
        paragraph.appendChild(tagLink);
        paragraph.appendText(" ");
        doc.body().prependChild(paragraph);
        return doc.body().html();
    }

    private static FiresideMomentVo.MediaType mediaTypeOf(String type) {
        // EnumUtils 对 null/非法值安全返回 null，不以异常做控制流
        return Objects.requireNonNullElse(
            EnumUtils.getEnum(FiresideMomentVo.MediaType.class, type),
            FiresideMomentVo.MediaType.PHOTO);
    }

    private FiresideMomentVo.OwnerVo ownerVo(User user) {
        if (user == null || user.getSpec() == null) {
            return null;
        }
        return FiresideMomentVo.OwnerVo.builder()
            .name(user.getMetadata().getName())
            // displayName 未填时回退用户名，避免展示空白（friendVo 对 linkName 同款兜底）
            .displayName(StringUtils.defaultIfBlank(
                user.getSpec().getDisplayName(), user.getMetadata().getName()))
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
        return friendOptions(linkName, null);
    }

    private ListOptions friendOptions(String linkName, TimelineCursor cursor) {
        // 隐藏条目不对外输出（timeline / listFriends / 公开 API 同走此查询）；
        // 同步创建条目时不写 hidden（为 null），索引函数已归一为 false，equal false 能全部命中
        var query = and(
            isNull("metadata.deletionTimestamp"),
            equal("spec.hidden", false)
        );
        if (StringUtils.isNotBlank(linkName)) {
            query = and(query, equal("spec.linkName", linkName));
        }
        if (cursor != null) {
            // pubDate 由同步侧兜底（resolvePubDate），存量条目恒非 null
            query = and(query, lessThan("spec.pubDate", cursor.time(), true));
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
        if (page == null || page < 1) {
            return 1;
        }
        return Math.min(page, MAX_PAGE);
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
