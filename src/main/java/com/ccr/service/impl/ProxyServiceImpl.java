package com.ccr.service.impl;

import com.ccr.config.CcrConfig;
import com.ccr.constant.CcrConstants;
import com.ccr.model.StreamContext;
import com.ccr.service.ProxyService;
import com.ccr.service.RouterService;
import com.ccr.service.RouterService.RouteResult;
import com.ccr.service.TransformerService;
import com.fasterxml.jackson.core.JsonProcessingException;
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
     * 1. 识别路由（供应商和模型）
     * 2. 执行请求体协议转换
     * 3. 转发至上游并处理响应转换
     * 
     * @param body 原始请求体字符串
     * @param isIncomingOpenAi 客户端发送的请求是否为 OpenAI 格式
     * @return 转换后的服务器发送事件（SSE）流
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
     * 转换请求体逻辑封装
     * 
     * @param body 原始请求体
     * @param isIncomingOpenAi 输入是否为 OpenAI 格式
     * @param isOutgoingAnthropic 输出（上游）是否为 Anthropic 格式
     * @param targetModel 目标模型名称
     * @return 转换并更新模型名后的请求体
     */
    private String transformRequest(String body, boolean isIncomingOpenAi, boolean isOutgoingAnthropic, String targetModel) {
        log.info("Transforming request: isIncomingOpenAi={}, isOutgoingAnthropic={}, targetModel={}", isIncomingOpenAi, isOutgoingAnthropic, targetModel);
        log.debug("Original request body: {}", body);
        
        // 执行 OpenAI <-> Anthropic 之间的协议互转
        String transformedBody = performProtocolTransformation(body, isIncomingOpenAi, isOutgoingAnthropic);
        // 更新 JSON 中的 model 字段为上游要求的名称
        String finalBody = updateTargetModel(transformedBody, targetModel);
        
        log.info("Final transformed request body: {}", finalBody);
        return finalBody;
    }

    /**
     * 执行协议层面的双向转换
     */
    private String performProtocolTransformation(String body, boolean isIncomingOpenAi, boolean isOutgoingAnthropic) {
        // 如果输入是 OpenAI 格式但输出是 Anthropic 格式，进行转换
        if (isIncomingOpenAi && isOutgoingAnthropic) {
            return transformerService.transformOpenAiToAnthropic(body);
        } 
        // 如果输入是 Anthropic 格式但输出是 OpenAI 格式，进行转换
        else if (!isIncomingOpenAi && !isOutgoingAnthropic) {
            return transformerService.transformAnthropicToOpenAi(body);
        }
        // 协议一致（例如都是 OpenAI 或都是 Anthropic），无需转换
        return body;
    }

    /**
     * 更新请求体中的模型名称字段
     * 
     * @param body 请求体字符串
     * @param targetModel 目标模型名
     * @return 更新后的请求体
     */
    private String updateTargetModel(String body, String targetModel) {
        try {
            JsonNode rootNode = objectMapper.readTree(body);
            if (rootNode instanceof ObjectNode) {
                ((ObjectNode) rootNode).put(CcrConstants.FIELD_MODEL, targetModel);
                return rootNode.toString();
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse request body for updating model name: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Unknown error occurred during model name update: {}", e.getMessage());
        }
        return body;
    }

    /**
     * 将请求转发至上游供应商，并处理 Headers 复制和响应转换
     * 
     * @param requestBody 转换后的请求体
     * @param provider 供应商配置
     * @param isIncomingOpenAi 客户端侧协议
     * @param isOutgoingAnthropic 上游侧协议
     * @return 响应流
     */
    private Flux<ServerSentEvent<String>> forwardToUpstream(String requestBody, CcrConfig.Provider provider, 
                                             boolean isIncomingOpenAi, boolean isOutgoingAnthropic) {
        // 创建流式上下文，用于维护 SSE 转换过程中的状态信息
        StreamContext streamContext = new StreamContext();
        return webClient.post()
                .uri(provider.getUrl())
                // 根据上游协议选择合适的认证 Header (X-API-Key 或 Authorization)
                .header(getAuthHeaderName(isOutgoingAnthropic), getAuthHeaderValue(provider, isOutgoingAnthropic))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBody)
                .exchangeToFlux(response -> processUpstreamResponse(response, provider, isIncomingOpenAi, isOutgoingAnthropic, streamContext));
    }

    /**
     * 根据协议获取认证 Header 名称
     */
    private String getAuthHeaderName(boolean isOutgoingAnthropic) {
        return isOutgoingAnthropic ? CcrConstants.HEADER_X_API_KEY : CcrConstants.HEADER_AUTHORIZATION;
    }

    /**
     * 根据协议和配置获取认证 Header 值
     */
    private String getAuthHeaderValue(CcrConfig.Provider provider, boolean isOutgoingAnthropic) {
        return isOutgoingAnthropic ? provider.getApiKey() : CcrConstants.HEADER_BEARER_PREFIX + provider.getApiKey();
    }

    /**
     * 处理上游返回的响应对象，决定是直接透传还是执行协议转换
     */
    private Flux<ServerSentEvent<String>> processUpstreamResponse(org.springframework.web.reactive.function.client.ClientResponse response, 
                                                                  CcrConfig.Provider provider, 
                                                                  boolean isIncomingOpenAi, 
                                                                  boolean isOutgoingAnthropic, 
                                                                  StreamContext streamContext) {
        log.debug("Upstream [{}] returned status code: {}", provider.getName(), response.statusCode());
        
        MediaType contentType = response.headers().asHttpHeaders().getContentType();
        // 识别是否为流式响应 (text/event-stream)
        boolean isStreaming = contentType != null && contentType.toString().contains(CcrConstants.MEDIA_TYPE_EVENT_STREAM);

        // 如果客户端期望协议与上游返回协议不一致，则需要执行转换逻辑
        if (isIncomingOpenAi == isOutgoingAnthropic) {
            log.info("Converting response: IncomingOpenAi={}, OutgoingAnthropic={}", isIncomingOpenAi, isOutgoingAnthropic);
            return handleResponseTransformation(response, isStreaming, isIncomingOpenAi, streamContext);
        } else {
            // 协议一致，直接透传
            return handlePassthroughResponse(response, isStreaming);
        }
    }

    /**
     * 透传处理逻辑：不对响应内容做修改，仅保持流式或非流式格式
     */
    private Flux<ServerSentEvent<String>> handlePassthroughResponse(org.springframework.web.reactive.function.client.ClientResponse response, boolean isStreaming) {
        log.info("Passthrough response: Streaming={}", isStreaming);
        if (isStreaming) {
            return response.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .doOnNext(sseEvent -> log.info("Upstream response (Stream): data={}, event={}", sseEvent.data(), sseEvent.event()))
                    .filter(sseEvent -> sseEvent.data() != null && !sseEvent.data().isBlank());
        } else {
            return response.bodyToMono(String.class)
                    .doOnNext(data -> log.info("Upstream response (JSON): {}", data))
                    .map(data -> ServerSentEvent.<String>builder().data(data).build())
                    .flux();
        }
    }

    /**
     * 处理响应的协议转换（支持流式和非流式）
     */
    private Flux<ServerSentEvent<String>> handleResponseTransformation(org.springframework.web.reactive.function.client.ClientResponse response, 
                                                         boolean isStreaming, 
                                                         boolean isAnthropicToOpenAi,
                                                         StreamContext streamContext) {
        if (isStreaming) {
            return handleStreamingTransformation(response, isAnthropicToOpenAi, streamContext);
        } else {
            return handleNonStreamingTransformation(response, isAnthropicToOpenAi);
        }
    }

    /**
     * 流式响应转换逻辑：逐帧解析上游事件并映射为目标协议事件
     */
    private Flux<ServerSentEvent<String>> handleStreamingTransformation(org.springframework.web.reactive.function.client.ClientResponse response, 
                                                                         boolean isAnthropicToOpenAi, 
                                                                         StreamContext streamContext) {
        return response.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .flatMap(sseEvent -> {
                    String data = sseEvent.data();
                    log.info("Upstream response (Stream): event={}, data={}", sseEvent.event(), data);
                    if (data == null) return Flux.empty();
                    
                    // 为每一行数据增加 data: 前缀和换行符，模拟标准 SSE 格式供转换器处理
                    String input = CcrConstants.SSE_DATA_PREFIX + data + CcrConstants.SSE_LINE_SEPARATOR;
                    String transformed = isAnthropicToOpenAi ? 
                            transformerService.transformAnthropicSseToOpenAi(input) :
                            transformerService.transformOpenAiSseToAnthropic(input, streamContext);
                    
                    // 处理转换后可能产生的多行（多事件）输出
                    return processTransformedSse(transformed, sseEvent.event());
                });
    }

    /**
     * 将转换后的字符串解析回多个 ServerSentEvent 对象
     */
    private Flux<ServerSentEvent<String>> processTransformedSse(String transformed, String originalEvent) {
        if (transformed == null) {
            log.info("Transformed response (Stream) is null, skipping");
            return Flux.empty();
        }

        // 拆分可能存在的多个事件
        String[] parts = transformed.split(CcrConstants.SSE_LINE_SEPARATOR);
        return Flux.fromArray(parts)
                .filter(part -> !part.isBlank())
                .map(part -> buildServerSentEvent(part, originalEvent));
    }

    /**
     * 构建单条 ServerSentEvent 对象
     */
    private ServerSentEvent<String> buildServerSentEvent(String part, String originalEvent) {
        // 移除 data: 前缀获取纯 JSON 字符串
        String cleanData = part.startsWith(CcrConstants.SSE_DATA_PREFIX) ? 
                part.substring(CcrConstants.SSE_DATA_PREFIX.length()) : part;
        
        // 处理流结束标记
        if (cleanData.equals(CcrConstants.SSE_DONE)) {
            log.info("Transformed response (Stream DONE)");
            return ServerSentEvent.<String>builder().data(CcrConstants.SSE_DONE).build();
        }

        // 尝试从 JSON 中解析事件类型 (event 字段)
        String eventType = parseEventType(cleanData, originalEvent);
        log.info("Transformed response (Stream): event={}, data={}", eventType, cleanData);
        return ServerSentEvent.<String>builder()
                .event(eventType)
                .data(cleanData)
                .build();
    }

    /**
     * 尝试从转换后的 JSON 数据中提取 SSE 事件类型
     */
    private String parseEventType(String cleanData, String originalEvent) {
        try {
            JsonNode json = objectMapper.readTree(cleanData);
            if (json.has(CcrConstants.FIELD_TYPE)) {
                return json.get(CcrConstants.FIELD_TYPE).asText();
            }
        } catch (Exception e) {
            log.warn("Exception occurred while parsing SSE data type: {}", e.getMessage());
        }
        return originalEvent;
    }

    /**
     * 非流式（JSON）响应转换逻辑
     */
    private Flux<ServerSentEvent<String>> handleNonStreamingTransformation(org.springframework.web.reactive.function.client.ClientResponse response, 
                                                                            boolean isAnthropicToOpenAi) {
        return response.bodyToMono(String.class)
                .doOnNext(body -> log.info("Upstream response (JSON): {}", body))
                .map(responseBody -> isAnthropicToOpenAi ? 
                        transformerService.transformAnthropicResponseToOpenAi(responseBody) :
                        transformerService.transformOpenAiResponseToAnthropic(responseBody))
                .doOnNext(data -> log.info("Transformed response (JSON): {}", data))
                .map(data -> ServerSentEvent.<String>builder().data(data).build())
                .flux();
    }
}
