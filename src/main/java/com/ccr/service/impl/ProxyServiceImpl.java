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

    private String transformRequest(String body, boolean isIncomingOpenAi, boolean isOutgoingAnthropic, String targetModel) {
        log.info("Transforming request: isIncomingOpenAi={}, isOutgoingAnthropic={}, targetModel={}", isIncomingOpenAi, isOutgoingAnthropic, targetModel);
        log.debug("Original request body: {}", body);
        
        String transformedBody = performProtocolTransformation(body, isIncomingOpenAi, isOutgoingAnthropic);
        String finalBody = updateTargetModel(transformedBody, targetModel);
        
        log.info("Final transformed request body: {}", finalBody);
        return finalBody;
    }

    private String performProtocolTransformation(String body, boolean isIncomingOpenAi, boolean isOutgoingAnthropic) {
        // 如果输入是 OpenAI 格式但输出是 Anthropic 格式，进行转换
        if (isIncomingOpenAi && isOutgoingAnthropic) {
            return transformerService.transformOpenAiToAnthropic(body);
        } 
        // 如果输入是 Anthropic 格式但输出是 OpenAI 格式，进行转换
        else if (!isIncomingOpenAi && !isOutgoingAnthropic) {
            return transformerService.transformAnthropicToOpenAi(body);
        }
        return body;
    }

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
     */
    private Flux<ServerSentEvent<String>> forwardToUpstream(String requestBody, CcrConfig.Provider provider, 
                                             boolean isIncomingOpenAi, boolean isOutgoingAnthropic) {
        StreamContext streamContext = new StreamContext();
        return webClient.post()
                .uri(provider.getUrl())
                .header(getAuthHeaderName(isOutgoingAnthropic), getAuthHeaderValue(provider, isOutgoingAnthropic))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBody)
                .exchangeToFlux(response -> processUpstreamResponse(response, provider, isIncomingOpenAi, isOutgoingAnthropic, streamContext));
    }

    private String getAuthHeaderName(boolean isOutgoingAnthropic) {
        return isOutgoingAnthropic ? CcrConstants.HEADER_X_API_KEY : CcrConstants.HEADER_AUTHORIZATION;
    }

    private String getAuthHeaderValue(CcrConfig.Provider provider, boolean isOutgoingAnthropic) {
        return isOutgoingAnthropic ? provider.getApiKey() : CcrConstants.HEADER_BEARER_PREFIX + provider.getApiKey();
    }

    private Flux<ServerSentEvent<String>> processUpstreamResponse(org.springframework.web.reactive.function.client.ClientResponse response, 
                                                                  CcrConfig.Provider provider, 
                                                                  boolean isIncomingOpenAi, 
                                                                  boolean isOutgoingAnthropic, 
                                                                  StreamContext streamContext) {
        log.debug("Upstream [{}] returned status code: {}", provider.getName(), response.statusCode());
        
        MediaType contentType = response.headers().asHttpHeaders().getContentType();
        boolean isStreaming = contentType != null && contentType.toString().contains(CcrConstants.MEDIA_TYPE_EVENT_STREAM);

        if (isIncomingOpenAi == isOutgoingAnthropic) {
            log.info("Converting response: IncomingOpenAi={}, OutgoingAnthropic={}", isIncomingOpenAi, isOutgoingAnthropic);
            return handleResponseTransformation(response, isStreaming, isIncomingOpenAi, streamContext);
        } else {
            return handlePassthroughResponse(response, isStreaming);
        }
    }

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

    private Flux<ServerSentEvent<String>> handleStreamingTransformation(org.springframework.web.reactive.function.client.ClientResponse response, 
                                                                         boolean isAnthropicToOpenAi, 
                                                                         StreamContext streamContext) {
        return response.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .flatMap(sseEvent -> {
                    String data = sseEvent.data();
                    log.info("Upstream response (Stream): event={}, data={}", sseEvent.event(), data);
                    if (data == null) return Flux.empty();
                    
                    String input = CcrConstants.SSE_DATA_PREFIX + data + CcrConstants.SSE_LINE_SEPARATOR;
                    String transformed = isAnthropicToOpenAi ? 
                            transformerService.transformAnthropicSseToOpenAi(input) :
                            transformerService.transformOpenAiSseToAnthropic(input, streamContext);
                    
                    return processTransformedSse(transformed, sseEvent.event());
                });
    }

    private Flux<ServerSentEvent<String>> processTransformedSse(String transformed, String originalEvent) {
        if (transformed == null) {
            log.info("Transformed response (Stream) is null, skipping");
            return Flux.empty();
        }

        String[] parts = transformed.split(CcrConstants.SSE_LINE_SEPARATOR);
        return Flux.fromArray(parts)
                .filter(part -> !part.isBlank())
                .map(part -> buildServerSentEvent(part, originalEvent));
    }

    private ServerSentEvent<String> buildServerSentEvent(String part, String originalEvent) {
        String cleanData = part.startsWith(CcrConstants.SSE_DATA_PREFIX) ? 
                part.substring(CcrConstants.SSE_DATA_PREFIX.length()) : part;
        
        if (cleanData.equals(CcrConstants.SSE_DONE)) {
            log.info("Transformed response (Stream DONE)");
            return ServerSentEvent.<String>builder().data(CcrConstants.SSE_DONE).build();
        }

        String eventType = parseEventType(cleanData, originalEvent);
        log.info("Transformed response (Stream): event={}, data={}", eventType, cleanData);
        return ServerSentEvent.<String>builder()
                .event(eventType)
                .data(cleanData)
                .build();
    }

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
