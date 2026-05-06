package com.ccr.service.impl;

import com.ccr.config.CcrConfig;
import com.ccr.constant.CcrConstants;
import com.ccr.service.ProxyService;
import com.ccr.service.RouterService;
import com.ccr.service.RouterService.RouteResult;
import com.ccr.service.TransformerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

/**
 * 代理转发服务实现类，处理请求转发、协议转换及响应透传
 */
@Service
public class ProxyServiceImpl implements ProxyService {
    private static final Logger log = LoggerFactory.getLogger(ProxyServiceImpl.class);

    private final WebClient webClient;
    private final RouterService routerService;
    private final TransformerService transformerService;
    private final ObjectMapper objectMapper;

    public ProxyServiceImpl(WebClient.Builder webClientBuilder, 
                        RouterService routerService, 
                        TransformerService transformerService, 
                        ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.routerService = routerService;
        this.transformerService = transformerService;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行代理请求的核心入口
     * 
     * @param body 请求体字符串
     * @param isIncomingOpenAi 客户端请求是否为 OpenAI 格式 (true: OpenAI, false: Anthropic)
     * @return 响应数据流
     */
    @Override
    public Flux<ServerSentEvent<String>> proxyRequest(String body, boolean isIncomingOpenAi) {
        log.info("Incoming request: body={}, isIncomingOpenAi={}", body, isIncomingOpenAi);
        // 1. 获取路由信息：决定使用哪个供应商及目标模型
        RouteResult route = routerService.getRoute(body);
        CcrConfig.Provider provider = route.getProvider();
        String targetModel = route.getTargetModel();
        boolean isOutgoingAnthropic = provider.isAnthropic();

        log.info("Forwarding request to provider: [{}] model: [{}] URL: {} (Anthropic: {})", 
                provider.getName(), targetModel, provider.getUrl(), isOutgoingAnthropic);

        // 2. 转换请求体：根据入参协议和目标供应商协议进行转换，并更新模型名称
        String finalBody = transformRequest(body, isIncomingOpenAi, isOutgoingAnthropic, targetModel);

        // 3. 发送请求并处理响应透传
        return forwardToUpstream(finalBody, provider, isIncomingOpenAi, isOutgoingAnthropic);
    }

    /**
     * 根据协议差异转换请求内容
     */
    private String transformRequest(String body, boolean isIncomingOpenAi, boolean isOutgoingAnthropic, String targetModel) {
        String finalBody = body;
        // 如果输入是 OpenAI 格式但输出是 Anthropic 格式，进行转换
        if (isIncomingOpenAi && isOutgoingAnthropic) {
            finalBody = transformerService.transformOpenAiToAnthropic(body);
        } 
        // 如果输入是 Anthropic 格式但输出是 OpenAI 格式，进行转换
        else if (!isIncomingOpenAi && !isOutgoingAnthropic) {
            finalBody = transformerService.transformAnthropicToOpenAi(body);
        }

        // 统一更新请求体中的模型名称为路由选定的目标模型
        try {
            JsonNode root = objectMapper.readTree(finalBody);
            if (root instanceof ObjectNode) {
                ((ObjectNode) root).put(CcrConstants.FIELD_MODEL, targetModel);
                finalBody = root.toString();
            }
        } catch (Exception e) {
            log.error("Failed to update model name: {}", e.getMessage());
        }
        return finalBody;
    }

    /**
     * 将请求转发至上游供应商，并处理 Headers 复制和响应转换
     */
    private Flux<ServerSentEvent<String>> forwardToUpstream(String requestBody, CcrConfig.Provider provider, 
                                             boolean isIncomingOpenAi, boolean isOutgoingAnthropic) {
        return webClient.post()
                .uri(provider.getUrl())
                // 根据供应商协议设置认证 Header
                .header(isOutgoingAnthropic ? CcrConstants.HEADER_X_API_KEY : CcrConstants.HEADER_AUTHORIZATION, 
                        isOutgoingAnthropic ? provider.getApiKey() : CcrConstants.HEADER_BEARER_PREFIX + provider.getApiKey())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBody)
                .exchangeToFlux(res -> {
                    log.debug("Upstream [{}] returned status code: {}", provider.getName(), res.statusCode());
                    
                    MediaType contentType = res.headers().asHttpHeaders().getContentType();
                    boolean isStreaming = contentType != null && contentType.toString().contains(CcrConstants.MEDIA_TYPE_EVENT_STREAM);

                    // 判断是否需要进行响应格式转换（SSE 或普通 JSON）
                    if (isIncomingOpenAi != isOutgoingAnthropic) {
                        log.debug("Converting response: IncomingOpenAi={}, OutgoingAnthropic={}", isIncomingOpenAi, isOutgoingAnthropic);
                        // 如果一端是 OpenAI，另一端是 Anthropic，则需要转换
                        // isIncomingOpenAi = true && isOutgoingAnthropic = false -> OpenAI 转 Anthropic
                        // isIncomingOpenAi = false && isOutgoingAnthropic = true -> Anthropic 转 OpenAI
                        return handleResponseTransformation(res, isStreaming, !isIncomingOpenAi);
                    } else {
                        log.debug("Passthrough response: Streaming={}", isStreaming);
                        // 协议一致（均为 OpenAI 或均为 Anthropic），直接透传原始数据流
                        if (isStreaming) {
                            return res.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                                    .doOnNext(sse -> log.info("Upstream response (Stream): data={}, event={}", sse.data(), sse.event()))
                                    .filter(sse -> sse.data() != null && !sse.data().isBlank());
                        } else {
                            return res.bodyToMono(String.class)
                                    .doOnNext(data -> log.info("Upstream response (JSON): {}", data))
                                    .map(data -> ServerSentEvent.<String>builder().data(data).build())
                                    .flux();
                        }
                    }
                });
    }

