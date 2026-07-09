package com.timxs.fireside;

import com.timxs.fireside.extension.FeedStatus;
import com.timxs.fireside.extension.FriendMoment;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.AnnotationSetting;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * 围炉插件入口：负责 FriendMoment 模型的 scheme 注册与注销，
 * 以及历史重复 AnnotationSetting 的迁移清理。
 */
@Slf4j
@Component
public class FiresidePlugin extends BasePlugin {

    /**
     * extensions/annotation-setting.yaml 的固定资源名——Halo 按 metadata.name
     * 对插件资源做 create-or-update，固定名才能幂等升级。
     */
    public static final String ANNOTATION_SETTING_NAME = "fireside-link-annotation-setting";

    /**
     * Halo 给插件创建的资源自动打的插件名 label（PluginConst 在 application 模块，插件不可见）。
     */
    private static final String PLUGIN_NAME_LABEL = "plugin.halo.run/plugin-name";

    private static final String PLUGIN_NAME = "plugin-fireside";

    private final SchemeManager schemeManager;
    private final ExtensionClient client;

    public FiresidePlugin(PluginContext pluginContext, SchemeManager schemeManager,
        ExtensionClient client) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.client = client;
    }

    @Override
    public void start() {
        schemeManager.register(FriendMoment.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<FriendMoment, String>single("spec.linkName", String.class)
                .indexFunc(fm -> fm.getSpec().getLinkName()));
            indexSpecs.add(IndexSpecs.<FriendMoment, String>single("spec.postLink", String.class)
                .indexFunc(fm -> fm.getSpec().getPostLink()));
            indexSpecs.add(IndexSpecs.<FriendMoment, Instant>single("spec.pubDate", Instant.class)
                .indexFunc(fm -> fm.getSpec().getPubDate()));
        });
        schemeManager.register(FeedStatus.class);
        cleanupLegacyAnnotationSettings();
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(FriendMoment.class));
        schemeManager.unregister(Scheme.buildFromType(FeedStatus.class));
    }

    /**
     * 历史版本的 annotation-setting.yaml 用了 generateName，每次插件启动都会
     * 重复创建一份（链接表单字段随之翻倍）。这里删除本插件名下所有非固定名的
     * AnnotationSetting；固定名实例由 Halo 在插件启动完成后按 yaml 创建/更新。
     */
    private void cleanupLegacyAnnotationSettings() {
        var options = ListOptions.builder()
            .labelSelector().eq(PLUGIN_NAME_LABEL, PLUGIN_NAME).end()
            .build();
        client.listAll(AnnotationSetting.class, options, Sort.unsorted()).stream()
            .filter(setting -> !ANNOTATION_SETTING_NAME.equals(setting.getMetadata().getName()))
            .forEach(setting -> {
                client.delete(setting);
                log.info("[围炉] 清理历史重复的 AnnotationSetting: {}",
                    setting.getMetadata().getName());
            });
    }
}
