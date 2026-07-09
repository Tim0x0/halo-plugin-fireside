package com.timxs.fireside.endpoint;

import com.timxs.fireside.finders.impl.FiresideFinderImpl;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListResult;

import static io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY;
import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

/**
 * 公开时间线 API：GET /apis/api.fireside.timxs.com/v1alpha1/timeline?page=&size=&tag=
 * 供主题前端 JS 做无限滚动加载（页面渲染后 Finder 不可达，需要匿名 HTTP 接口）。
 * 数据与 firesideFinder.timeline 同源同过滤（仅公开内容）；size 设服务端上限防大分页拖库。
 * 写法参照瞬间插件的 MomentQueryEndpoint（PR #177）。
 */
@Component
@RequiredArgsConstructor
public class TimelinePublicEndpoint implements CustomEndpoint {

    private static final int MAX_SIZE = 50;

    private final FiresideFinderImpl firesideFinder;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.fireside.timxs.com/v1alpha1/Timeline";
        return SpringdocRouteBuilder.route()
            .GET("timeline", this::listTimeline,
                builder -> builder.operationId("queryTimeline")
                    .description("Lists the aggregated timeline (own moments + friend moments).")
                    .tag(tag)
                    .parameter(parameterBuilder().in(QUERY).name("page")
                        .description("Page number, default 1").required(false))
                    .parameter(parameterBuilder().in(QUERY).name("size")
                        .description("Page size, default 10, max " + MAX_SIZE).required(false))
                    .parameter(parameterBuilder().in(QUERY).name("tag")
                        .description("Optional tag filter").required(false))
                    .response(responseBuilder().implementation(ListResult.class)))
            .build();
    }

    private Mono<ServerResponse> listTimeline(ServerRequest request) {
        Integer page = parseIntOrNull(request.queryParam("page").orElse(null));
        Integer size = parseIntOrNull(request.queryParam("size").orElse(null));
        if (size != null) {
            size = Math.min(size, MAX_SIZE);
        }
        String tag = request.queryParam("tag").filter(s -> !s.isBlank()).orElse(null);
        return firesideFinder.timeline(page, size, tag)
            .flatMap(result -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(result));
    }

    private static Integer parseIntOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.fireside.timxs.com/v1alpha1");
    }
}
