package com.ccr.service.impl;

import com.ccr.constant.CcrConstants;
import com.ccr.service.TransformerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 协议转换服务实现类，负责在 OpenAI 和 Anthropic 两种协议格式之间进行双向转换
 */
@Service
public class TransformerServiceImpl implements TransformerService {
    private static final Logger log = LoggerFactory.getLogger(TransformerServiceImpl.class);
    private final ObjectMapper objectMapper;

    public TransformerServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将 OpenAI 格式的请求体转换为 Anthropic 格式
     * 主要处理：模型名透传、流式开关、最大 Token (必填项补全)、温度、消息数组拆分及 System Prompt 提取
     */
    @Override
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
            } else if (openAiRequest.has("max_completion_tokens")) {
                anthropicRequest.set(CcrConstants.FIELD_MAX_TOKENS, openAiRequest.get("max_completion_tokens"));
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
     * 将 Anthropic 格式的请求体转换为 OpenAI 格式
     * 主要处理：模型名、流式开关、最大 Token、温度以及 System Prompt 转为消息数组首条
     */
    @Override
    public String transformAnthropicToOpenAi(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!(root instanceof ObjectNode)) return body;
            ObjectNode antRequest = (ObjectNode) root;

            ObjectNode openAiRequest = objectMapper.createObjectNode();
            openAiRequest.set(CcrConstants.FIELD_MODEL, antRequest.get(CcrConstants.FIELD_MODEL));
            
            if (antRequest.has(CcrConstants.FIELD_STREAM)) {
                openAiRequest.set(CcrConstants.FIELD_STREAM, antRequest.get(CcrConstants.FIELD_STREAM));
            }
            
            if (antRequest.has(CcrConstants.FIELD_MAX_TOKENS)) {
                openAiRequest.set(CcrConstants.FIELD_MAX_TOKENS, antRequest.get(CcrConstants.FIELD_MAX_TOKENS));
            }
            
            if (antRequest.has(CcrConstants.FIELD_TEMPERATURE)) {
                openAiRequest.set(CcrConstants.FIELD_TEMPERATURE, antRequest.get(CcrConstants.FIELD_TEMPERATURE));
            }

            ArrayNode openAiMessages = objectMapper.createArrayNode();
            
            // Add system prompt as first message if exists
            if (antRequest.has(CcrConstants.FIELD_SYSTEM)) {
                ObjectNode sysMsg = objectMapper.createObjectNode();
                sysMsg.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_SYSTEM);
                sysMsg.set(CcrConstants.FIELD_CONTENT, antRequest.get(CcrConstants.FIELD_SYSTEM));
                openAiMessages.add(sysMsg);
            }

            // Add other messages
            if (antRequest.has(CcrConstants.FIELD_MESSAGES)) {
                ArrayNode antMessages = (ArrayNode) antRequest.get(CcrConstants.FIELD_MESSAGES);
                for (JsonNode msg : antMessages) {
                    openAiMessages.add(msg);
                }
            }
            
            openAiRequest.set(CcrConstants.FIELD_MESSAGES, openAiMessages);
            
            return openAiRequest.toString();
        } catch (Exception e) {
            log.error("Failed to transform Anthropic to OpenAI: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 将 OpenAI 响应转换为 Anthropic 格式 (非流式)
     * 将 OpenAI 的 choices 数组转换为 Anthropic 的 content 数组及 usage 统计
     */
    @Override
    public String transformOpenAiResponseToAnthropic(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            ObjectNode antResp = objectMapper.createObjectNode();
            
            antResp.put(CcrConstants.FIELD_ID, root.has(CcrConstants.FIELD_ID) ? root.get(CcrConstants.FIELD_ID).asText() : "ant-" + UUID.randomUUID());
            antResp.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_TYPE_MESSAGE);
            antResp.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_ASSISTANT);
            antResp.put(CcrConstants.FIELD_MODEL, root.has(CcrConstants.FIELD_MODEL) ? root.get(CcrConstants.FIELD_MODEL).asText() : "unknown");

            ArrayNode choices = (ArrayNode) root.get(CcrConstants.OPENAI_CHOICES);
            ArrayNode contentArray = objectMapper.createArrayNode();
            if (choices != null && choices.size() > 0) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.get("message");
                if (message != null && message.has(CcrConstants.FIELD_CONTENT)) {
                    ObjectNode contentObj = objectMapper.createObjectNode();
                    contentObj.put(CcrConstants.FIELD_TYPE, CcrConstants.FIELD_TEXT);
                    contentObj.put(CcrConstants.FIELD_TEXT, message.get(CcrConstants.FIELD_CONTENT).asText());
                    contentArray.add(contentObj);
                }
                
                String finishReason = firstChoice.has(CcrConstants.OPENAI_FINISH_REASON) ? firstChoice.get(CcrConstants.OPENAI_FINISH_REASON).asText() : null;
                antResp.put(CcrConstants.FIELD_STOP_REASON, CcrConstants.OPENAI_FINISH_REASON_STOP.equals(finishReason) ? CcrConstants.ANT_STOP_REASON_END_TURN : finishReason);
            }
            antResp.set(CcrConstants.FIELD_CONTENT, contentArray);

            if (root.has(CcrConstants.FIELD_USAGE)) {
                ObjectNode usage = objectMapper.createObjectNode();
                JsonNode openAiUsage = root.get(CcrConstants.FIELD_USAGE);
                usage.put(CcrConstants.FIELD_INPUT_TOKENS, openAiUsage.has(CcrConstants.FIELD_PROMPT_TOKENS) ? openAiUsage.get(CcrConstants.FIELD_PROMPT_TOKENS).asInt() : 0);
                usage.put(CcrConstants.FIELD_OUTPUT_TOKENS, openAiUsage.has(CcrConstants.FIELD_COMPLETION_TOKENS) ? openAiUsage.get(CcrConstants.FIELD_COMPLETION_TOKENS).asInt() : 0);
                antResp.set(CcrConstants.FIELD_USAGE, usage);
            } else {
                // Anthropic requires usage field
                ObjectNode usage = objectMapper.createObjectNode();
                usage.put(CcrConstants.FIELD_INPUT_TOKENS, 0);
                usage.put(CcrConstants.FIELD_OUTPUT_TOKENS, 0);
                antResp.set(CcrConstants.FIELD_USAGE, usage);
            }

            return antResp.toString();
        } catch (Exception e) {
            log.error("Failed to transform OpenAI response to Anthropic: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 将 OpenAI SSE 事件转换为 Anthropic SSE 事件
     * 将 OpenAI 的 delta 增量更新包装为 Anthropic 的 message_start, content_block_delta, message_delta 事件
     */
    @Override
    public String transformOpenAiSseToAnthropic(String line) {
        if (line == null || line.isBlank()) return line;
        if (!line.startsWith(CcrConstants.SSE_DATA_PREFIX)) return line;
        String data = line.substring(CcrConstants.SSE_DATA_PREFIX.length()).trim();
        if (CcrConstants.SSE_DONE.equals(data)) return line;

        try {
            JsonNode root = objectMapper.readTree(data);
            ArrayNode choices = (ArrayNode) root.get(CcrConstants.OPENAI_CHOICES);
            if (choices == null || choices.size() == 0) return null;
            
            JsonNode firstChoice = choices.get(0);
            JsonNode delta = firstChoice.get(CcrConstants.FIELD_DELTA);
            String finishReason = firstChoice.has(CcrConstants.OPENAI_FINISH_REASON) && !firstChoice.get(CcrConstants.OPENAI_FINISH_REASON).isNull() 
                    ? firstChoice.get(CcrConstants.OPENAI_FINISH_REASON).asText() : null;

            if (delta != null && delta.has(CcrConstants.FIELD_ROLE)) {
                // message_start
                ObjectNode start = objectMapper.createObjectNode();
                start.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_MESSAGE_START);
                ObjectNode message = objectMapper.createObjectNode();
                message.put(CcrConstants.FIELD_ID, root.has(CcrConstants.FIELD_ID) ? root.get(CcrConstants.FIELD_ID).asText() : "ant-" + UUID.randomUUID());
                message.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_TYPE_MESSAGE);
                message.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_ASSISTANT);
                message.put(CcrConstants.FIELD_MODEL, root.has(CcrConstants.FIELD_MODEL) ? root.get(CcrConstants.FIELD_MODEL).asText() : "unknown");
                message.set(CcrConstants.FIELD_CONTENT, objectMapper.createArrayNode());
                start.set(CcrConstants.FIELD_MESSAGE, message);
                return CcrConstants.SSE_DATA_PREFIX + start.toString() + CcrConstants.SSE_LINE_SEPARATOR;
            } else if (delta != null && delta.has(CcrConstants.FIELD_CONTENT)) {
                // content_block_delta
                ObjectNode content = objectMapper.createObjectNode();
                content.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_DELTA);
                content.put(CcrConstants.FIELD_INDEX, 0);
                ObjectNode d = objectMapper.createObjectNode();
                d.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_TYPE_TEXT_DELTA);
                d.put(CcrConstants.FIELD_TEXT, delta.get(CcrConstants.FIELD_CONTENT).asText());
                content.set(CcrConstants.FIELD_DELTA, d);
                return CcrConstants.SSE_DATA_PREFIX + content.toString() + CcrConstants.SSE_LINE_SEPARATOR;
            } else if (finishReason != null || root.has(CcrConstants.FIELD_USAGE)) {
                // message_delta
                ObjectNode end = objectMapper.createObjectNode();
                end.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_MESSAGE_DELTA);
                ObjectNode d = objectMapper.createObjectNode();
                if (finishReason != null) {
                    d.put(CcrConstants.FIELD_STOP_REASON, CcrConstants.OPENAI_FINISH_REASON_STOP.equals(finishReason) ? CcrConstants.ANT_STOP_REASON_END_TURN : finishReason);
                } else {
                    d.put(CcrConstants.FIELD_STOP_REASON, CcrConstants.ANT_STOP_REASON_END_TURN);
                }
                end.set(CcrConstants.FIELD_DELTA, d);

                // Add usage if available
                ObjectNode usage = objectMapper.createObjectNode();
                if (root.has(CcrConstants.FIELD_USAGE)) {
                    JsonNode openAiUsage = root.get(CcrConstants.FIELD_USAGE);
                    usage.put(CcrConstants.FIELD_INPUT_TOKENS, openAiUsage.has(CcrConstants.FIELD_PROMPT_TOKENS) ? openAiUsage.get(CcrConstants.FIELD_PROMPT_TOKENS).asInt() : 0);
                    usage.put(CcrConstants.FIELD_OUTPUT_TOKENS, openAiUsage.has(CcrConstants.FIELD_COMPLETION_TOKENS) ? openAiUsage.get(CcrConstants.FIELD_COMPLETION_TOKENS).asInt() : 0);
                } else {
                    usage.put(CcrConstants.FIELD_INPUT_TOKENS, 0);
                    usage.put(CcrConstants.FIELD_OUTPUT_TOKENS, 0);
                }
                end.set(CcrConstants.FIELD_USAGE, usage);

                return CcrConstants.SSE_DATA_PREFIX + end.toString() + CcrConstants.SSE_LINE_SEPARATOR;
            }

            return null;
        } catch (Exception e) {
            log.error("Failed to transform OpenAI SSE to Anthropic: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将 Anthropic 响应转换为 OpenAI 格式 (非流式)
     * 将 Anthropic 的 content 数组转回 OpenAI 的 choices 格式
     */
    @Override
    public String transformAnthropicResponseToOpenAi(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            ObjectNode openAiResp = objectMapper.createObjectNode();
            
            openAiResp.put(CcrConstants.FIELD_ID, root.has(CcrConstants.FIELD_ID) ? root.get(CcrConstants.FIELD_ID).asText() : "chatcmpl-" + UUID.randomUUID());
            openAiResp.put(CcrConstants.FIELD_OBJECT, CcrConstants.OPENAI_OBJECT_CHAT_COMPLETION);
            openAiResp.put(CcrConstants.FIELD_CREATED, System.currentTimeMillis() / 1000);
            openAiResp.put(CcrConstants.FIELD_MODEL, root.has(CcrConstants.FIELD_MODEL) ? root.get(CcrConstants.FIELD_MODEL).asText() : "unknown");

            ArrayNode choices = objectMapper.createArrayNode();
            ObjectNode choice = objectMapper.createObjectNode();
            choice.put(CcrConstants.FIELD_INDEX, 0);
            
            ObjectNode message = objectMapper.createObjectNode();
            message.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_ASSISTANT);
            
            JsonNode contentNode = root.get(CcrConstants.FIELD_CONTENT);
            if (contentNode != null && contentNode.isArray() && contentNode.size() > 0) {
                message.put(CcrConstants.FIELD_CONTENT, contentNode.get(0).get(CcrConstants.FIELD_TEXT).asText());
            } else {
                message.put(CcrConstants.FIELD_CONTENT, "");
            }
            
            choice.set(CcrConstants.FIELD_MESSAGE, message);
            choice.put(CcrConstants.OPENAI_FINISH_REASON, CcrConstants.OPENAI_FINISH_REASON_STOP);
            choices.add(choice);
            openAiResp.set(CcrConstants.FIELD_CHOICES, choices);

            if (root.has(CcrConstants.FIELD_USAGE)) {
                ObjectNode usage = objectMapper.createObjectNode();
                JsonNode antUsage = root.get(CcrConstants.FIELD_USAGE);
                usage.put(CcrConstants.FIELD_PROMPT_TOKENS, antUsage.has(CcrConstants.FIELD_INPUT_TOKENS) ? antUsage.get(CcrConstants.FIELD_INPUT_TOKENS).asInt() : 0);
                usage.put(CcrConstants.FIELD_COMPLETION_TOKENS, antUsage.has(CcrConstants.FIELD_OUTPUT_TOKENS) ? antUsage.get(CcrConstants.FIELD_OUTPUT_TOKENS).asInt() : 0);
                usage.put(CcrConstants.FIELD_TOTAL_TOKENS, usage.get(CcrConstants.FIELD_PROMPT_TOKENS).asInt() + usage.get(CcrConstants.FIELD_COMPLETION_TOKENS).asInt());
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
     * 将 Anthropic 的三阶段事件映射回 OpenAI 的 chunk 增量更新
     */
    @Override
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
            openAiChunk.put(CcrConstants.FIELD_OBJECT, CcrConstants.OPENAI_OBJECT_CHAT_COMPLETION_CHUNK);
            openAiChunk.put(CcrConstants.FIELD_CREATED, System.currentTimeMillis() / 1000);
            
            ArrayNode choices = objectMapper.createArrayNode();
            ObjectNode choice = objectMapper.createObjectNode();
            choice.put(CcrConstants.FIELD_INDEX, 0);
            ObjectNode delta = objectMapper.createObjectNode();

            boolean shouldSend = false;
            if (CcrConstants.ANT_EVENT_MESSAGE_START.equals(type)) {
                delta.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_ASSISTANT);
                if (root.has(CcrConstants.FIELD_MESSAGE) && root.get(CcrConstants.FIELD_MESSAGE).has(CcrConstants.FIELD_MODEL)) {
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
