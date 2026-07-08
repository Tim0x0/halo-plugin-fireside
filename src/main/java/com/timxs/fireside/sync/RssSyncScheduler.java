package com.timxs.fireside.sync;

import com.timxs.fireside.config.SyncConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.SettingFetcher;

/**
 * 定时调度：5 分钟轮询一次，对照 settings 中的同步间隔判断是否到期。
 * 沿用链接插件的调度模式；lastRunAt 仅存内存（Halo 2.x 单实例架构），
 * 插件重启会提前触发一次同步，无害。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RssSyncScheduler {

    private final SettingFetcher settingFetcher;
    private final RssSyncService syncService;

    private volatile Instant lastRunAt;

    @Scheduled(initialDelay = 30, fixedDelay = 300, timeUnit = TimeUnit.SECONDS)
    public void tick() {
        SyncConfig config = settingFetcher.fetch(SyncConfig.GROUP, SyncConfig.class)
            .orElseGet(SyncConfig::new);
        if (!config.effectiveEnabled()) {
            return;
        }
        Instant now = Instant.now();
        if (lastRunAt != null
            && lastRunAt.plus(Duration.ofMinutes(config.effectiveIntervalMinutes())).isAfter(now)) {
            return;
        }
        lastRunAt = now;
        try {
            // @Scheduled 单线程串行，方法体阻塞到本轮结束，天然不重叠
            syncService.syncAll(config);
        } catch (Exception e) {
            log.error("[围炉] 本轮同步执行失败", e);
        }
    }
}
