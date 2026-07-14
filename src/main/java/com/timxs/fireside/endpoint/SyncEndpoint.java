package com.timxs.fireside.endpoint;

import com.timxs.fireside.config.SyncConfig;
import com.timxs.fireside.sync.RssSyncService;
import com.timxs.fireside.sync.SyncInProgressException;
import java.util.List;
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
import run.halo.app.plugin.SettingFetcher;

import static io.swagger.v3.oas.annotations.enums.ParameterIn.PATH;
import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

/**
 * Console 手动同步端点：POST /apis/console.api.fireside.timxs.com/v1alpha1/links/{name}/sync
 * name 为链接名，"-" 表示全量。子资源动作形态（"集合+资源名"的 POST 会被判成
 * 非资源请求导致细粒度角色 403）。响应返回本次各链接的同步摘要。
 */
@Component
@RequiredArgsConstructor
public class SyncEndpoint implements CustomEndpoint {

    public static final String ALL_LINKS = "-";

    private final RssSyncService syncService;
    private final SettingFetcher settingFetcher;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "console.api.fireside.timxs.com/v1alpha1/Link";
        return SpringdocRouteBuilder.route()
            .POST("links/{name}/sync", this::sync,
                builder -> builder.operationId("syncLinkFeed")
                    .description("Manually sync friend moments of the link, '-' for all links.")
                    .tag(tag)
                    .parameter(parameterBuilder()
                        .in(PATH)
                        .name("name")
                        .description("Link name, '-' means all links")
                        .required(true))
                    .response(responseBuilder()
                        .implementation(SyncResponse.class)))
            .build();
    }

    private Mono<ServerResponse> sync(ServerRequest request) {
        String name = request.pathVariable("name");
        return Mono.fromCallable(() -> doSync(name))
            // 同步内部是阻塞 IO（HTTP 抓取 + 阻塞 client），不能占用 NIO 线程
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(SyncInProgressException.class,
                e -> new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()))
            .flatMap(response -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(response));
    }

    private SyncResponse doSync(String name) {
        SyncConfig config = settingFetcher.fetch(SyncConfig.GROUP, SyncConfig.class)
            .orElseGet(SyncConfig::new);
        List<RssSyncService.LinkSyncResult> results = ALL_LINKS.equals(name)
            ? syncService.syncAll(config)
            : List.of(syncService.syncLink(name, config));
        int success = (int) results.stream().filter(RssSyncService.LinkSyncResult::success).count();
        int created = results.stream().mapToInt(RssSyncService.LinkSyncResult::created).sum();
        return new SyncResponse(results.size(), success, results.size() - success, created,
            results);
    }

    public record SyncResponse(int total, int successCount, int failureCount, int createdCount,
                               List<RssSyncService.LinkSyncResult> results) {
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.fireside.timxs.com/v1alpha1");
    }
}
