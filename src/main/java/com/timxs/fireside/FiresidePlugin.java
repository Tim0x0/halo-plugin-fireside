package com.timxs.fireside;

import com.timxs.fireside.extension.FeedStatus;
import com.timxs.fireside.extension.FriendMoment;
import java.time.Instant;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * 围炉插件入口：负责 FriendMoment / FeedStatus 模型的 scheme 注册与注销。
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
        // 防御性先注销：Halo 存在"索引已清但 scheme 残留"的情况（halo#8265，
        // plugin-moments #187 同款修复），直接 register 会跳过索引重建
        schemeManager.unregister(Scheme.buildFromType(FriendMoment.class));
        schemeManager.unregister(Scheme.buildFromType(FeedStatus.class));
        schemeManager.register(FriendMoment.class, indexSpecs -> {
            // spec 为 null 的脏数据（API 直建可绕过 schema 校验）不应拖垮索引重建
            indexSpecs.add(IndexSpecs.<FriendMoment, String>single("spec.linkName", String.class)
                .indexFunc(fm -> fm.getSpec() == null ? null : fm.getSpec().getLinkName()));
            indexSpecs.add(IndexSpecs.<FriendMoment, String>single("spec.postLink", String.class)
                .indexFunc(fm -> fm.getSpec() == null ? null : fm.getSpec().getPostLink()));
            indexSpecs.add(IndexSpecs.<FriendMoment, Instant>single("spec.pubDate", Instant.class)
                .indexFunc(fm -> fm.getSpec() == null ? null : fm.getSpec().getPubDate()));
            // 同步创建条目时不写 hidden（值为 null），索引统一归一为 false，
            // 保证 equal("spec.hidden", false) 能命中全部未隐藏条目
            indexSpecs.add(IndexSpecs.<FriendMoment, Boolean>single("spec.hidden", Boolean.class)
                .indexFunc(fm -> fm.getSpec() != null
                    && Boolean.TRUE.equals(fm.getSpec().getHidden())));
        });
        schemeManager.register(FeedStatus.class);
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(FriendMoment.class));
        schemeManager.unregister(Scheme.buildFromType(FeedStatus.class));
    }
}
