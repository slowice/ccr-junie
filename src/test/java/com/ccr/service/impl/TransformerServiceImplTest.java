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
    void testTransformOpenAiToAnthropic_basic() throws Exception {
        String openAiBasicResultJson = """
                {
                  "id": "chatcmpl-123",
                  "model": "gpt-4o",
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": "Hello!"
                    },
                    "finish_reason": "stop"
                  }],
                  "usage": {
                    "prompt_tokens": 100,
                    "completion_tokens": 50,
                    "prompt_tokens_details": {
                      "cached_tokens": 30
                    }
                  }
                }
                """;
        // 将openai格式的响应转换为anthropic格式的响应
        String result = transformerService.transformOpenAiResponseToAnthropic(openAiBasicResultJson);
        JsonNode root = objectMapper.readTree(result);

        assertEquals("chatcmpl-123", root.get(CcrConstants.FIELD_ID).asText());
        assertEquals(CcrConstants.ANT_TYPE_MESSAGE, root.get(CcrConstants.FIELD_TYPE).asText());
        assertEquals(CcrConstants.ROLE_ASSISTANT, root.get(CcrConstants.FIELD_ROLE).asText());
        assertEquals("Hello!", root.get(CcrConstants.FIELD_CONTENT).get(0).get(CcrConstants.FIELD_TEXT).asText());
        assertEquals(CcrConstants.ANT_STOP_REASON_END_TURN, root.get(CcrConstants.FIELD_STOP_REASON).asText());

        // 验证 Usage (100 - 30 = 70 input, 30 cached)
        JsonNode usage = root.get(CcrConstants.FIELD_USAGE);
        assertEquals(70, usage.get(CcrConstants.FIELD_INPUT_TOKENS).asInt());
        assertEquals(50, usage.get(CcrConstants.FIELD_OUTPUT_TOKENS).asInt());
        assertEquals(30, usage.get(CcrConstants.FIELD_CACHE_READ_INPUT_TOKENS).asInt());
    }

    @Test
    void testTransformOpenAiToAnthropic_toolCall() throws Exception {
        String openAiToolCallResultJson = """
                {
                  "id": "chatcmpl-456",
                  "model": "gpt-4o",
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "reasoning_content": "I should call weather tool.",
                      "content": "Checking weather...",
                      "tool_calls": [{
                        "id": "call_123",
                        "type": "function",
                        "function": {
                          "name": "get_weather",
                          "arguments": "{\\"location\\":\\"Shanghai\\"}"
                        }
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }]
                }
                """;
        // 将openai格式的响应转换为anthropic格式的响应(包含工具调用)
        String result = transformerService.transformOpenAiResponseToAnthropic(openAiToolCallResultJson);
        JsonNode root = objectMapper.readTree(result);

        JsonNode content = root.get(CcrConstants.FIELD_CONTENT);
        assertEquals(3, content.size());
        assertEquals(CcrConstants.FIELD_THINKING, content.get(0).get(CcrConstants.FIELD_TYPE).asText());
        assertEquals("I should call weather tool.", content.get(0).get(CcrConstants.FIELD_THINKING).asText());
        assertEquals(CcrConstants.FIELD_TEXT, content.get(1).get(CcrConstants.FIELD_TYPE).asText());
        assertEquals("Checking weather...", content.get(1).get(CcrConstants.FIELD_TEXT).asText());
        assertEquals(CcrConstants.ANT_TYPE_TOOL_USE, content.get(2).get(CcrConstants.FIELD_TYPE).asText());
        assertEquals("get_weather", content.get(2).get("name").asText());
        assertEquals("Shanghai", content.get(2).get("input").get("location").asText());
        assertEquals(CcrConstants.ANT_STOP_REASON_TOOL_USE, root.get(CcrConstants.FIELD_STOP_REASON).asText());
    }

    @Test
    void testTransformAnthropicToOpenAi_basic() throws Exception {
        String anthropicBasicResultJson = """
                {
                  "id": "msg_123",
                  "model": "claude-3-opus",
                  "role": "assistant",
                  "content": [{"type": "text", "text": "Hi there!"}],
                  "stop_reason": "end_turn",
                  "usage": {
                    "input_tokens": 20,
                    "output_tokens": 10,
                    "cache_read_input_tokens": 5
                  }
                }
                """;
        // 将anthropic格式的响应转换为openai格式的响应
        String result = transformerService.transformAnthropicResponseToOpenAi(anthropicBasicResultJson);
        JsonNode root = objectMapper.readTree(result);

        assertEquals("msg_123", root.get(CcrConstants.FIELD_ID).asText());
        assertEquals(CcrConstants.OPENAI_OBJECT_CHAT_COMPLETION, root.get(CcrConstants.FIELD_OBJECT).asText());
        assertEquals("Hi there!", root.get(CcrConstants.OPENAI_CHOICES).get(0).get(CcrConstants.FIELD_MESSAGE).get(CcrConstants.FIELD_CONTENT).asText());
        assertEquals(CcrConstants.OPENAI_FINISH_REASON_STOP, root.get(CcrConstants.OPENAI_CHOICES).get(0).get(CcrConstants.OPENAI_FINISH_REASON).asText());
        
        // 验证 Usage (20 + 5 = 25 prompt tokens)
        JsonNode usage = root.get(CcrConstants.FIELD_USAGE);
        assertEquals(25, usage.get(CcrConstants.FIELD_PROMPT_TOKENS).asInt());
        assertEquals(10, usage.get(CcrConstants.FIELD_COMPLETION_TOKENS).asInt());
        assertEquals(35, usage.get(CcrConstants.FIELD_TOTAL_TOKENS).asInt());
        assertEquals(5, usage.get("prompt_tokens_details").get("cached_tokens").asInt());
    }

    @Test
    void testTransformAnthropicToOpenAi_toolCall() throws Exception {
        String anthropicToolCallResultJson = """
                {
                  "id": "msg_789",
                  "model": "claude-3-opus",
                  "role": "assistant",
                  "content": [
                    {"type": "thinking", "thinking": "Let me think..."},
                    {"type": "text", "text": "Thinking done."},
                    {"type": "tool_use", "id": "toolu_1", "name": "get_weather", "input": {"location": "Shanghai"}}
                  ],
                  "stop_reason": "tool_use"
                }
                """;
        // 将anthropic格式的响应转换为openai格式的响应(包含工具调用)
        String result = transformerService.transformAnthropicResponseToOpenAi(anthropicToolCallResultJson);
        JsonNode root = objectMapper.readTree(result);

        JsonNode message = root.get(CcrConstants.OPENAI_CHOICES).get(0).get(CcrConstants.FIELD_MESSAGE);
        assertEquals("Let me think...", message.get(CcrConstants.FIELD_REASONING_CONTENT).asText());
        assertEquals("Thinking done.", message.get(CcrConstants.FIELD_CONTENT).asText());
        assertTrue(message.has(CcrConstants.FIELD_TOOL_CALLS));
        JsonNode toolCall = message.get(CcrConstants.FIELD_TOOL_CALLS).get(0);
        assertEquals("get_weather", toolCall.get("function").get("name").asText());
        assertEquals("{\"location\":\"Shanghai\"}", toolCall.get("function").get("arguments").asText());
        assertEquals(CcrConstants.OPENAI_FINISH_REASON_TOOL_CALLS, root.get(CcrConstants.OPENAI_CHOICES).get(0).get(CcrConstants.OPENAI_FINISH_REASON).asText());
    }
}
