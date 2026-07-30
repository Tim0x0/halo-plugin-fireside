package com.timxs.fireside.route;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.timxs.fireside.config.DisplayConfig;
import com.timxs.fireside.finders.impl.FiresideFinderImpl;
import com.timxs.fireside.vo.FiresideMomentVo;
import com.timxs.fireside.vo.FiresideTagVo;
import com.timxs.fireside.vo.FiresideTimelineSlice;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.PluginContext;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.app.theme.TemplateNameResolver;

/**
 * 围炉前台页面路由：
 * GET /fireside          —— 时间线（游标分页，?before= 为上一页页尾游标）
 * GET /fireside/{name}   —— 自身瞬间详情页
 * 首屏与每一页均为 SSR，模板可被主题覆盖（TemplateNameResolver 兜底到插件 jar 内模板）。
 */
@RequiredArgsConstructor
public class FiresideRouter {

    private static final String TEMPLATE_ID = "_templateId";
    private static final String TAG_PARAM = "tag";
    private static final String BEFORE_PARAM = "before";

    private final FiresideFinderImpl firesideFinder;
    private final TemplateNameResolver templateNameResolver;
    private final ReactiveSettingFetcher settingFetcher;
    private final PluginContext pluginContext;

    /**
     * 注册 /fireside 页面路由；时间线路由在前，详情路由的 {name} 变量不会吞掉它。
     */
    @SuppressWarnings("unchecked")
    public RouterFunction<ServerResponse> firesideRouteFunction() {
        return route(GET("/fireside"), this::handleList)
            .andRoute(GET("/fireside/{name}"), this::handleDetail);
    }

    /**
     * 时间线：每页条数取自插件设置（默认 20），?before= 游标翻更早的页。
     */
    private Mono<ServerResponse> handleList(ServerRequest request) {
        String tag = tagParam(request);
        String before = request.queryParam(BEFORE_PARAM)
            .filter(StringUtils::isNotBlank)
            .orElse(null);
        return displayConfig().flatMap(display ->
            firesideFinder.timelineSlice(before, display.effectivePageSize(), tag)
                .flatMap(slice -> templateNameResolver
                    .resolveTemplateNameOrDefault(request.exchange(), "fireside")
                    .flatMap(templateName -> ServerResponse.ok()
                        .render(templateName,
                            buildModel(slice, tag, before != null, display)))));
    }

    /**
     * 自身瞬间详情页：可见性由 Finder 的同口径查询保证
     * （匿名只见已通过 + 公开，登录用户可见自己的私密瞬间），未命中转 404。
     */
    private Mono<ServerResponse> handleDetail(ServerRequest request) {
        // 该路由必有 name 变量，pathVariable 不会抛异常
        String name = request.pathVariable("name");
        // 与列表页一致：先解包 DisplayConfig 再放进 model（不用塞未订阅的 Mono）
        return displayConfig().flatMap(display ->
            firesideFinder.get(name)
                .flatMap(moment -> templateNameResolver
                    .resolveTemplateNameOrDefault(request.exchange(), "fireside-detail")
                    .flatMap(templateName -> ServerResponse.ok()
                        .render(templateName, buildDetailModel(moment, display))))
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Moment not found: " + name))));
    }

    /** 详情 model：附加 seoDescription（正文摘要）、seoImage（首图，可为相对路径）。 */
    private Map<String, Object> buildDetailModel(FiresideMomentVo moment,
        DisplayConfig display) {
        Map<String, Object> model = new HashMap<>();
        model.put("moment", moment);
        model.put(TEMPLATE_ID, "fireside-detail");
        model.put("display", display);
        model.put("pluginVersion", pluginContext.getVersion());
        model.put("seoDescription", seoDescriptionOf(moment));
        model.put("seoImage", seoImageOf(moment));
        return model;
    }

    /** 详情分享描述：HTML 纯文本优先，否则 raw；约 160 字。 */
    static String seoDescriptionOf(FiresideMomentVo moment) {
        var content = moment == null || moment.getSpec() == null
            ? null : moment.getSpec().getContent();
        if (content == null) {
            return "";
        }
        String text = "";
        if (StringUtils.isNotBlank(content.getHtml())) {
            text = Jsoup.parse(content.getHtml()).text();
        }
        if (StringUtils.isBlank(text)) {
            text = StringUtils.defaultString(content.getRaw());
        }
        text = StringUtils.normalizeSpace(text);
        return text.isEmpty() ? "" : StringUtils.abbreviate(text, 160);
    }

    /** 详情分享图：medium 中首张 PHOTO/POST（或未标类型）；无则空串。 */
    static String seoImageOf(FiresideMomentVo moment) {
        var content = moment == null || moment.getSpec() == null
            ? null : moment.getSpec().getContent();
        List<FiresideMomentVo.MediaVo> medium =
            content == null || content.getMedium() == null ? List.of() : content.getMedium();
        for (FiresideMomentVo.MediaVo item : medium) {
            if (item == null || StringUtils.isBlank(item.getUrl())) {
                continue;
            }
            var type = item.getType();
            if (type == null
                || type == FiresideMomentVo.MediaType.PHOTO
                || type == FiresideMomentVo.MediaType.POST) {
                return item.getUrl().trim();
            }
        }
        return "";
    }

    /**
     * 构建时间线模板 model。olderUrl/latestUrl 为空串表示不渲染对应链接。
     */
    private Map<String, Object> buildModel(FiresideTimelineSlice slice, String tag,
        boolean paged, DisplayConfig display) {
        return Map.of(
            "moments", slice,
            TEMPLATE_ID, "fireside",
            "tag", Optional.ofNullable(tag).orElse(""),
            // display 组配置整体给模板（含 appearance / interaction 分节）
            "display", display,
            // Halo 模板全局变量只有 site/theme，插件版本需自行放入 model
            // 供模板给静态资源 URL 加 ?v= 缓存指纹
            "pluginVersion", pluginContext.getVersion(),
            // 更早一页：游标为页尾条目的（时间|来源|name）
            "olderUrl", slice.isHasNext()
                ? pageUrl(slice.getNextCursor(), tag) : "",
            // 回到最新：仅翻页后显示
            "latestUrl", paged ? pageUrl(null, tag) : "",
            // 标签导航：本地标签按条数降序，友链标签固定末位
            "tags", firesideFinder.listAllTags()
                .collectList()
                .map(list -> {
                    String friendTag = display.effectiveFriendTag();
                    list.sort(Comparator
                        .comparing((FiresideTagVo t) -> friendTag.equals(t.getName()))
                        .thenComparing(Comparator
                            .comparingInt(FiresideTagVo::getMomentCount).reversed()));
                    return list;
                })
        );
    }

    /**
     * 拼时间线页 URL：游标与 tag 参数按需附加，统一在此转义
     * （游标含 '|' 与 ':'，tag 可能含中文）。
     */
    private static String pageUrl(String cursor, String tag) {
        var builder = UriComponentsBuilder.fromPath("/fireside");
        if (StringUtils.isNotBlank(cursor)) {
            builder.queryParam(BEFORE_PARAM, cursor);
        }
        if (StringUtils.isNotBlank(tag)) {
            builder.queryParam(TAG_PARAM, tag);
        }
        return builder.encode().build().toUriString();
    }

    private Mono<DisplayConfig> displayConfig() {
        return settingFetcher.fetch(DisplayConfig.GROUP, DisplayConfig.class)
            .defaultIfEmpty(new DisplayConfig());
    }

    private static String tagParam(ServerRequest request) {
        return request.queryParam(TAG_PARAM)
            .filter(StringUtils::isNotBlank)
            .orElse(null);
    }
}
