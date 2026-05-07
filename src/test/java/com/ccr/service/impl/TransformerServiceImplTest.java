package com.ccr.service.impl;

import com.ccr.constant.CcrConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TransformerServiceImpl 单元测试
 * 验证 OpenAI 与 Anthropic 协议之间的双向转换逻辑
 */
public class TransformerServiceImplTest {

    private TransformerServiceImpl transformerService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        transformerService = new TransformerServiceImpl(objectMapper);
    }

    @Test
    void testTransformOpenAiToAnthropic_Basic() throws Exception {
        String openAiJson = "{\n" +
                "  \"model\": \"gpt-4\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"system\", \"content\": \"You are a helpful assistant.\"},\n" +
                "    {\"role\": \"user\", \"content\": \"Hello!\"}\n" +
                "  ],\n" +
                "  \"max_tokens\": 100\n" +
                "}";

        String result = transformerService.transformOpenAiToAnthropic(openAiJson);
        JsonNode root = objectMapper.readTree(result);

        // 验证系统提示词提取
        assertEquals("You are a helpful assistant.", root.get(CcrConstants.FIELD_SYSTEM).asText());
        // 验证消息角色和内容
        JsonNode messages = root.get(CcrConstants.FIELD_MESSAGES);
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).get("role").asText());
        assertEquals("Hello!", messages.get(0).get("content").asText());
        // 验证模型和 max_tokens
        assertEquals("gpt-4", root.get("model").asText());
        assertEquals(100, root.get("max_tokens").asInt());
    }

    @Test
    void testTransformOpenAiToAnthropic_MaxTokensFallback() throws Exception {
        String openAiJson = "{\n" +
                "  \"model\": \"gpt-4\",\n" +
                "  \"messages\": [{\"role\": \"user\", \"content\": \"Hi\"}]\n" +
                "}";

        String result = transformerService.transformOpenAiToAnthropic(openAiJson);
        JsonNode root = objectMapper.readTree(result);

        // 验证缺失 max_tokens 时自动补全为 4096
        assertEquals(4096, root.get("max_tokens").asInt());
    }

    @Test
    void testTransformAnthropicToOpenAi_Basic() throws Exception {
        String anthropicJson = "{\n" +
                "  \"model\": \"claude-3-opus-20240229\",\n" +
                "  \"system\": \"System prompt here\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"Hello\"}\n" +
                "  ],\n" +
                "  \"max_tokens\": 1024\n" +
                "}";

        String result = transformerService.transformAnthropicToOpenAi(anthropicJson);
        JsonNode root = objectMapper.readTree(result);

        // 验证系统提示词转换为消息数组的第一项
        JsonNode messages = root.get(CcrConstants.FIELD_MESSAGES);
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("System prompt here", messages.get(0).get("content").asText());
        assertEquals("user", messages.get(1).get("role").asText());
        
        assertEquals("claude-3-opus-20240229", root.get("model").asText());
    }
}
