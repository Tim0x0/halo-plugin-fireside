package com.timxs.fireside.endpoint;

import com.timxs.fireside.sync.RssSyncService;
import com.timxs.fireside.sync.SyncInProgressException;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static io.swagger.v3.oas.annotations.enums.ParameterIn.PATH;
import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

/**
 * Console 清空端点：POST /apis/console.api.fireside.timxs.com/v1alpha1/links/{name}/clear-moments
 * name 为链接名，"-" 表示全部（子资源动作形态，与手动同步端点一致）。
 * 后端批量删除并归零计数：大量订阅时前端逐条 DELETE 可达数万次请求，
 * 且 FeedStatus 计数由后端单点维护才能在清空后即时准确。
 */
@Component
@RequiredArgsConstructor
public class ClearMomentsEndpoint implements CustomEndpoint {

    public static final String ALL_LINKS = "-";

    private final RssSyncService syncService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "console.api.fireside.timxs.com/v1alpha1/Link";
        return SpringdocRouteBuilder.route()
            .POST("links/{name}/clear-moments", this::clear,
                builder -> builder.operationId("clearLinkFriendMoments")
                    .description("Delete fetched friend moments of the link, '-' for all links.")
                    .tag(tag)
                    .parameter(parameterBuilder()
                        .in(PATH)
                        .name("name")
                        .description("Link name, '-' means all links")
                        .required(true))
                    .response(responseBuilder()
                        .implementation(ClearResponse.class)))
            .build();
    }

    private Mono<ServerResponse> clear(ServerRequest request) {
        String name = request.pathVariable("name");
        return Mono.fromCallable(() -> new ClearResponse(
                syncService.clearMoments(ALL_LINKS.equals(name) ? null : name)))
            // 批量删除是阻塞 IO，不能占用 NIO 线程
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(SyncInProgressException.class,
                e -> new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()))
            .flatMap(response -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(response));
    }

    public record ClearResponse(int deletedCount) {
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.fireside.timxs.com/v1alpha1");
    }
}
