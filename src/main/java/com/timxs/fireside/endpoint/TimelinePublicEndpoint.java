package com.timxs.fireside.endpoint;

import com.timxs.fireside.finders.impl.FiresideFinderImpl;
import com.timxs.fireside.vo.FiresideTimelineSlice;
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

import static io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY;
import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

/**
 * 公开时间线 API：GET /apis/api.fireside.timxs.com/v1alpha1/timeline?before=&size=&tag=
 * 供主题前端 JS 做无限滚动（页面渲染后 Finder 不可达，需要匿名 HTTP 接口）。
 * 仅游标分页，与内置 /fireside 同源（timelineSlice）；size 设服务端上限防大分页拖库。
 * before 含 '|' 与 ':'，客户端必须 URL 编码（URLSearchParams / encodeURIComponent）。
 */
@Component
@RequiredArgsConstructor
public class TimelinePublicEndpoint implements CustomEndpoint {

    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 50;

    private final FiresideFinderImpl firesideFinder;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.fireside.timxs.com/v1alpha1/Timeline";
        return SpringdocRouteBuilder.route()
            .GET("timeline", this::listTimeline,
                builder -> builder.operationId("queryTimeline")
                    .description("Lists the aggregated timeline with cursor pagination "
                        + "(own moments + friend moments). Pass before=nextCursor from the "
                        + "previous response; the value must be URL-encoded.")
                    .tag(tag)
                    .parameter(parameterBuilder().in(QUERY).name("before")
                        .description("Cursor from previous page's nextCursor (URL-encoded); "
                            + "omit for first page")
                        .required(false))
                    .parameter(parameterBuilder().in(QUERY).name("size")
                        .description("Page size, default " + DEFAULT_SIZE + ", max " + MAX_SIZE)
                        .required(false))
                    .parameter(parameterBuilder().in(QUERY).name("tag")
                        .description("Optional tag filter").required(false))
                    .response(responseBuilder().implementation(FiresideTimelineSlice.class)))
            .build();
    }

    private Mono<ServerResponse> listTimeline(ServerRequest request) {
        String before = request.queryParam("before").filter(s -> !s.isBlank()).orElse(null);
        int size = parseSize(request.queryParam("size").orElse(null));
        String tag = request.queryParam("tag").filter(s -> !s.isBlank()).orElse(null);
        return firesideFinder.timelineSlice(before, size, tag)
            .flatMap(result -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(result));
    }

    private static int parseSize(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_SIZE;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1) {
                return DEFAULT_SIZE;
            }
            return Math.min(parsed, MAX_SIZE);
        } catch (NumberFormatException e) {
            return DEFAULT_SIZE;
        }
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.fireside.timxs.com/v1alpha1");
    }
}
