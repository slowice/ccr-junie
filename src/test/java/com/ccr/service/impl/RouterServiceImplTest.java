package com.ccr.service.impl;

import com.ccr.config.CcrConfig;
import com.ccr.service.RouterService.RouteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RouterServiceImpl 单元测试
 * 验证路由选择逻辑，包括手动指定和场景自动检测
 */
public class RouterServiceImplTest {

    private RouterServiceImpl routerService;
    private CcrConfig ccrConfig;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ccrConfig = new CcrConfig();
        
        // 配置测试用的 Provider
        CcrConfig.Provider provider = new CcrConfig.Provider();
        provider.setName("test-provider");
        provider.setUrl("http://test.ai/v1");
        
        CcrConfig.TransformerConfig transformerConfig = new CcrConfig.TransformerConfig();
        transformerConfig.setUse(Collections.singletonList("OpenAI")); // 默认不是 Anthropic
        provider.setTransformer(transformerConfig);
        
        ccrConfig.setProviders(Collections.singletonList(provider));
        
        // 配置默认路由
        ccrConfig.setRouter(Collections.singletonMap("default", "test-provider,default-model"));
        
        routerService = new RouterServiceImpl(ccrConfig, objectMapper);
    }

    @Test
    void testGetRoute_DirectMatch() {
        // 请求体中直接指定 "Provider,Model" 格式
        String json = "{\"model\": \"test-provider,specific-model\"}";
        RouteResult result = routerService.getRoute(json);

        assertEquals("test-provider", result.getProvider().getName());
        assertEquals("specific-model", result.getTargetModel());
    }

    @Test
    void testGetRoute_DefaultScenario() {
        // 普通请求，应匹配默认路由
        String json = "{\"model\": \"claude-3-5-sonnet\", \"messages\": [{\"role\": \"user\", \"content\": \"hello\"}]}";
        RouteResult result = routerService.getRoute(json);

        assertEquals("test-provider", result.getProvider().getName());
        assertEquals("default-model", result.getTargetModel());
    }

    @Test
    void testGetRoute_BackgroundScenario() {
        // 模型名包含 haiku，应识别为 background 场景
        ccrConfig.setRouter(Collections.singletonMap("background", "test-provider,haiku-model"));
        
        String json = "{\"model\": \"claude-3-haiku-20240307\"}";
        RouteResult result = routerService.getRoute(json);

        assertEquals("haiku-model", result.getTargetModel());
    }
}
