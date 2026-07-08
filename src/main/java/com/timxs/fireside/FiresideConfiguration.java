package com.timxs.fireside;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 插件的 Spring 子上下文默认不注册调度注解处理器，
 * 必须显式 @EnableScheduling，否则 @Scheduled 静默不触发。
 */
@Configuration
@EnableScheduling
public class FiresideConfiguration {
}
