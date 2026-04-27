package com.ccr.service;

import com.ccr.constant.CcrConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
public class TransformerService {
    private final ObjectMapper objectMapper;

    public TransformerService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将 OpenAI 格式的请求体转换为 Anthropic 格式
     */
    public String transformOpenAiToAnthropic(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!(root instanceof ObjectNode)) return body;
            ObjectNode openAiRequest = (ObjectNode) root;

            ObjectNode anthropicRequest = objectMapper.createObjectNode();
            anthropicRequest.set(CcrConstants.FIELD_MODEL, openAiRequest.get(CcrConstants.FIELD_MODEL));
            
            if (openAiRequest.has(CcrConstants.FIELD_STREAM)) {
                anthropicRequest.set(CcrConstants.FIELD_STREAM, openAiRequest.get(CcrConstants.FIELD_STREAM));
            }
            
            if (openAiRequest.has(CcrConstants.FIELD_MAX_TOKENS)) {
                anthropicRequest.set(CcrConstants.FIELD_MAX_TOKENS, openAiRequest.get(CcrConstants.FIELD_MAX_TOKENS));
            } else {
                // Anthropic requires max_tokens
                anthropicRequest.put(CcrConstants.FIELD_MAX_TOKENS, 4096);
            }
            
            if (openAiRequest.has(CcrConstants.FIELD_TEMPERATURE)) {
                anthropicRequest.set(CcrConstants.FIELD_TEMPERATURE, openAiRequest.get(CcrConstants.FIELD_TEMPERATURE));
            }

            ArrayNode openAiMessages = (ArrayNode) openAiRequest.get(CcrConstants.FIELD_MESSAGES);
            ArrayNode anthropicMessages = objectMapper.createArrayNode();
            StringBuilder systemPrompt = new StringBuilder();

            if (openAiMessages != null) {
                for (JsonNode msg : openAiMessages) {
                    String role = msg.get(CcrConstants.FIELD_ROLE).asText();
                    JsonNode content = msg.get(CcrConstants.FIELD_CONTENT);
                    
                    if (CcrConstants.ROLE_SYSTEM.equals(role)) {
                        if (systemPrompt.length() > 0) systemPrompt.append("\n");
                        systemPrompt.append(content.isTextual() ? content.asText() : content.toString());
                    } else {
                        // Pass through user and assistant messages
                        anthropicMessages.add(msg);
                    }
                }
            }
            
            if (systemPrompt.length() > 0) {
                anthropicRequest.put(CcrConstants.FIELD_SYSTEM, systemPrompt.toString());
            }
            anthropicRequest.set(CcrConstants.FIELD_MESSAGES, anthropicMessages);
            
