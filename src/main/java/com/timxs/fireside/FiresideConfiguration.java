package com.timxs.fireside;

import com.timxs.fireside.config.DisplayConfig;
import com.timxs.fireside.finders.impl.FiresideFinderImpl;
import com.timxs.fireside.route.FiresideRouter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import run.halo.app.plugin.PluginContext;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.app.theme.TemplateNameResolver;

/**
 * 插件的 Spring 子上下文默认不注册调度注解处理器，
 * 必须显式 @EnableScheduling，否则 @Scheduled 静默不触发。
 */
@Configuration
@EnableScheduling
public class FiresideConfiguration {

    /**
     * 注册围炉前台页面路由（/fireside）。
     * FiresideRouter 本身不加 @Component（避免 Spring 扫描时因构造器参数
     * 注入失败而启动异常），由 @Bean 显式组装——与 plugin-moments 同一模式。
     */
    @Bean
    RouterFunction<ServerResponse> firesideRoute(
        FiresideFinderImpl firesideFinder,
        TemplateNameResolver templateNameResolver,
        ReactiveSettingFetcher settingFetcher,
        PluginContext pluginContext) {
        return new FiresideRouter(
            firesideFinder, templateNameResolver, settingFetcher, pluginContext
        ).firesideRouteFunction();
    }
}
