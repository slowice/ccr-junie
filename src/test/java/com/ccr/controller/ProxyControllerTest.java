package com.ccr.controller;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ProxyControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    private static MockWebServer mockBackEnd;

    @BeforeAll
    static void setUp() throws IOException {
        mockBackEnd = new MockWebServer();
        mockBackEnd.start();
    }

    @AfterAll
    static void tearDown() throws IOException {
        mockBackEnd.shutdown();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String baseUrl = "http://localhost:" + mockBackEnd.getPort();
        registry.add("ccr.providers[0].name", () -> "zhipu");
        registry.add("ccr.providers[0].url", () -> baseUrl + "/zhipu/v1/messages");
        registry.add("ccr.providers[0].apiKey", () -> "zhipu-key");
        registry.add("ccr.providers[0].transformer.use[0]", () -> "Anthropic");
        
        registry.add("ccr.providers[1].name", () -> "think-p");
        registry.add("ccr.providers[1].url", () -> baseUrl + "/think/v1/messages");
        registry.add("ccr.providers[1].apiKey", () -> "think-key");
        registry.add("ccr.providers[1].transformer.use[0]", () -> "Anthropic");

        registry.add("ccr.providers[2].name", () -> "openai-p");
        registry.add("ccr.providers[2].url", () -> baseUrl + "/openai/v1/chat/completions");
        registry.add("ccr.providers[2].apiKey", () -> "openai-key");
        registry.add("ccr.providers[2].transformer.use[0]", () -> "OpenAI");

        registry.add("ccr.router.default", () -> "zhipu,model-1");
        registry.add("ccr.router.think", () -> "think-p,model-2");
        registry.add("ccr.router.longContextThreshold", () -> "10");
        registry.add("ccr.router.longContext", () -> "think-p,long-model");
    }

    @BeforeEach
    void cleanRequests() {
        while (mockBackEnd.getRequestCount() > 0) {
            try {
                mockBackEnd.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * 测试 Anthropic 到 OpenAI 的协议转换（Claude Code 使用 OpenAI 后端）。
     * 验证发送 Anthropic 格式的请求（/v1/messages）是否能正确转换为目标供应商（OpenAI）要求的格式，
     * 并将供应商返回的 OpenAI 响应正确转回 Anthropic 格式。
     */
    @Test
    @Order(7)
    void testProxyMessagesToOpenAiBackend() throws Exception {
        // 1. Mock OpenAI Backend Response
        String openAiResponse = "{\"id\":\"chatcmpl-123\",\"object\":\"chat.completion\",\"created\":1677652288,\"model\":\"gpt-3.5-turbo\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Hello Anthropic\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":12,\"total_tokens\":21}}";
        mockBackEnd.enqueue(new MockResponse()
                .setBody(openAiResponse)
                .addHeader("Content-Type", "application/json"));

        // 2. Send Anthropic style request but route it to OpenAI provider (explicitly)
        String antRequest = "{\"model\": \"openai-p,gpt-3.5-turbo\", \"messages\": [{\"role\":\"user\", \"content\":\"Hi\"}], \"system\": \"Sys msg\"}";
        
        Flux<String> result = webTestClient.post()
                .uri("/v1/messages")
                .bodyValue(antRequest)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody();

        StepVerifier.create(result.collectList())
                .assertNext(list -> {
                    String combined = String.join("", list);
                    assertThat(combined).contains("Hello Anthropic");
                    assertThat(combined).contains("assistant");
                    assertThat(combined).contains("usage");
                    assertThat(combined).contains("input_tokens");
                    assertThat(combined).contains("output_tokens");
                })
                .verifyComplete();

        // 3. Verify Request was transformed to OpenAI
        var recordedRequest = mockBackEnd.takeRequest();
        assertThat(recordedRequest.getPath()).isEqualTo("/openai/v1/chat/completions");
        String body = recordedRequest.getBody().readUtf8();
        assertThat(body).contains("\"role\":\"system\",\"content\":\"Sys msg\"");
        assertThat(body).contains("\"role\":\"user\",\"content\":\"Hi\"");
        assertThat(recordedRequest.getHeader("Authorization")).isEqualTo("Bearer openai-key");
    }

    /**
     * 测试 Anthropic 到 OpenAI 的流式响应转发。
     * 验证发送 Anthropic 格式的流式请求时，能否正确解析 OpenAI 供应商返回的 SSE 事件，
     * 并将其转换为 Anthropic 兼容的 SSE 事件流。
     */
    @Test
    @Order(8)
    void testProxyMessagesToOpenAiBackendStreaming() throws Exception {
        // 1. Mock OpenAI SSE events
        String[] events = {
            "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-3.5-turbo\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}\n\n",
            "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-3.5-turbo\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n",
            "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-3.5-turbo\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
            "data: [DONE]\n\n"
        };

        mockBackEnd.enqueue(new MockResponse()
                .setBody(String.join("", events))
                .addHeader("Content-Type", "text/event-stream"));

        // 2. Send Anthropic style streaming request
        String antRequest = "{\"model\": \"openai-p,gpt-3.5-turbo\", \"messages\": [{\"role\":\"user\", \"content\":\"Hi\"}], \"stream\": true}";

        Flux<String> result = webTestClient.post()
                .uri("/v1/messages")
                .bodyValue(antRequest)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody();

        // 3. Verify transformed SSE events
        StepVerifier.create(result.collectList())
                .assertNext(list -> {
                    String fullResponse = String.join("", list);
                    assertThat(fullResponse).contains("message_start");
                    assertThat(fullResponse).contains("content_block_delta");
                    assertThat(fullResponse).contains("Hello");
                    assertThat(fullResponse).contains("message_delta");
                    assertThat(fullResponse).contains("end_turn");
                    assertThat(fullResponse).contains("usage");
                    assertThat(fullResponse).contains("input_tokens");
                    assertThat(fullResponse).contains("output_tokens");
                })
                .verifyComplete();
    }

    /**
     * 测试 OpenAI 兼容接口的协议转换功能。
     * 验证发送 OpenAI 格式的请求（/v1/chat/completions）是否能正确转换为目标供应商（如 Anthropic）要求的格式，
     * 并将供应商返回的 Anthropic 响应正确转回 OpenAI 格式返回给客户端。
     */
    @Test
    @Order(1)
    void testProxyChatCompletionsTransformation() throws Exception {
        // 1. Mock Anthropic Backend Response
        String anthropicResponse = "{\"id\":\"ant-123\",\"model\":\"model-1\",\"content\":[{\"type\":\"text\",\"text\":\"Hello OpenAI\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}";
        mockBackEnd.enqueue(new MockResponse()
                .setBody(anthropicResponse)
                .addHeader("Content-Type", "application/json"));

        // 2. Send OpenAI style request
        String openAiRequest = "{\"model\": \"claude-3\", \"messages\": [{\"role\":\"system\", \"content\":\"Be helpful\"}, {\"role\":\"user\", \"content\":\"Hi\"}]}";
        
        Flux<String> result = webTestClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(openAiRequest)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody();

        StepVerifier.create(result.collectList())
                .assertNext(list -> {
                    String combined = String.join("", list);
                    assertThat(combined).contains("Hello OpenAI");
                    assertThat(combined).contains("30");
                })
                .verifyComplete();

        // 3. Verify Request was transformed to Anthropic
        var recordedRequest = mockBackEnd.takeRequest();
        assertThat(recordedRequest.getPath()).isEqualTo("/zhipu/v1/messages");
        String body = recordedRequest.getBody().readUtf8();
        assertThat(body).contains("\"system\":\"Be helpful\"");
        assertThat(body).contains("\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]");
    }

    /**
     * 测试 OpenAI 兼容接口的流式响应（Streaming）转发。
     * 验证当客户端请求流式输出时，服务端能否正确解析供应商返回的 Anthropic SSE 事件流，
     * 并将其转换为 OpenAI 兼容的流式数据块返回。
     */
    @Test
    @Order(2)
    void testProxyChatCompletionsStreaming() throws Exception {
        // 1. Mock Anthropic SSE events
        String[] events = {
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"model-1\"}}\n\n",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text\",\"text\":\"Hello\"}}\n\n",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text\",\"text\":\" Stream\"}}\n\n",
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n",
            "data: [DONE]\n\n"
        };
        
        MockResponse response = new MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody(String.join("", events));
        mockBackEnd.enqueue(response);

        // 2. Send OpenAI request
        String openAiRequest = "{\"model\": \"claude-3\", \"messages\": [{\"role\":\"user\", \"content\":\"Hi\"}], \"stream\": true}";

        Flux<String> result = webTestClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(openAiRequest)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody();

        StepVerifier.create(result.collectList())
                .assertNext(list -> {
                    String combined = String.join("", list);
                    assertThat(combined).contains("Hello");
                    assertThat(combined).contains(" Stream");
                    assertThat(combined).contains("chat.completion.chunk");
                })
                .verifyComplete();
    }

    /**
     * 验证包含推理内容（reasoning_content）的 OpenAI 流式响应转换。
     * 针对 GLM-5.1 和 MiniMax-2.7 的推理功能进行强化验证。
     */
    @Test
    @Order(3)
    public void testProxyChatCompletionsStreamingWithReasoning() throws Exception {
        String openaiChunk1 = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}\n\n";
        String openaiChunk2 = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"thinking...\"},\"finish_reason\":null}]}\n\n";
        String openaiChunk3 = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"},\"finish_reason\":null}]}\n\n";
        String openaiChunk4 = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\n";
        String openaiDone = "data: [DONE]\n\n";

        mockBackEnd.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(openaiChunk1 + openaiChunk2 + openaiChunk3 + openaiChunk4 + openaiDone));

        // 显式指定使用目标供应商，避免路由选择默认的 zhipu (Anthropic)
        String requestBody = "{\"model\":\"openai-p,glm-5.1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}";

        Flux<String> result = webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody();

        StepVerifier.create(result.collectList())
                .assertNext(body -> {
                    String fullResponse = String.join("", body);

                    // 验证是否包含 message_start
                    assertTrue(fullResponse.contains("message_start"));
                    // 验证是否包含普通内容
                    assertTrue(fullResponse.contains("hello"));
                    // 验证是否包含推理内容对应的 thinking_delta (由 Transformer 转换)
                    assertTrue(fullResponse.contains("thinking_delta"));
                    assertTrue(fullResponse.contains("thinking..."));
                    // 验证是否包含 message_delta 且包含关键的 usage 字段
                    assertTrue(fullResponse.contains("message_delta"));
                    assertTrue(fullResponse.contains("input_tokens"));
                    assertThat(fullResponse).contains("\"input_tokens\":10");
                    assertTrue(fullResponse.contains("output_tokens"));
                    assertThat(fullResponse).contains("\"output_tokens\":5");
                })
                .verifyComplete();
    }

    /**
     * 验证 OpenAI 流式响应在缺失 Usage 字段时的异常处理。
     * 确保即使模型未返回 Token 统计，转换后的 Anthropic 格式仍包含默认的 Usage 字段（0）。
     */
    @Test
    @Order(10)
    public void testProxyChatCompletionsStreamingMissingUsage() throws Exception {
        String openaiChunk1 = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}\n\n";
        String openaiChunk2 = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}\n\n";
        String openaiDone = "data: [DONE]\n\n";

        mockBackEnd.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(openaiChunk1 + openaiChunk2 + openaiDone));

        String requestBody = "{\"model\":\"openai-p,gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}";

        Flux<String> result = webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody();

        StepVerifier.create(result.collectList())
                .assertNext(body -> {
                    String fullResponse = String.join("", body);

                    // 验证是否包含 message_start 且初始 usage 为 0
                    assertTrue(fullResponse.contains("message_start"));
                    assertThat(fullResponse).contains("\"input_tokens\":0");

                    // 验证结束帧 message_delta 是否包含补全的 usage
                    assertTrue(fullResponse.contains("message_delta"), "Response should contain message_delta event. Full response: " + fullResponse);
                    assertThat(fullResponse).contains("\"input_tokens\":0");
                    assertThat(fullResponse).contains("\"output_tokens\":0");
                })
                .verifyComplete();
    }

    /**
     * 测试 Anthropic 消息接口（/v1/messages）的默认路由功能。
     * 验证在未指定 Provider 的情况下，系统是否按配置使用默认的供应商和模型。
     */
    @Test
    @Order(3)
    void testProxyMessagesRoutingDefault() throws Exception {
        mockBackEnd.enqueue(new MockResponse().setBody("default-res"));
        String requestBody = "{\"model\": \"claude-3-sonnet\", \"messages\": []}";

        webTestClient.post().uri("/v1/messages").bodyValue(requestBody).exchange().expectStatus().isOk();

        var req = mockBackEnd.takeRequest();
        assertThat(req.getPath()).isEqualTo("/zhipu/v1/messages");
        assertThat(req.getBody().readUtf8()).contains("\"model\":\"model-1\"");
    }

    /**
     * 测试显式指定供应商的路由逻辑。
     * 验证当模型名称采用 "Provider,Model" 格式时，系统能否正确路由到指定的供应商并提取真实的模型名。
     */
    @Test
    @Order(4)
    void testProxyMessagesExplicitProvider() throws Exception {
        mockBackEnd.enqueue(new MockResponse().setBody("explicit-res"));
        String requestBody = "{\"model\": \"think-p,special-model\", \"messages\": []}";

        webTestClient.post().uri("/v1/messages").bodyValue(requestBody).exchange().expectStatus().isOk();

        // 消耗之前可能排队的请求（如果测试顺序乱了的话，虽然有Order注解但为了保险）
        var req = mockBackEnd.takeRequest();
        assertThat(req.getPath()).isEqualTo("/think/v1/messages");
        assertThat(req.getHeader("x-api-key")).isEqualTo("think-key");
        assertThat(req.getBody().readUtf8()).contains("\"model\":\"special-model\"");
    }

    /**
     * 测试长上下文场景下的自动路由切换。
     * 验证当请求内容超过配置的 token 阈值（longContextThreshold）时，
     * 系统是否能自动切换到为长上下文场景配置的供应商和模型。
     */
    @Test
    @Order(5)
    void testProxyMessagesLongContext() throws Exception {
        mockBackEnd.enqueue(new MockResponse().setBody("long-res"));
        // 超过 threshold 10 (charCount/4 > 10 => charCount > 40)
        String longContent = "A".repeat(100);
        String requestBody = "{\"model\": \"claude-3\", \"messages\": [{\"role\":\"user\", \"content\":\"" + longContent + "\"}]}";

        webTestClient.post().uri("/v1/messages").bodyValue(requestBody).exchange().expectStatus().isOk();

        var req = mockBackEnd.takeRequest();
        assertThat(req.getPath()).isEqualTo("/think/v1/messages"); // router.longContext points to think-p
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"long-model\"");
    }

    /**
     * 测试 Thinking（深度思考）场景下的路由切换。
     * 验证当请求体中包含 "thinking" 字段时，系统是否识别为思考场景并路由到对应的供应商。
     */
    @Test
    @Order(6)
    void testProxyMessagesRoutingThink() throws Exception {
        // 1. 准备 Mock 的响应
        mockBackEnd.enqueue(new MockResponse()
                .setBody("think-response")
                .addHeader("Content-Type", "application/json"));

        // 2. 模拟 Thinking 请求
        String requestBody = "{\"model\": \"glm-4.7\", \"thinking\": {}, \"messages\": [{\"role\": \"user\", \"content\": \"Solve this\"}]}";

        // 3. 执行
        Flux<String> result = webTestClient.post()
                .uri("/v1/messages")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody();

        StepVerifier.create(result.collectList())
                .assertNext(list -> {
                    String combined = String.join("", list);
                    assertThat(combined).contains("think-response");
                })
                .verifyComplete();

        // 4. 验证路径切换到了 thinking-provider 的 URL
        var recordedRequest = mockBackEnd.takeRequest();
        assertThat(recordedRequest.getPath()).isEqualTo("/think/v1/messages");
        assertThat(recordedRequest.getHeader("x-api-key")).isEqualTo("think-key");
    }
    /**
     * 验证 Anthropic 接口在显式指定 Accept: application/json 时的兼容性
     * 防止 Spring 返回 406 Not Acceptable 错误
     */
    @Test
    public void testProxyMessagesWithJsonAcceptHeader() {
        String responseBody = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        
        mockBackEnd.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(responseBody));

        String requestBody = "{\"model\":\"model-1\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";

        webTestClient.post()
                .uri("/v1/messages")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody()
                .consumeWith(result -> {
                    byte[] responseBytes = result.getResponseBody();
                    String body = responseBytes != null ? new String(responseBytes) : "";
                    assertTrue(body.contains("Hello"));
                });
    }
}