            return anthropicRequest.toString();
        } catch (Exception e) {
            log.error("Failed to transform OpenAI to Anthropic: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 将 Anthropic 响应转换为 OpenAI 格式 (非流式)
     */
    public String transformAnthropicResponseToOpenAi(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            ObjectNode openAiResp = objectMapper.createObjectNode();
            
            openAiResp.put(CcrConstants.FIELD_ID, root.has(CcrConstants.FIELD_ID) ? root.get(CcrConstants.FIELD_ID).asText() : "chatcmpl-" + UUID.randomUUID());
            openAiResp.put("object", CcrConstants.OPENAI_OBJECT_CHAT_COMPLETION);
            openAiResp.put("created", System.currentTimeMillis() / 1000);
            openAiResp.put(CcrConstants.FIELD_MODEL, root.has(CcrConstants.FIELD_MODEL) ? root.get(CcrConstants.FIELD_MODEL).asText() : "unknown");

            ArrayNode choices = objectMapper.createArrayNode();
            ObjectNode choice = objectMapper.createObjectNode();
            choice.put("index", 0);
            
            ObjectNode message = objectMapper.createObjectNode();
            message.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_ASSISTANT);
            
            JsonNode contentNode = root.get(CcrConstants.FIELD_CONTENT);
            if (contentNode != null && contentNode.isArray() && contentNode.size() > 0) {
                message.put(CcrConstants.FIELD_CONTENT, contentNode.get(0).get(CcrConstants.FIELD_TEXT).asText());
            } else {
                message.put(CcrConstants.FIELD_CONTENT, "");
            }
            
            choice.set("message", message);
            choice.put("finish_reason", CcrConstants.OPENAI_FINISH_REASON_STOP);
            choices.add(choice);
            openAiResp.set("choices", choices);

            if (root.has(CcrConstants.FIELD_USAGE)) {
                ObjectNode usage = objectMapper.createObjectNode();
                JsonNode antUsage = root.get(CcrConstants.FIELD_USAGE);
                usage.put("prompt_tokens", antUsage.has("input_tokens") ? antUsage.get("input_tokens").asInt() : 0);
                usage.put("completion_tokens", antUsage.has("output_tokens") ? antUsage.get("output_tokens").asInt() : 0);
                usage.put("total_tokens", usage.get("prompt_tokens").asInt() + usage.get("completion_tokens").asInt());
                openAiResp.set(CcrConstants.FIELD_USAGE, usage);
            }

            return openAiResp.toString();
        } catch (Exception e) {
            log.error("Failed to transform Anthropic response to OpenAI: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 将 Anthropic SSE 事件转换为 OpenAI SSE 事件 (极简版)
     */
    public String transformAnthropicSseToOpenAi(String line) {
        if (line == null || line.isBlank()) return line;
        if (!line.startsWith(CcrConstants.SSE_DATA_PREFIX)) return line;
        String data = line.substring(CcrConstants.SSE_DATA_PREFIX.length()).trim();
        if (CcrConstants.SSE_DONE.equals(data)) return line;

        try {
            JsonNode root = objectMapper.readTree(data);
            String type = root.has(CcrConstants.FIELD_TYPE) ? root.get(CcrConstants.FIELD_TYPE).asText() : "";
            
            ObjectNode openAiChunk = objectMapper.createObjectNode();
            openAiChunk.put(CcrConstants.FIELD_ID, "chatcmpl-" + UUID.randomUUID());
            openAiChunk.put("object", CcrConstants.OPENAI_OBJECT_CHAT_COMPLETION_CHUNK);
            openAiChunk.put("created", System.currentTimeMillis() / 1000);
            
            ArrayNode choices = objectMapper.createArrayNode();
            ObjectNode choice = objectMapper.createObjectNode();
            choice.put("index", 0);
            ObjectNode delta = objectMapper.createObjectNode();

            boolean shouldSend = false;
            if (CcrConstants.ANT_EVENT_MESSAGE_START.equals(type)) {
                delta.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_ASSISTANT);
                if (root.has("message") && root.get("message").has(CcrConstants.FIELD_MODEL)) {
                    openAiChunk.put(CcrConstants.FIELD_MODEL, root.get("message").get(CcrConstants.FIELD_MODEL).asText());
                }
                shouldSend = true;
            } else if (CcrConstants.ANT_EVENT_CONTENT_BLOCK_DELTA.equals(type)) {
                delta.put(CcrConstants.FIELD_CONTENT, root.get(CcrConstants.FIELD_DELTA).get(CcrConstants.FIELD_TEXT).asText());
                shouldSend = true;
            } else if (CcrConstants.ANT_EVENT_MESSAGE_DELTA.equals(type)) {
                choice.put("finish_reason", CcrConstants.OPENAI_FINISH_REASON_STOP);
                shouldSend = true;
            }

            if (!shouldSend) return null;

            choice.set(CcrConstants.FIELD_DELTA, delta);
            choices.add(choice);
            openAiChunk.set("choices", choices);
            
            return CcrConstants.SSE_DATA_PREFIX + openAiChunk.toString() + "\n\n";
        } catch (Exception e) {
            log.error("Failed to transform Anthropic SSE to OpenAI: {}", e.getMessage());
            return line;
        }
    }
}
