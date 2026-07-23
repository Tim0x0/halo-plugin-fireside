package com.timxs.fireside.reconciler;

import com.timxs.fireside.FiresideConst;
import com.timxs.fireside.stub.Link;
import com.timxs.fireside.sync.RssSyncService;
import com.timxs.fireside.sync.SyncInProgressException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

/**
 * 监听 Link 变化做级联清理：Link 被删除或动态 RSS 注解被清空时，
 * 删除该 linkName 下全部 FriendMoment 与对应 FeedStatus。
 * 清理走 {@link RssSyncService#cleanupLinkSubscription}，与同步/清空共用一把锁，
 * 避免同步写入与级联删除交错留下孤儿数据；锁被占用时稍后重试。
 * 不给 Link 加 finalizer（那是别的插件的数据，插件卸载后残留 finalizer 会卡住 Link 删除）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LinkReconciler implements Reconciler<Reconciler.Request> {

    private static final Duration RETRY_AFTER = Duration.ofSeconds(5);

    private final ExtensionClient client;
    private final RssSyncService syncService;

    @Override
    public Result reconcile(Request request) {
        String linkName = request.name();
        boolean shouldCleanup = client.fetch(Link.class, linkName)
            .map(link -> ExtensionUtil.isDeleted(link)
                || StringUtils.isBlank(
                    MetadataUtil.nullSafeAnnotations(link).get(FiresideConst.RSS_URL_ANNO)))
            .orElse(true);
        if (!shouldCleanup) {
            return Result.doNotRetry();
        }
        try {
            syncService.cleanupLinkSubscription(linkName);
            return Result.doNotRetry();
        } catch (SyncInProgressException e) {
            // 同步持锁——稍后重试，避免本轮放弃后留下未清理数据
            log.info("[围炉] 友链 [{}] 级联清理延后：{}", linkName, e.getMessage());
            return new Result(true, RETRY_AFTER);
        }
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
            .extension(new Link())
            .build();
    }
}
