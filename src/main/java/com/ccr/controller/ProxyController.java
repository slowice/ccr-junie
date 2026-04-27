package com.ccr.controller;

import com.ccr.config.CcrConfig;
import com.ccr.service.RouterService;
import com.ccr.service.TransformerService;
import com.ccr.constant.CcrConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Slf4j
@RestController
public class ProxyController {

    private final WebClient webClient;
    private final RouterService routerService;
    private final TransformerService transformerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProxyController(WebClient.Builder webClientBuilder, RouterService routerService, TransformerService transformerService) {
        this.webClient = webClientBuilder.build();
        this.routerService = routerService;
        this.transformerService = transformerService;
    }

    @PostMapping("/v1/messages")
    public Flux<DataBuffer> proxyMessages(@RequestBody String body, ServerHttpResponse response) {
        return proxyRequest(body, false, response);
    }

    @PostMapping("/v1/chat/completions")
    public Flux<DataBuffer> proxyChatCompletions(@RequestBody String body, ServerHttpResponse response) {
        return proxyRequest(body, true, response);
    }

    private Flux<DataBuffer> proxyRequest(String body, boolean isIncomingOpenAi, ServerHttpResponse response) {
        RouterService.RouteResult route = routerService.getRoute(body);
        CcrConfig.Provider provider = route.getProvider();
        String targetModel = route.getTargetModel();
        boolean isOutgoingAnthropic = provider.isAnthropic();

        log.info("Forwarding request to provider: [{}] model: [{}] URL: {} (Anthropic: {})", 
                provider.getName(), targetModel, provider.getUrl(), isOutgoingAnthropic);

        // 1. Transform request body
        String finalBody = body;
        if (isIncomingOpenAi && isOutgoingAnthropic) {
            finalBody = transformerService.transformOpenAiToAnthropic(body);
        }

        // 2. Update model
        try {
            JsonNode root = objectMapper.readTree(finalBody);
            if (root instanceof ObjectNode) {
                ((ObjectNode) root).put(CcrConstants.FIELD_MODEL, targetModel);
                finalBody = root.toString();
            }
        } catch (Exception e) {
            log.error("Failed to update model name: {}", e.getMessage());
        }

        final String requestBody = finalBody;

        return webClient.post()
                .uri(provider.getUrl())
                .header(isOutgoingAnthropic ? CcrConstants.HEADER_X_API_KEY : CcrConstants.HEADER_AUTHORIZATION, 
                        isOutgoingAnthropic ? provider.getApiKey() : CcrConstants.HEADER_BEARER_PREFIX + provider.getApiKey())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBody)
                .exchangeToFlux(res -> {
                    log.debug("Upstream [{}] returned status code: {}", provider.getName(), res.statusCode());
                    response.setStatusCode(res.statusCode());
                    
                    MediaType contentType = res.headers().asHttpHeaders().getContentType();
                    boolean isStreaming = contentType != null && contentType.toString().contains("event-stream");

                    res.headers().asHttpHeaders().forEach((name, values) -> {
                        if (!name.equalsIgnoreCase(HttpHeaders.TRANSFER_ENCODING) && 
                            !name.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH) &&
                            !name.equalsIgnoreCase(HttpHeaders.CONTENT_TYPE)) {
                            response.getHeaders().addAll(name, values);
                        }
                    });
                    
                    if (isIncomingOpenAi && isOutgoingAnthropic) {
                        // Transform response if incoming is OpenAI and outgoing is Anthropic
                        response.getHeaders().setContentType(isStreaming ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON);
                        if (isStreaming) {
                            return res.bodyToFlux(DataBuffer.class)
                                    .flatMap(db -> {
                                        byte[] bytes = new byte[db.readableByteCount()];
                                        db.read(bytes);
                                        org.springframework.core.io.buffer.DataBufferUtils.release(db);
                                        String chunk = new String(bytes, StandardCharsets.UTF_8);
                                        
                                        // Simple handling: split chunk by lines
                                        String[] lines = chunk.split("\n");
                                        return Flux.fromArray(lines)
                                                .map(line -> transformerService.transformAnthropicSseToOpenAi(line + "\n"))
                                                .filter(Objects::nonNull)
                                                .map(line -> response.bufferFactory().wrap(line.getBytes(StandardCharsets.UTF_8)));
                                    });
                        } else {
                            return res.bodyToMono(String.class)
                                    .map(transformerService::transformAnthropicResponseToOpenAi)
                                    .map(b -> response.bufferFactory().wrap(b.getBytes(StandardCharsets.UTF_8)))
                                    .flux();
                        }
                    } else {
                        response.getHeaders().setContentType(contentType);
                        return res.bodyToFlux(DataBuffer.class);
                    }
                });
    }
}
