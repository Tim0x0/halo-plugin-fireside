package com.timxs.fireside;

import com.timxs.fireside.extension.FriendMoment;
import java.time.Instant;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * 围炉插件入口：负责 FriendMoment 模型的 scheme 注册与注销。
 */
@Component
public class FiresidePlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public FiresidePlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
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
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(FriendMoment.class));
    }
}
