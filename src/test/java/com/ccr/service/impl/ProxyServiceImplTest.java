package com.ccr.service.impl;

import com.ccr.config.CcrConfig;
import com.ccr.service.RouterService;
import com.ccr.service.RouterService.RouteResult;
import com.ccr.service.TransformerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * ProxyServiceImpl 单元测试
 * 验证请求转发的核心链路，包括路由获取和转换逻辑的调用
 */
public class ProxyServiceImplTest {

    private ProxyServiceImpl proxyService;
    private RouterService routerService;
    private TransformerService transformerService;
    private WebClient.Builder webClientBuilder;
    private WebClient webClient;

    @BeforeEach
    void setUp() {
        routerService = Mockito.mock(RouterService.class);
        transformerService = Mockito.mock(TransformerService.class);
        webClientBuilder = Mockito.mock(WebClient.Builder.class);
        webClient = Mockito.mock(WebClient.class);
        
        when(webClientBuilder.build()).thenReturn(webClient);
        
        proxyService = new ProxyServiceImpl(webClientBuilder, routerService, transformerService, new ObjectMapper());
    }

    @Test
    void testProxyRequest_BasicFlow() {
        String inputBody = "{\"model\": \"gpt-4\"}";
        CcrConfig.Provider provider = new CcrConfig.Provider();
        provider.setName("test-provider");
        provider.setUrl("http://test.ai");
        
        CcrConfig.TransformerConfig transformerConfig = new CcrConfig.TransformerConfig();
        transformerConfig.setUse(java.util.Collections.singletonList("Anthropic"));
        provider.setTransformer(transformerConfig); // 目标是 Anthropic
        
        RouteResult routeResult = new RouteResult(provider, "claude-3");
        
        when(routerService.getRoute(anyString())).thenReturn(routeResult);
        when(transformerService.transformOpenAiToAnthropic(anyString())).thenReturn("{\"transformed\": true}");
        
        // Mock WebClient 链式调用
        WebClient.RequestBodyUriSpec requestBodyUriSpec = Mockito.mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec requestBodySpec = Mockito.mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec requestHeadersSpec = Mockito.mock(WebClient.RequestHeadersSpec.class);
        
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), any())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        
        // 模拟返回 SSE 流
        Flux<ServerSentEvent<String>> sseFlux = Flux.just(ServerSentEvent.builder("data").build());
        when(requestHeadersSpec.exchangeToFlux(any())).thenAnswer(invocation -> {
            // 这里我们需要模拟 exchangeToFlux 的回调逻辑
            // 为了简化，我们可以直接返回我们想要的 Flux
            return sseFlux;
        });

        // 执行测试
        Flux<ServerSentEvent<String>> result = proxyService.proxyRequest(inputBody, true);

        // 验证
        StepVerifier.create(result)
                .expectNextMatches(sse -> "data".equals(sse.data()))
                .verifyComplete();
                
        Mockito.verify(routerService).getRoute(inputBody);
        Mockito.verify(transformerService).transformOpenAiToAnthropic(inputBody);
    }
}
