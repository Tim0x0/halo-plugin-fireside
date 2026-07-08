package com.timxs.fireside.config;

import lombok.Data;

/**
 * settings.yaml 中 sync 组的配置项，字段缺失时按默认值兜底。
 */
@Data
public class SyncConfig {

    public static final String GROUP = "sync";

    private Boolean enabled;

    private Integer intervalMinutes;

    private Integer maxLinksPerRun;

    private Integer retainPerLink;

    private Integer timeoutSeconds;

    public boolean effectiveEnabled() {
        return enabled == null || enabled;
    }

    public int effectiveIntervalMinutes() {
        return clamp(intervalMinutes, 60, 5, Integer.MAX_VALUE);
    }

    public int effectiveMaxLinksPerRun() {
        return clamp(maxLinksPerRun, 20, 1, Integer.MAX_VALUE);
    }

    public int effectiveRetainPerLink() {
        return clamp(retainPerLink, 20, 1, 100);
    }

    public int effectiveTimeoutSeconds() {
        return clamp(timeoutSeconds, 10, 1, 60);
    }

    private static int clamp(Integer value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }
}
