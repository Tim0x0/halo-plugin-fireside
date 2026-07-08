package com.timxs.fireside.reconciler;

import static run.halo.app.extension.index.query.Queries.equal;

import com.timxs.fireside.FiresideConst;
import com.timxs.fireside.extension.FriendMoment;
import com.timxs.fireside.stub.Link;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

/**
 * 监听 Link 变化做级联清理：Link 被删除或动态 RSS 注解被清空时，
 * 删除该 linkName 下全部 FriendMoment。
 * 不给 Link 加 finalizer（那是别的插件的数据，插件卸载后残留 finalizer 会卡住 Link 删除）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LinkReconciler implements Reconciler<Reconciler.Request> {

    private final ExtensionClient client;

    @Override
    public Result reconcile(Request request) {
        String linkName = request.name();
        boolean shouldCleanup = client.fetch(Link.class, linkName)
            .map(link -> ExtensionUtil.isDeleted(link)
                || StringUtils.isBlank(
                    MetadataUtil.nullSafeAnnotations(link).get(FiresideConst.RSS_URL_ANNO)))
            .orElse(true);
        if (shouldCleanup) {
            deleteFriendMoments(linkName);
        }
        return Result.doNotRetry();
    }

    private void deleteFriendMoments(String linkName) {
        var options = ListOptions.builder()
            .fieldQuery(equal("spec.linkName", linkName))
            .build();
        var friendMoments = client.listAll(FriendMoment.class, options, Sort.unsorted());
        if (friendMoments.isEmpty()) {
            return;
        }
        friendMoments.forEach(client::delete);
        log.info("[围炉] 友链 [{}] 已删除或取消订阅，级联清理 {} 条动态", linkName, friendMoments.size());
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
            .extension(new Link())
            .build();
    }
}