    /**
     * 处理响应的协议转换（支持流式和非流式）
     */
    private Flux<ServerSentEvent<String>> handleResponseTransformation(org.springframework.web.reactive.function.client.ClientResponse res, 
                                                         boolean isStreaming, 
                                                         boolean isAnthropicToOpenAi) {
        if (isStreaming) {
            // 流式响应转换：逐个处理 SSE 事件
            return res.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .flatMap(sse -> {
                        String data = sse.data();
                        if (data == null) return Flux.empty();
                        
                        String input = CcrConstants.SSE_DATA_PREFIX + data + CcrConstants.SSE_LINE_SEPARATOR;
                        String transformed = isAnthropicToOpenAi ? 
                                transformerService.transformAnthropicSseToOpenAi(input) :
                                transformerService.transformOpenAiSseToAnthropic(input);
                        
                        if (transformed == null) return Flux.empty();
                        
                        // 清理转换后字符串中的 data: 前缀和换行符，因为 ServerSentEvent 会自动添加
                        String cleanData = transformed;
                        if (cleanData.startsWith(CcrConstants.SSE_DATA_PREFIX)) {
                            cleanData = cleanData.substring(CcrConstants.SSE_DATA_PREFIX.length());
                        }
                        if (cleanData.endsWith(CcrConstants.SSE_LINE_SEPARATOR)) {
                            cleanData = cleanData.substring(0, cleanData.length() - CcrConstants.SSE_LINE_SEPARATOR.length());
                        }
                        
                        // 提取转换后的事件类型（如果有）
                        String eventType = sse.event();
                        try {
                            JsonNode json = objectMapper.readTree(cleanData);
                            if (json.has(CcrConstants.FIELD_TYPE)) {
                                eventType = json.get(CcrConstants.FIELD_TYPE).asText();
                            }
                        } catch (Exception e) {
                            // ignore
                        }

                        log.info("Transformed response (Stream): data={}, event={}", cleanData, eventType);
                        
                        return Flux.just(ServerSentEvent.<String>builder()
                                .event(eventType)
                                .id(sse.id())
                                .data(cleanData)
                                .build());
                    });
        } else {
            // 普通 JSON 响应转换
            return res.bodyToMono(String.class)
                    .map((String b) -> isAnthropicToOpenAi ? 
                            transformerService.transformAnthropicResponseToOpenAi(b) :
                            transformerService.transformOpenAiResponseToAnthropic(b))
                    .doOnNext(data -> log.info("Transformed response (JSON): {}", data))
                    .map(data -> ServerSentEvent.<String>builder().data(data).build())
                    .flux();
        }
    }
}
