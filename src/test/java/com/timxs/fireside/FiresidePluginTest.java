package com.timxs.fireside;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import run.halo.app.core.extension.AnnotationSetting;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.PluginContext;

@ExtendWith(MockitoExtension.class)
class FiresidePluginTest {

    @Mock
    PluginContext context;

    @Mock
    SchemeManager schemeManager;

    @Mock
    ExtensionClient client;

    @InjectMocks
    FiresidePlugin plugin;

    @Test
    void contextLoads() {
        when(client.listAll(eq(AnnotationSetting.class), any(), any()))
            .thenReturn(List.of());
        plugin.start();
        plugin.stop();
    }
}
