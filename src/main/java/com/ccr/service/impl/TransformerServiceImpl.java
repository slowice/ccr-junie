package com.ccr.service.impl;

import com.ccr.constant.CcrConstants;
import com.ccr.model.StreamContext;
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
                    if (!msg.has(CcrConstants.FIELD_ROLE)) continue;
                    String role = msg.get(CcrConstants.FIELD_ROLE).asText();
                    JsonNode content = msg.get(CcrConstants.FIELD_CONTENT);
                    
                    if (CcrConstants.ROLE_SYSTEM.equals(role)) {
                        if (systemPrompt.length() > 0) systemPrompt.append("\n");
                        systemPrompt.append(content.isTextual() ? content.asText() : content.toString());
                    } else {
                        // 转换消息内容为符合 Anthropic 要求的格式
                        ObjectNode transformedMsg = (ObjectNode) msg.deepCopy();
                        if (content != null && content.isArray()) {
                            ArrayNode newContent = objectMapper.createArrayNode();
                            for (JsonNode item : content) {
                                if (item.isTextual()) {
                                    ObjectNode textItem = objectMapper.createObjectNode();
                                    textItem.put("type", "text");
                                    textItem.put("text", item.asText());
                                    newContent.add(textItem);
                                } else if (item.isObject()) {
                                    newContent.add(item);
                                }
                            }
                            transformedMsg.set(CcrConstants.FIELD_CONTENT, newContent);
                        }
                        anthropicMessages.add(transformedMsg);
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
            
            // 1. Add system prompt as first message if exists
            if (antRequest.has(CcrConstants.FIELD_SYSTEM)) {
                JsonNode system = antRequest.get(CcrConstants.FIELD_SYSTEM);
                ObjectNode sysMsg = objectMapper.createObjectNode();
                sysMsg.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_SYSTEM);
                
                if (system.isTextual()) {
                    sysMsg.set(CcrConstants.FIELD_CONTENT, system);
                } else if (system.isArray()) {
                    // Anthropic 允许 system 是数组，转为 OpenAI 字符串或保持数组（如果 OpenAI 支持）
                    // 为了兼容性，转为字符串
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode node : system) {
                        if (node.has(CcrConstants.FIELD_TEXT)) {
                            sb.append(node.get(CcrConstants.FIELD_TEXT).asText());
                        }
                    }
                    sysMsg.put(CcrConstants.FIELD_CONTENT, sb.toString());
                } else {
                    sysMsg.set(CcrConstants.FIELD_CONTENT, system);
                }
                openAiMessages.add(sysMsg);
            }

            // 2. Add other messages and handle tool_result to tool role
            if (antRequest.has(CcrConstants.FIELD_MESSAGES)) {
                ArrayNode antMessages = (ArrayNode) antRequest.get(CcrConstants.FIELD_MESSAGES);
                for (JsonNode msg : antMessages) {
                    if (msg.isObject()) {
                        ObjectNode msgObj = (ObjectNode) msg;
                        JsonNode content = msgObj.get(CcrConstants.FIELD_CONTENT);
                        
                        if (content != null && content.isArray()) {
                            // 处理工具调用结果 (tool_result -> tool role)
                            ArrayNode contentArray = (ArrayNode) content;
                            boolean hasToolResult = false;
                            for (JsonNode item : contentArray) {
                                if (item.has(CcrConstants.FIELD_TYPE) && "tool_result".equals(item.get(CcrConstants.FIELD_TYPE).asText())) {
                                    hasToolResult = true;
                                    ObjectNode toolMsg = objectMapper.createObjectNode();
                                    toolMsg.put(CcrConstants.FIELD_ROLE, "tool");
                                    toolMsg.put("tool_call_id", item.path("tool_use_id").asText());
                                    
                                    JsonNode toolContent = item.get(CcrConstants.FIELD_CONTENT);
                                    if (toolContent != null) {
                                        if (toolContent.isTextual()) {
                                            toolMsg.set(CcrConstants.FIELD_CONTENT, toolContent);
                                        } else {
                                            toolMsg.put(CcrConstants.FIELD_CONTENT, toolContent.toString());
                                        }
                                    }
                                    openAiMessages.add(toolMsg);
                                }
                            }
                            
                            // 处理 assistant 的 tool_use -> tool_calls
                            if ("assistant".equals(msgObj.path(CcrConstants.FIELD_ROLE).asText())) {
                                ArrayNode toolCalls = objectMapper.createArrayNode();
                                boolean hasToolUse = false;
                                StringBuilder textContent = new StringBuilder();
                                
                                for (JsonNode item : contentArray) {
                                    String type = item.path(CcrConstants.FIELD_TYPE).asText();
                                    if ("tool_use".equals(type)) {
                                        hasToolUse = true;
                                        ObjectNode toolCall = objectMapper.createObjectNode();
                                        toolCall.put(CcrConstants.FIELD_TYPE, "function");
                                        toolCall.put(CcrConstants.FIELD_ID, item.path(CcrConstants.FIELD_ID).asText());
                                        ObjectNode function = objectMapper.createObjectNode();
                                        function.put("name", item.path("name").asText());
                                        function.put("arguments", item.path("input").toString());
                                        toolCall.set("function", function);
                                        toolCalls.add(toolCall);
                                    } else if (CcrConstants.FIELD_TEXT.equals(type)) {
                                        textContent.append(item.path(CcrConstants.FIELD_TEXT).asText());
                                    }
                                }
                                
                                if (hasToolUse) {
                                    ObjectNode assistantMsg = objectMapper.createObjectNode();
                                    assistantMsg.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_ASSISTANT);
                                    if (textContent.length() > 0) {
                                        assistantMsg.put(CcrConstants.FIELD_CONTENT, textContent.toString());
                                    } else {
                                        assistantMsg.putNull(CcrConstants.FIELD_CONTENT);
                                    }
                                    assistantMsg.set(CcrConstants.FIELD_TOOL_CALLS, toolCalls);
                                    openAiMessages.add(assistantMsg);
                                    continue; // 已处理
                                }
                            }
                            
                            if (hasToolResult) continue; // 已作为 tool role 添加
                        }
                    }
                    openAiMessages.add(msg.deepCopy());
                }
            }
            
            openAiRequest.set(CcrConstants.FIELD_MESSAGES, openAiMessages);

            // 3. Handle Tools
            if (antRequest.has(CcrConstants.FIELD_TOOLS)) {
                ArrayNode antTools = (ArrayNode) antRequest.get(CcrConstants.FIELD_TOOLS);
                ArrayNode openAiTools = objectMapper.createArrayNode();
                for (JsonNode antTool : antTools) {
                    ObjectNode openAiTool = objectMapper.createObjectNode();
                    openAiTool.put(CcrConstants.FIELD_TYPE, "function");
                    ObjectNode function = objectMapper.createObjectNode();
                    function.set("name", antTool.get("name"));
                    function.set("description", antTool.get("description"));
                    function.set("parameters", antTool.get("input_schema"));
                    openAiTool.set("function", function);
                    openAiTools.add(openAiTool);
                }
                openAiRequest.set(CcrConstants.FIELD_TOOLS, openAiTools);
            }

            // 4. Handle Tool Choice
            if (antRequest.has("tool_choice")) {
                JsonNode antToolChoice = antRequest.get("tool_choice");
                if (antToolChoice.isObject()) {
                    String type = antToolChoice.path(CcrConstants.FIELD_TYPE).asText();
                    if ("tool".equals(type)) {
                        ObjectNode openAiToolChoice = objectMapper.createObjectNode();
                        openAiToolChoice.put(CcrConstants.FIELD_TYPE, "function");
                        ObjectNode function = objectMapper.createObjectNode();
                        function.set("name", antToolChoice.get("name"));
                        openAiToolChoice.set("function", function);
                        openAiRequest.set("tool_choice", openAiToolChoice);
                    } else {
                        openAiRequest.put("tool_choice", type);
                    }
                } else {
                    openAiRequest.set("tool_choice", antToolChoice);
                }
            }
            
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
                JsonNode message = firstChoice.get(CcrConstants.FIELD_MESSAGE);
                
                // 1. 处理推理内容 (Thinking)
                if (message != null && message.has(CcrConstants.FIELD_REASONING_CONTENT) && !message.get(CcrConstants.FIELD_REASONING_CONTENT).isNull()) {
                    ObjectNode thinkingObj = objectMapper.createObjectNode();
                    thinkingObj.put(CcrConstants.FIELD_TYPE, CcrConstants.FIELD_THINKING);
                    thinkingObj.put(CcrConstants.FIELD_THINKING, message.get(CcrConstants.FIELD_REASONING_CONTENT).asText());
                    thinkingObj.put("signature", "sign_" + UUID.randomUUID().toString().substring(0, 8));
                    contentArray.add(thinkingObj);
                }

                // 2. 处理文本内容或已有的 content 数组
                if (message != null && message.has(CcrConstants.FIELD_CONTENT) && !message.get(CcrConstants.FIELD_CONTENT).isNull()) {
                    JsonNode contentNode = message.get(CcrConstants.FIELD_CONTENT);
                    if (contentNode.isTextual()) {
                        String text = contentNode.asText();
                        if (!text.isEmpty()) {
                            ObjectNode contentObj = objectMapper.createObjectNode();
                            contentObj.put(CcrConstants.FIELD_TYPE, CcrConstants.FIELD_TEXT);
                            contentObj.put(CcrConstants.FIELD_TEXT, text);
                            contentArray.add(contentObj);
                        }
                    } else if (contentNode.isArray()) {
                        for (JsonNode item : contentNode) {
                            if (item.isObject()) {
                                ObjectNode itemObj = item.deepCopy();
                                // 如果是 thinking 块且缺少 signature，补上
                                if (CcrConstants.FIELD_THINKING.equals(itemObj.path(CcrConstants.FIELD_TYPE).asText()) 
                                        && !itemObj.has("signature")) {
                                    itemObj.put("signature", "sign_" + UUID.randomUUID().toString().substring(0, 8));
                                }
                                contentArray.add(itemObj);
                            } else {
                                contentArray.add(item);
                            }
                        }
                    }
                }

                // 3. 处理工具调用 (Tool Calls)
                if (message != null && message.has(CcrConstants.FIELD_TOOL_CALLS)) {
                    ArrayNode toolCalls = (ArrayNode) message.get(CcrConstants.FIELD_TOOL_CALLS);
                    for (JsonNode toolCall : toolCalls) {
                        ObjectNode toolUseObj = objectMapper.createObjectNode();
                        toolUseObj.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_TYPE_TOOL_USE);
                        toolUseObj.put(CcrConstants.FIELD_ID, toolCall.get(CcrConstants.FIELD_ID).asText());
                        JsonNode function = toolCall.get("function");
                        toolUseObj.put("name", function.get("name").asText());
                        try {
                            toolUseObj.set("input", objectMapper.readTree(function.get("arguments").asText()));
                        } catch (Exception e) {
                            toolUseObj.put("input", function.get("arguments").asText());
                        }
                        contentArray.add(toolUseObj);
                    }
                }
                
                String finishReason = firstChoice.has(CcrConstants.OPENAI_FINISH_REASON) ? firstChoice.get(CcrConstants.OPENAI_FINISH_REASON).asText() : null;
                antResp.put(CcrConstants.FIELD_STOP_REASON, mapOpenAiFinishReasonToAnthropic(finishReason));
                antResp.putNull(CcrConstants.FIELD_STOP_SEQUENCE);
            }
            antResp.set(CcrConstants.FIELD_CONTENT, contentArray);

            if (root.has(CcrConstants.FIELD_USAGE)) {
                ObjectNode usage = objectMapper.createObjectNode();
                JsonNode openAiUsage = root.get(CcrConstants.FIELD_USAGE);
                usage.put(CcrConstants.FIELD_INPUT_TOKENS, openAiUsage.has(CcrConstants.FIELD_PROMPT_TOKENS) ? openAiUsage.get(CcrConstants.FIELD_PROMPT_TOKENS).asInt() : 0);
                usage.put(CcrConstants.FIELD_OUTPUT_TOKENS, openAiUsage.has(CcrConstants.FIELD_COMPLETION_TOKENS) ? openAiUsage.get(CcrConstants.FIELD_COMPLETION_TOKENS).asInt() : 0);
                
                // 处理缓存命中 tokens (如有)
                if (openAiUsage.has("prompt_tokens_details") && openAiUsage.get("prompt_tokens_details").has("cached_tokens")) {
                    int cached = openAiUsage.get("prompt_tokens_details").get("cached_tokens").asInt();
                    usage.put("cache_read_input_tokens", cached);
                    // Anthropic 的 input_tokens 通常不包含缓存部分
                    usage.put(CcrConstants.FIELD_INPUT_TOKENS, Math.max(0, usage.get(CcrConstants.FIELD_INPUT_TOKENS).asInt() - cached));
                }
                
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
    public String transformOpenAiSseToAnthropic(String line, StreamContext context) {
        if (line == null || line.isBlank()) return line;
        if (!line.startsWith(CcrConstants.SSE_DATA_PREFIX)) return line;
        String data = line.substring(CcrConstants.SSE_DATA_PREFIX.length()).trim();
        if (CcrConstants.SSE_DONE.equals(data)) {
            // OpenAI [DONE] 映射为 Anthropic message_stop
            ObjectNode stop = objectMapper.createObjectNode();
            stop.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_MESSAGE_STOP);
            return CcrConstants.SSE_DATA_PREFIX + stop.toString() + CcrConstants.SSE_LINE_SEPARATOR;
        }

        try {
            JsonNode root = objectMapper.readTree(data);
            ArrayNode choices = (ArrayNode) root.get(CcrConstants.OPENAI_CHOICES);
            if (choices == null || choices.size() == 0) return null;
            
            JsonNode firstChoice = choices.get(0);
            JsonNode delta = firstChoice.get(CcrConstants.FIELD_DELTA);
            String finishReason = firstChoice.has(CcrConstants.OPENAI_FINISH_REASON) && !firstChoice.get(CcrConstants.OPENAI_FINISH_REASON).isNull() 
                    ? firstChoice.get(CcrConstants.OPENAI_FINISH_REASON).asText() : null;

            StringBuilder sb = new StringBuilder();

            // 1. 发送 message_start (如果尚未发送)
            if (!context.isMessageStarted()) {
                ObjectNode start = objectMapper.createObjectNode();
                start.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_MESSAGE_START);
                ObjectNode message = objectMapper.createObjectNode();
                message.put(CcrConstants.FIELD_ID, root.has(CcrConstants.FIELD_ID) ? root.get(CcrConstants.FIELD_ID).asText() : "ant-" + UUID.randomUUID());
                message.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_TYPE_MESSAGE);
                message.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_ASSISTANT);
                String model = root.has(CcrConstants.FIELD_MODEL) ? root.get(CcrConstants.FIELD_MODEL).asText() : "unknown";
                message.put(CcrConstants.FIELD_MODEL, model);
                context.setModel(model);
                message.set(CcrConstants.FIELD_CONTENT, objectMapper.createArrayNode());
                message.putNull(CcrConstants.FIELD_STOP_REASON);
                message.putNull(CcrConstants.FIELD_STOP_SEQUENCE);
                
                ObjectNode usage = objectMapper.createObjectNode();
                usage.put(CcrConstants.FIELD_INPUT_TOKENS, 0);
                usage.put(CcrConstants.FIELD_OUTPUT_TOKENS, 0);
                message.set(CcrConstants.FIELD_USAGE, usage);
                
                start.set(CcrConstants.FIELD_MESSAGE, message);
                sb.append(CcrConstants.SSE_DATA_PREFIX).append(start.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
                context.setMessageStarted(true);
            }

            if (delta != null) {
                // 2. 处理推理内容 (Thinking)
                if (delta.has(CcrConstants.FIELD_REASONING_CONTENT) && !delta.get(CcrConstants.FIELD_REASONING_CONTENT).isNull()) {
                    String thinkingContent = delta.get(CcrConstants.FIELD_REASONING_CONTENT).asText();
                    if (!thinkingContent.isEmpty()) {
                        if (!context.isThinkingStarted()) {
                            // content_block_start for thinking
                            int blockIndex = context.getNextBlockIndex();
                            context.setThinkingBlockIndex(blockIndex);
                            context.setNextBlockIndex(blockIndex + 1);
                            
                            ObjectNode cbStart = objectMapper.createObjectNode();
                            cbStart.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_START);
                            cbStart.put(CcrConstants.FIELD_INDEX, blockIndex);
                            ObjectNode cb = objectMapper.createObjectNode();
                            cb.put(CcrConstants.FIELD_TYPE, CcrConstants.FIELD_THINKING);
                            cb.put(CcrConstants.FIELD_THINKING, "");
                            cb.put("signature", "sign_" + UUID.randomUUID().toString().substring(0, 8));
                            cbStart.set("content_block", cb);
                            sb.append(CcrConstants.SSE_DATA_PREFIX).append(cbStart.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
                            context.setThinkingStarted(true);
                            context.setCurrentBlockIndex(blockIndex);
                        }
                        
                        // content_block_delta
                        ObjectNode cbDelta = objectMapper.createObjectNode();
                        cbDelta.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_DELTA);
                        cbDelta.put(CcrConstants.FIELD_INDEX, context.getThinkingBlockIndex());
                        ObjectNode d = objectMapper.createObjectNode();
                        d.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_TYPE_THINKING_DELTA);
                        d.put(CcrConstants.FIELD_THINKING, thinkingContent);
                        cbDelta.set(CcrConstants.FIELD_DELTA, d);
                        sb.append(CcrConstants.SSE_DATA_PREFIX).append(cbDelta.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
                    }
                }

                // 3. 处理普通文本 (Text)
                if (delta.has(CcrConstants.FIELD_CONTENT) && !delta.get(CcrConstants.FIELD_CONTENT).isNull()) {
                    String content = delta.get(CcrConstants.FIELD_CONTENT).asText();
                    if (!content.isEmpty()) {
                        // 如果之前在做 Thinking 或 Tool Call，现在转 Text，需要发 Stop
                        if (context.isThinkingStarted() || (context.getCurrentBlockIndex() != -1 && !context.isTextStarted())) {
                            ObjectNode cbStop = objectMapper.createObjectNode();
                            cbStop.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_STOP);
                            cbStop.put(CcrConstants.FIELD_INDEX, context.getCurrentBlockIndex());
                            sb.append(CcrConstants.SSE_DATA_PREFIX).append(cbStop.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
                            context.setThinkingStarted(false);
                            context.setCurrentBlockIndex(-1);
                        }
                        
                        if (!context.isTextStarted()) {
                            // content_block_start for text
                            int blockIndex = context.getNextBlockIndex();
                            context.setTextBlockIndex(blockIndex);
                            context.setNextBlockIndex(blockIndex + 1);
                            
                            ObjectNode cbStart = objectMapper.createObjectNode();
                            cbStart.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_START);
                            cbStart.put(CcrConstants.FIELD_INDEX, blockIndex);
                            ObjectNode cb = objectMapper.createObjectNode();
                            cb.put(CcrConstants.FIELD_TYPE, CcrConstants.FIELD_TEXT);
                            cb.put(CcrConstants.FIELD_TEXT, "");
                            cbStart.set("content_block", cb);
                            sb.append(CcrConstants.SSE_DATA_PREFIX).append(cbStart.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
                            context.setTextStarted(true);
                            context.setCurrentBlockIndex(blockIndex);
                        }
                        
                        // content_block_delta
                        ObjectNode cbDelta = objectMapper.createObjectNode();
                        cbDelta.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_DELTA);
                        cbDelta.put(CcrConstants.FIELD_INDEX, context.getTextBlockIndex());
                        ObjectNode d = objectMapper.createObjectNode();
                        d.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_TYPE_TEXT_DELTA);
                        d.put(CcrConstants.FIELD_TEXT, content);
                        cbDelta.set(CcrConstants.FIELD_DELTA, d);
                        sb.append(CcrConstants.SSE_DATA_PREFIX).append(cbDelta.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
                    }
                }

                // 4. 处理工具调用 (Tool Calls)
                if (delta.has(CcrConstants.FIELD_TOOL_CALLS)) {
                    ArrayNode toolCalls = (ArrayNode) delta.get(CcrConstants.FIELD_TOOL_CALLS);
                    for (int i = 0; i < toolCalls.size(); i++) {
                        JsonNode toolCall = toolCalls.get(i);
                        int toolCallIndex = toolCall.has(CcrConstants.FIELD_INDEX) ? toolCall.get(CcrConstants.FIELD_INDEX).asInt() : 0;
                        
                        if (!context.getToolCallIndexToContentBlockIndex().containsKey(toolCallIndex)) {
                            // 这是一个新的工具调用
                            // 如果之前在做 Thinking 或 Text 或其他 Tool Call，发送 Stop
                            if (context.getCurrentBlockIndex() != -1) {
                                ObjectNode cbStop = objectMapper.createObjectNode();
                                cbStop.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_STOP);
                                cbStop.put(CcrConstants.FIELD_INDEX, context.getCurrentBlockIndex());
                                sb.append(CcrConstants.SSE_DATA_PREFIX).append(cbStop.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
                                context.setThinkingStarted(false);
                                context.setTextStarted(false);
                                context.setCurrentBlockIndex(-1);
                            }
                            
                            int blockIndex = context.getNextBlockIndex();
                            context.setNextBlockIndex(blockIndex + 1);
                            context.getToolCallIndexToContentBlockIndex().put(toolCallIndex, blockIndex);
                            
                            // content_block_start
                            ObjectNode cbStart = objectMapper.createObjectNode();
                            cbStart.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_START);
                            cbStart.put(CcrConstants.FIELD_INDEX, blockIndex);
                            ObjectNode cb = objectMapper.createObjectNode();
                            cb.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_TYPE_TOOL_USE);
                            cb.put(CcrConstants.FIELD_ID, toolCall.has(CcrConstants.FIELD_ID) ? toolCall.get(CcrConstants.FIELD_ID).asText() : "toolu_" + UUID.randomUUID().toString().substring(0, 8));
                            cb.put("name", toolCall.has("function") && toolCall.get("function").has("name") ? toolCall.get("function").get("name").asText() : "unknown");
                            cb.set("input", objectMapper.createObjectNode());
                            cbStart.set("content_block", cb);
                            sb.append(CcrConstants.SSE_DATA_PREFIX).append(cbStart.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
                            context.setCurrentBlockIndex(blockIndex);
                        }
                        
                        if (toolCall.has("function") && toolCall.get("function").has("arguments")) {
                            String arguments = toolCall.get("function").get("arguments").asText();
                            if (!arguments.isEmpty()) {
                                // content_block_delta
                                int blockIndex = context.getToolCallIndexToContentBlockIndex().get(toolCallIndex);
                                ObjectNode cbDelta = objectMapper.createObjectNode();
                                cbDelta.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_DELTA);
                                cbDelta.put(CcrConstants.FIELD_INDEX, blockIndex);
                                ObjectNode d = objectMapper.createObjectNode();
                                d.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_TYPE_INPUT_JSON_DELTA);
                                d.put("partial_json", arguments);
                                cbDelta.set(CcrConstants.FIELD_DELTA, d);
                                sb.append(CcrConstants.SSE_DATA_PREFIX).append(cbDelta.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
                                context.setCurrentBlockIndex(blockIndex);
                            }
                        }
                    }
                }
            }

            // 5. 处理结束信号 (Finish Reason or Usage)
            if (finishReason != null || root.has(CcrConstants.FIELD_USAGE)) {
                // 如果块还在运行，先发 Stop
                if (context.getCurrentBlockIndex() != -1) {
                    ObjectNode cbStop = objectMapper.createObjectNode();
                    cbStop.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_STOP);
                    cbStop.put(CcrConstants.FIELD_INDEX, context.getCurrentBlockIndex());
                    sb.append(CcrConstants.SSE_DATA_PREFIX).append(cbStop.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
                    context.setThinkingStarted(false);
                    context.setTextStarted(false);
                    context.setCurrentBlockIndex(-1);
                }

                // message_delta
                ObjectNode end = objectMapper.createObjectNode();
                end.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_MESSAGE_DELTA);
                ObjectNode d = objectMapper.createObjectNode();
                d.put(CcrConstants.FIELD_STOP_REASON, mapOpenAiFinishReasonToAnthropic(finishReason));
                d.putNull("stop_sequence");
                end.set(CcrConstants.FIELD_DELTA, d);

                // Usage
                ObjectNode usage = objectMapper.createObjectNode();
                if (root.has(CcrConstants.FIELD_USAGE)) {
                    JsonNode openAiUsage = root.get(CcrConstants.FIELD_USAGE);
                    int inputTokens = openAiUsage.has(CcrConstants.FIELD_PROMPT_TOKENS) ? openAiUsage.get(CcrConstants.FIELD_PROMPT_TOKENS).asInt() : 0;
                    int outputTokens = openAiUsage.has(CcrConstants.FIELD_COMPLETION_TOKENS) ? openAiUsage.get(CcrConstants.FIELD_COMPLETION_TOKENS).asInt() : 0;
                    int cached = 0;
                    if (openAiUsage.has("prompt_tokens_details") && openAiUsage.get("prompt_tokens_details").has("cached_tokens")) {
                        cached = openAiUsage.get("prompt_tokens_details").get("cached_tokens").asInt();
                    }
                    usage.put(CcrConstants.FIELD_INPUT_TOKENS, Math.max(0, inputTokens - cached));
                    usage.put(CcrConstants.FIELD_OUTPUT_TOKENS, outputTokens);
                    usage.put(CcrConstants.FIELD_CACHE_READ_INPUT_TOKENS, cached);
                } else {
                    usage.put(CcrConstants.FIELD_INPUT_TOKENS, 0);
                    usage.put(CcrConstants.FIELD_OUTPUT_TOKENS, 0);
                    usage.put(CcrConstants.FIELD_CACHE_READ_INPUT_TOKENS, 0);
                }
                end.set(CcrConstants.FIELD_USAGE, usage);
                sb.append(CcrConstants.SSE_DATA_PREFIX).append(end.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
                
                // message_stop
                ObjectNode stop = objectMapper.createObjectNode();
                stop.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_MESSAGE_STOP);
                sb.append(CcrConstants.SSE_DATA_PREFIX).append(stop.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
            }

            return sb.length() > 0 ? sb.toString() : null;
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
            if (contentNode != null && contentNode.isArray()) {
                StringBuilder contentText = new StringBuilder();
                for (JsonNode block : contentNode) {
                    String blockType = block.has(CcrConstants.FIELD_TYPE) ? block.get(CcrConstants.FIELD_TYPE).asText() : "";
                    if (CcrConstants.FIELD_TEXT.equals(blockType)) {
                        contentText.append(block.get(CcrConstants.FIELD_TEXT).asText());
                    } else if (CcrConstants.FIELD_THINKING.equals(blockType)) {
                        message.put(CcrConstants.FIELD_REASONING_CONTENT, block.get(CcrConstants.FIELD_THINKING).asText());
                    } else if (CcrConstants.ANT_TYPE_TOOL_USE.equals(blockType)) {
                        if (!message.has(CcrConstants.FIELD_TOOL_CALLS)) {
                            message.set(CcrConstants.FIELD_TOOL_CALLS, objectMapper.createArrayNode());
                        }
                        ArrayNode toolCalls = (ArrayNode) message.get(CcrConstants.FIELD_TOOL_CALLS);
                        ObjectNode toolCall = objectMapper.createObjectNode();
                        toolCall.put(CcrConstants.FIELD_ID, block.get(CcrConstants.FIELD_ID).asText());
                        toolCall.put(CcrConstants.FIELD_TYPE, "function");
                        ObjectNode function = objectMapper.createObjectNode();
                        function.put("name", block.get("name").asText());
                        function.set("arguments", block.get("input"));
                        toolCall.set("function", function);
                        toolCalls.add(toolCall);
                    }
                }
                message.put(CcrConstants.FIELD_CONTENT, contentText.toString());
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
                int inputTokens = antUsage.has(CcrConstants.FIELD_INPUT_TOKENS) ? antUsage.get(CcrConstants.FIELD_INPUT_TOKENS).asInt() : 0;
                int outputTokens = antUsage.has(CcrConstants.FIELD_OUTPUT_TOKENS) ? antUsage.get(CcrConstants.FIELD_OUTPUT_TOKENS).asInt() : 0;
                int cachedTokens = antUsage.has(CcrConstants.FIELD_CACHE_READ_INPUT_TOKENS) ? antUsage.get(CcrConstants.FIELD_CACHE_READ_INPUT_TOKENS).asInt() : 0;
                
                usage.put(CcrConstants.FIELD_PROMPT_TOKENS, inputTokens + cachedTokens);
                usage.put(CcrConstants.FIELD_COMPLETION_TOKENS, outputTokens);
                usage.put(CcrConstants.FIELD_TOTAL_TOKENS, inputTokens + cachedTokens + outputTokens);
                
                if (cachedTokens > 0) {
                    ObjectNode details = objectMapper.createObjectNode();
                    details.put("cached_tokens", cachedTokens);
                    usage.set("prompt_tokens_details", details);
                }
                
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
            
            if (CcrConstants.ANT_EVENT_MESSAGE_STOP.equals(type)) {
                return CcrConstants.SSE_DATA_PREFIX + CcrConstants.SSE_DONE + CcrConstants.SSE_LINE_SEPARATOR;
            }

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
                    openAiChunk.put(CcrConstants.FIELD_MODEL, root.get(CcrConstants.FIELD_MESSAGE).get(CcrConstants.FIELD_MODEL).asText());
                }
                shouldSend = true;
            } else if (CcrConstants.ANT_EVENT_CONTENT_BLOCK_DELTA.equals(type)) {
                JsonNode antDelta = root.get(CcrConstants.FIELD_DELTA);
                String antDeltaType = antDelta.get(CcrConstants.FIELD_TYPE).asText();
                
                if (CcrConstants.ANT_TYPE_TEXT_DELTA.equals(antDeltaType)) {
                    delta.put(CcrConstants.FIELD_CONTENT, antDelta.get(CcrConstants.FIELD_TEXT).asText());
                    shouldSend = true;
                } else if (CcrConstants.ANT_TYPE_THINKING_DELTA.equals(antDeltaType)) {
                    delta.put(CcrConstants.FIELD_REASONING_CONTENT, antDelta.get(CcrConstants.FIELD_THINKING).asText());
                    shouldSend = true;
                } else if (CcrConstants.ANT_TYPE_INPUT_JSON_DELTA.equals(antDeltaType)) {
                    // 工具调用增量处理
                    ArrayNode openAiToolCalls = objectMapper.createArrayNode();
                    ObjectNode openAiToolCall = objectMapper.createObjectNode();
                    openAiToolCall.put(CcrConstants.FIELD_INDEX, root.get(CcrConstants.FIELD_INDEX).asInt() - 2); // 假设从 index 2 开始是工具
                    ObjectNode function = objectMapper.createObjectNode();
                    function.put("arguments", antDelta.get("partial_json").asText());
                    openAiToolCall.set("function", function);
                    openAiToolCalls.add(openAiToolCall);
                    choice.set(CcrConstants.FIELD_TOOL_CALLS, openAiToolCalls);
                    shouldSend = true;
                }
            } else if (CcrConstants.ANT_EVENT_MESSAGE_DELTA.equals(type)) {
                JsonNode antDelta = root.get(CcrConstants.FIELD_DELTA);
                if (antDelta.has(CcrConstants.FIELD_STOP_REASON) && !antDelta.get(CcrConstants.FIELD_STOP_REASON).isNull()) {
                    choice.put(CcrConstants.OPENAI_FINISH_REASON, mapAnthropicStopReasonToOpenAi(antDelta.get(CcrConstants.FIELD_STOP_REASON).asText()));
                } else {
                    choice.put(CcrConstants.OPENAI_FINISH_REASON, CcrConstants.OPENAI_FINISH_REASON_STOP);
                }
                
                if (root.has(CcrConstants.FIELD_USAGE)) {
                    JsonNode antUsage = root.get(CcrConstants.FIELD_USAGE);
                    ObjectNode openAiUsage = objectMapper.createObjectNode();
                    int input = antUsage.has(CcrConstants.FIELD_INPUT_TOKENS) ? antUsage.get(CcrConstants.FIELD_INPUT_TOKENS).asInt() : 0;
                    int output = antUsage.has(CcrConstants.FIELD_OUTPUT_TOKENS) ? antUsage.get(CcrConstants.FIELD_OUTPUT_TOKENS).asInt() : 0;
                    int cached = antUsage.has(CcrConstants.FIELD_CACHE_READ_INPUT_TOKENS) ? antUsage.get(CcrConstants.FIELD_CACHE_READ_INPUT_TOKENS).asInt() : 0;
                    
                    openAiUsage.put(CcrConstants.FIELD_PROMPT_TOKENS, input + cached);
                    openAiUsage.put(CcrConstants.FIELD_COMPLETION_TOKENS, output);
                    openAiUsage.put(CcrConstants.FIELD_TOTAL_TOKENS, input + cached + output);
                    
                    if (cached > 0) {
                        ObjectNode details = objectMapper.createObjectNode();
                        details.put("cached_tokens", cached);
                        openAiUsage.set("prompt_tokens_details", details);
                    }
                    openAiChunk.set(CcrConstants.FIELD_USAGE, openAiUsage);
                }
                shouldSend = true;
            } else if (CcrConstants.ANT_EVENT_CONTENT_BLOCK_START.equals(type)) {
                JsonNode cb = root.get("content_block");
                if (cb.has(CcrConstants.FIELD_TYPE) && CcrConstants.ANT_TYPE_TOOL_USE.equals(cb.get(CcrConstants.FIELD_TYPE).asText())) {
                    ArrayNode openAiToolCalls = objectMapper.createArrayNode();
                    ObjectNode openAiToolCall = objectMapper.createObjectNode();
                    openAiToolCall.put(CcrConstants.FIELD_INDEX, root.get(CcrConstants.FIELD_INDEX).asInt() - 2);
                    openAiToolCall.put(CcrConstants.FIELD_ID, cb.get(CcrConstants.FIELD_ID).asText());
                    openAiToolCall.put(CcrConstants.FIELD_TYPE, "function");
                    ObjectNode function = objectMapper.createObjectNode();
                    function.put("name", cb.get("name").asText());
                    function.put("arguments", "");
                    openAiToolCall.set("function", function);
                    openAiToolCalls.add(openAiToolCall);
                    choice.set(CcrConstants.FIELD_TOOL_CALLS, openAiToolCalls);
                    shouldSend = true;
                }
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
    private String mapOpenAiFinishReasonToAnthropic(String openAiReason) {
        if (openAiReason == null) return null;
        switch (openAiReason) {
            case CcrConstants.OPENAI_FINISH_REASON_STOP:
                return CcrConstants.ANT_STOP_REASON_END_TURN;
            case CcrConstants.OPENAI_FINISH_REASON_LENGTH:
                return CcrConstants.ANT_STOP_REASON_MAX_TOKENS;
            case CcrConstants.OPENAI_FINISH_REASON_TOOL_CALLS:
                return CcrConstants.ANT_STOP_REASON_TOOL_USE;
            case CcrConstants.OPENAI_FINISH_REASON_CONTENT_FILTER:
                return CcrConstants.ANT_STOP_REASON_STOP_SEQUENCE;
            default:
                return CcrConstants.ANT_STOP_REASON_END_TURN;
        }
    }

    private String mapAnthropicStopReasonToOpenAi(String antReason) {
        if (antReason == null) return CcrConstants.OPENAI_FINISH_REASON_STOP;
        switch (antReason) {
            case CcrConstants.ANT_STOP_REASON_END_TURN:
                return CcrConstants.OPENAI_FINISH_REASON_STOP;
            case CcrConstants.ANT_STOP_REASON_MAX_TOKENS:
                return CcrConstants.OPENAI_FINISH_REASON_LENGTH;
            case CcrConstants.ANT_STOP_REASON_TOOL_USE:
                return CcrConstants.OPENAI_FINISH_REASON_TOOL_CALLS;
            case CcrConstants.ANT_STOP_REASON_STOP_SEQUENCE:
                return CcrConstants.OPENAI_FINISH_REASON_CONTENT_FILTER;
            default:
                return CcrConstants.OPENAI_FINISH_REASON_STOP;
        }
    }
}
