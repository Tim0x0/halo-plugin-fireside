package com.timxs.fireside.config;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;

/**
 * settings.yaml 中 display 组的配置项。
 */
@Data
public class DisplayConfig {

    public static final String GROUP = "display";

    public static final String DEFAULT_FRIEND_TAG = "友链";

    /**
     * 赋给每条友链动态的标签名；主题可用 ?tag=该标签 仅筛出友链动态。
     */
    private String friendTag;

    public String effectiveFriendTag() {
        // 井号由展示层统一添加；配置值带 # 会导致双井号，且 # 是 URL 锚点符会破坏
        // ?tag= 筛选——无论填不填 # 都归一为纯标签名
        String value = StringUtils.trim(StringUtils.stripStart(
            StringUtils.trim(friendTag), "#"));
        return StringUtils.defaultIfBlank(value, DEFAULT_FRIEND_TAG);
    }
}
