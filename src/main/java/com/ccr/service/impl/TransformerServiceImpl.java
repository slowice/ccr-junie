package com.ccr.service.impl;

import com.ccr.constant.CcrConstants;
import com.ccr.model.StreamContext;
import com.ccr.service.TransformerService;
import com.fasterxml.jackson.core.JsonProcessingException;
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
            JsonNode rootNode = objectMapper.readTree(body);
            if (!(rootNode instanceof ObjectNode)) return body;
            ObjectNode openAiRequest = (ObjectNode) rootNode;

            ObjectNode anthropicRequest = objectMapper.createObjectNode();
            anthropicRequest.set(CcrConstants.FIELD_MODEL, openAiRequest.get(CcrConstants.FIELD_MODEL));
            
            setStreamAndMaxTokens(openAiRequest, anthropicRequest);
            
            if (openAiRequest.has(CcrConstants.FIELD_TEMPERATURE)) {
                anthropicRequest.set(CcrConstants.FIELD_TEMPERATURE, openAiRequest.get(CcrConstants.FIELD_TEMPERATURE));
            }

            JsonNode messagesNode = openAiRequest.get(CcrConstants.FIELD_MESSAGES);
            if (!(messagesNode instanceof ArrayNode)) return body;
            
            ArrayNode anthropicMessages = objectMapper.createArrayNode();
            StringBuilder systemPrompt = new StringBuilder();
            processOpenAiMessages((ArrayNode) messagesNode, anthropicMessages, systemPrompt);
            
            if (systemPrompt.length() > 0) {
                anthropicRequest.put(CcrConstants.FIELD_SYSTEM, systemPrompt.toString());
            }
            anthropicRequest.set(CcrConstants.FIELD_MESSAGES, anthropicMessages);
            
            return anthropicRequest.toString();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse request body when transforming OpenAI to Anthropic: {}", e.getMessage());
            return body;
        } catch (Exception e) {
            log.error("Unknown error occurred when transforming OpenAI to Anthropic: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 设置流式开关和最大 Token 数
     * Anthropic 协议中 max_tokens 是必填项，如果 OpenAI 请求中没有提供，则设置默认值
     */
    private void setStreamAndMaxTokens(ObjectNode openAiRequest, ObjectNode anthropicRequest) {
        if (openAiRequest.has(CcrConstants.FIELD_STREAM)) {
            anthropicRequest.set(CcrConstants.FIELD_STREAM, openAiRequest.get(CcrConstants.FIELD_STREAM));
        }
        
        if (openAiRequest.has(CcrConstants.FIELD_MAX_TOKENS)) {
            anthropicRequest.set(CcrConstants.FIELD_MAX_TOKENS, openAiRequest.get(CcrConstants.FIELD_MAX_TOKENS));
        } else if (openAiRequest.has("max_completion_tokens")) {
            anthropicRequest.set(CcrConstants.FIELD_MAX_TOKENS, openAiRequest.get("max_completion_tokens"));
        } else {
            // Anthropic 协议必须包含 max_tokens 字段
            anthropicRequest.put(CcrConstants.FIELD_MAX_TOKENS, 4096);
        }
    }

    /**
     * 处理 OpenAI 消息数组，提取 System Prompt 并将其余消息转为 Anthropic 格式
     */
    private void processOpenAiMessages(ArrayNode openAiMessages, ArrayNode anthropicMessages, StringBuilder systemPrompt) {
        for (JsonNode msg : openAiMessages) {
            if (!msg.has(CcrConstants.FIELD_ROLE)) continue;
            String role = msg.get(CcrConstants.FIELD_ROLE).asText();
            JsonNode content = msg.get(CcrConstants.FIELD_CONTENT);
            
            if (CcrConstants.ROLE_SYSTEM.equals(role)) {
                if (systemPrompt.length() > 0) systemPrompt.append("\n");
                systemPrompt.append(content.isTextual() ? content.asText() : content.toString());
            } else {
                anthropicMessages.add(transformOpenAiMessage(msg, content));
            }
        }
    }

    /**
     * 转换单条 OpenAI 消息到 Anthropic 格式
     * 特别处理 content 为数组的情况，确保每一项都符合 Anthropic 的内容块规范
     */
    private JsonNode transformOpenAiMessage(JsonNode msg, JsonNode content) {
        JsonNode msgCopy = msg.deepCopy();
        if (!(msgCopy instanceof ObjectNode)) return msg;
        ObjectNode transformedMsg = (ObjectNode) msgCopy;
        
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
        return transformedMsg;
    }

    /**
     * 将 Anthropic 格式的请求体转换为 OpenAI 格式
     * 主要处理：模型名、流式开关、最大 Token、温度以及 System Prompt 转为消息数组首条
     */
    @Override
    public String transformAnthropicToOpenAi(String body) {
        try {
            JsonNode rootNode = objectMapper.readTree(body);
            if (!(rootNode instanceof ObjectNode)) return body;
            ObjectNode anthropicRequestNode = (ObjectNode) rootNode;

            ObjectNode openAiRequest = objectMapper.createObjectNode();
            setOpenAiBasicFields(anthropicRequestNode, openAiRequest);

            ArrayNode openAiMessagesArray = objectMapper.createArrayNode();
            processAnthropicSystem(anthropicRequestNode, openAiMessagesArray);
            processAnthropicMessages(anthropicRequestNode, openAiMessagesArray);
            
            openAiRequest.set(CcrConstants.FIELD_MESSAGES, openAiMessagesArray);

            processAnthropicTools(anthropicRequestNode, openAiRequest);
            processAnthropicToolChoice(anthropicRequestNode, openAiRequest);
            
            return openAiRequest.toString();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse request body when transforming Anthropic to OpenAI: {}", e.getMessage());
            return body;
        } catch (Exception e) {
            log.error("Unknown error occurred when transforming Anthropic to OpenAI: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 设置 OpenAI 请求的基础字段
     */
    private void setOpenAiBasicFields(ObjectNode anthropicRequestNode, ObjectNode openAiRequest) {
        openAiRequest.set(CcrConstants.FIELD_MODEL, anthropicRequestNode.get(CcrConstants.FIELD_MODEL));
        
        if (anthropicRequestNode.has(CcrConstants.FIELD_STREAM)) {
            openAiRequest.set(CcrConstants.FIELD_STREAM, anthropicRequestNode.get(CcrConstants.FIELD_STREAM));
        }
        
        if (anthropicRequestNode.has(CcrConstants.FIELD_MAX_TOKENS)) {
            openAiRequest.set(CcrConstants.FIELD_MAX_TOKENS, anthropicRequestNode.get(CcrConstants.FIELD_MAX_TOKENS));
        }
        
        if (anthropicRequestNode.has(CcrConstants.FIELD_TEMPERATURE)) {
            openAiRequest.set(CcrConstants.FIELD_TEMPERATURE, anthropicRequestNode.get(CcrConstants.FIELD_TEMPERATURE));
        }
    }

    /**
     * 处理 Anthropic 的 System Prompt，转为 OpenAI 的 system 角色消息
     */
    private void processAnthropicSystem(ObjectNode anthropicRequestNode, ArrayNode openAiMessagesArray) {
        JsonNode system = anthropicRequestNode.get(CcrConstants.FIELD_SYSTEM);
        if (system == null || system.isNull()) return;

        ObjectNode sysMsg = objectMapper.createObjectNode();
        sysMsg.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_SYSTEM);
        
        if (system.isArray()) {
            sysMsg.put(CcrConstants.FIELD_CONTENT, extractTextFromArray(system));
        } else {
            sysMsg.set(CcrConstants.FIELD_CONTENT, system);
        }
        openAiMessagesArray.add(sysMsg);
    }

    /**
     * 从 Anthropic 的内容数组中提取纯文本
     */
    private String extractTextFromArray(JsonNode arrayNode) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode node : arrayNode) {
            if (node.has(CcrConstants.FIELD_TEXT)) {
                sb.append(node.get(CcrConstants.FIELD_TEXT).asText());
            }
        }
        return sb.toString();
    }

    /**
     * 遍历并处理 Anthropic 的消息列表
     */
    private void processAnthropicMessages(ObjectNode anthropicRequestNode, ArrayNode openAiMessagesArray) {
        JsonNode messagesNode = anthropicRequestNode.get(CcrConstants.FIELD_MESSAGES);
        if (!(messagesNode instanceof ArrayNode)) return;
        
        for (JsonNode msg : (ArrayNode) messagesNode) {
            processSingleAnthropicMessage(msg, openAiMessagesArray);
        }
    }

    /**
     * 处理单条 Anthropic 消息，根据角色和内容类型进行分发转换
     */
    private void processSingleAnthropicMessage(JsonNode msg, ArrayNode openAiMessagesArray) {
        if (msg.isObject()) {
            ObjectNode msgObj = (ObjectNode) msg;
            JsonNode content = msgObj.get(CcrConstants.FIELD_CONTENT);
            // 如果 content 是数组，需要根据角色进行复杂的结构重组
            if (content instanceof ArrayNode && handleAnthropicContentArray((ArrayNode) content, openAiMessagesArray, msgObj)) {
                return;
            }
        }
        openAiMessagesArray.add(msg.deepCopy());
    }

    /**
     * 处理 Anthropic 的内容数组（可能包含 text, thinking, tool_use, tool_result）
     */
    private boolean handleAnthropicContentArray(ArrayNode contentArray, ArrayNode openAiMessagesArray, ObjectNode msgObj) {
        String role = msgObj.path(CcrConstants.FIELD_ROLE).asText();
        
        if (CcrConstants.ROLE_USER.equals(role)) {
            return handleUserContentArray(contentArray, openAiMessagesArray);
        }
        
        if (CcrConstants.ROLE_ASSISTANT.equals(role)) {
            return handleAssistantToolUse(contentArray, openAiMessagesArray);
        }
        
        return false;
    }

    /**
     * 处理 User 角色的内容数组，主要映射 tool_result 到 OpenAI 的 tool 角色
     */
    private boolean handleUserContentArray(ArrayNode contentArray, ArrayNode openAiMessagesArray) {
        StringBuilder textContent = new StringBuilder();
        boolean hasSpecialType = false;
        for (JsonNode item : contentArray) {
            String type = item.path(CcrConstants.FIELD_TYPE).asText();
            if ("tool_result".equals(type)) {
                hasSpecialType = true;
                flushUserTextMessage(textContent, openAiMessagesArray);
                addToolResultMessage(item, openAiMessagesArray);
            } else if (CcrConstants.FIELD_TEXT.equals(type)) {
                textContent.append(item.path(CcrConstants.FIELD_TEXT).asText());
            }
        }
        if (textContent.length() > 0) {
            addUserTextMessage(textContent.toString(), openAiMessagesArray);
            hasSpecialType = true;
        }
        return hasSpecialType;
    }

    /**
     * 将暂存的 User 文本内容刷新到消息数组中
     */
    private void flushUserTextMessage(StringBuilder textContent, ArrayNode openAiMessagesArray) {
        if (textContent.length() > 0) {
            addUserTextMessage(textContent.toString(), openAiMessagesArray);
            textContent.setLength(0);
        }
    }

    /**
     * 添加一条 User 角色文本消息
     */
    private void addUserTextMessage(String text, ArrayNode openAiMessagesArray) {
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_USER);
        userMsg.put(CcrConstants.FIELD_CONTENT, text);
        openAiMessagesArray.add(userMsg);
    }

    /**
     * 将 Anthropic 的 tool_result 转换为 OpenAI 的 tool 角色消息
     */
    private void addToolResultMessage(JsonNode item, ArrayNode openAiMessagesArray) {
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
        openAiMessagesArray.add(toolMsg);
    }

    /**
     * 处理 Assistant 角色的内容数组，映射 thinking 到 reasoning_content，tool_use 到 tool_calls
     */
    private boolean handleAssistantToolUse(ArrayNode contentArray, ArrayNode openAiMessagesArray) {
        ArrayNode toolCalls = objectMapper.createArrayNode();
        boolean hasToolUse = false;
        boolean hasThinking = false;
        StringBuilder textContent = new StringBuilder();
        StringBuilder thinkingContent = new StringBuilder();
        
        for (JsonNode item : contentArray) {
            String type = item.path(CcrConstants.FIELD_TYPE).asText();
            if ("tool_use".equals(type)) {
                hasToolUse = true;
                toolCalls.add(buildOpenAiToolCall(item));
            } else if (CcrConstants.FIELD_TEXT.equals(type)) {
                textContent.append(item.path(CcrConstants.FIELD_TEXT).asText());
            } else if ("thinking".equals(type)) {
                hasThinking = true;
                thinkingContent.append(item.path("thinking").asText());
            }
        }
        
        if (hasToolUse || hasThinking || textContent.length() > 0) {
            ObjectNode assistantMsg = objectMapper.createObjectNode();
            assistantMsg.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_ASSISTANT);
            
            if (hasThinking) {
                assistantMsg.put(CcrConstants.FIELD_REASONING_CONTENT, thinkingContent.toString());
            }
            
            if (textContent.length() > 0) {
                assistantMsg.put(CcrConstants.FIELD_CONTENT, textContent.toString());
            } else if (hasToolUse) {
                assistantMsg.putNull(CcrConstants.FIELD_CONTENT);
            } else {
                assistantMsg.put(CcrConstants.FIELD_CONTENT, "");
            }
            
            if (hasToolUse) {
                assistantMsg.set(CcrConstants.FIELD_TOOL_CALLS, toolCalls);
            }
            
            openAiMessagesArray.add(assistantMsg);
            return true;
        }
        return false;
    }

    /**
     * 构建 OpenAI 格式的工具调用对象
     */
    private ObjectNode buildOpenAiToolCall(JsonNode item) {
        ObjectNode toolCall = objectMapper.createObjectNode();
        toolCall.put(CcrConstants.FIELD_TYPE, "function");
        toolCall.put(CcrConstants.FIELD_ID, item.path(CcrConstants.FIELD_ID).asText());
        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", item.path("name").asText());
        function.put("arguments", item.path("input").toString());
        toolCall.set("function", function);
        return toolCall;
    }

    /**
     * 处理 Anthropic 的 tools 定义转换为 OpenAI 格式
     */
    private void processAnthropicTools(ObjectNode anthropicRequestNode, ObjectNode openAiRequest) {
        JsonNode toolsNode = anthropicRequestNode.get(CcrConstants.FIELD_TOOLS);
        if (toolsNode instanceof ArrayNode) {
            ArrayNode anthropicTools = (ArrayNode) toolsNode;
            ArrayNode openAiTools = objectMapper.createArrayNode();
            for (JsonNode antTool : anthropicTools) {
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
    }

    /**
     * 处理 Anthropic 的 tool_choice 转换为 OpenAI 格式
     */
    private void processAnthropicToolChoice(ObjectNode anthropicRequestNode, ObjectNode openAiRequest) {
        if (anthropicRequestNode.has("tool_choice")) {
            JsonNode anthropicToolChoice = anthropicRequestNode.get("tool_choice");
            if (anthropicToolChoice.isObject()) {
                String type = anthropicToolChoice.path(CcrConstants.FIELD_TYPE).asText();
                if ("tool".equals(type)) {
                    ObjectNode openAiToolChoice = objectMapper.createObjectNode();
                    openAiToolChoice.put(CcrConstants.FIELD_TYPE, "function");
                    ObjectNode function = objectMapper.createObjectNode();
                    function.set("name", anthropicToolChoice.get("name"));
                    openAiToolChoice.set("function", function);
                    openAiRequest.set("tool_choice", openAiToolChoice);
                } else {
                    openAiRequest.put("tool_choice", type);
                }
            } else {
                openAiRequest.set("tool_choice", anthropicToolChoice);
            }
        }
    }

    /**
     * 将 OpenAI 响应转换为 Anthropic 格式 (非流式)
     * 将 OpenAI 的 choices 数组转换为 Anthropic 的 content 数组及 usage 统计
     */
    @Override
    public String transformOpenAiResponseToAnthropic(String body) {
        try {
            JsonNode rootNode = objectMapper.readTree(body);
            ObjectNode anthropicResponseNode = objectMapper.createObjectNode();
            
            setAnthropicBasicResponseFields(rootNode, anthropicResponseNode);
            processOpenAiChoices(rootNode, anthropicResponseNode);
            processOpenAiUsage(rootNode, anthropicResponseNode);

            return anthropicResponseNode.toString();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse OpenAI response when transforming to Anthropic: {}", e.getMessage());
            return body;
        } catch (Exception e) {
            log.error("Unknown error occurred when transforming OpenAI response to Anthropic: {}", e.getMessage());
            return body;
        }
    }

    private void setAnthropicBasicResponseFields(JsonNode rootNode, ObjectNode anthropicResponseNode) {
        anthropicResponseNode.put(CcrConstants.FIELD_ID, rootNode.has(CcrConstants.FIELD_ID) ? rootNode.get(CcrConstants.FIELD_ID).asText() : "ant-" + UUID.randomUUID());
        anthropicResponseNode.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_TYPE_MESSAGE);
        anthropicResponseNode.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_ASSISTANT);
        anthropicResponseNode.put(CcrConstants.FIELD_MODEL, rootNode.has(CcrConstants.FIELD_MODEL) ? rootNode.get(CcrConstants.FIELD_MODEL).asText() : "unknown");
    }

    private void processOpenAiChoices(JsonNode rootNode, ObjectNode anthropicResponseNode) {
        JsonNode choicesNode = rootNode.get(CcrConstants.OPENAI_CHOICES);
        if (!(choicesNode instanceof ArrayNode)) return;
        ArrayNode choices = (ArrayNode) choicesNode;
        ArrayNode contentArray = objectMapper.createArrayNode();
        if (choices.size() > 0) {
            JsonNode firstChoice = choices.get(0);
            JsonNode message = firstChoice.get(CcrConstants.FIELD_MESSAGE);
            
            if (message != null) {
                addThinkingContent(message, contentArray);
                addTextOrArrayContent(message, contentArray);
                addToolCallsContent(message, contentArray);
            }
            
            String finishReason = firstChoice.has(CcrConstants.OPENAI_FINISH_REASON) ? firstChoice.get(CcrConstants.OPENAI_FINISH_REASON).asText() : null;
            anthropicResponseNode.put(CcrConstants.FIELD_STOP_REASON, mapOpenAiFinishReasonToAnthropic(finishReason));
            anthropicResponseNode.putNull(CcrConstants.FIELD_STOP_SEQUENCE);
        }
        anthropicResponseNode.set(CcrConstants.FIELD_CONTENT, contentArray);
    }

    private void addThinkingContent(JsonNode message, ArrayNode contentArray) {
        if (message.has(CcrConstants.FIELD_REASONING_CONTENT) && !message.get(CcrConstants.FIELD_REASONING_CONTENT).isNull()) {
            ObjectNode thinkingObj = objectMapper.createObjectNode();
            thinkingObj.put(CcrConstants.FIELD_TYPE, CcrConstants.FIELD_THINKING);
            thinkingObj.put(CcrConstants.FIELD_THINKING, message.get(CcrConstants.FIELD_REASONING_CONTENT).asText());
            thinkingObj.put("signature", "sign_" + UUID.randomUUID().toString().substring(0, 8));
            contentArray.add(thinkingObj);
        }
    }

    private void addTextOrArrayContent(JsonNode message, ArrayNode contentArray) {
        JsonNode contentNode = message.get(CcrConstants.FIELD_CONTENT);
        if (contentNode == null || contentNode.isNull()) return;

        if (contentNode.isTextual()) {
            addTextContent(contentNode.asText(), contentArray);
        } else if (contentNode.isArray()) {
            addArrayContent((ArrayNode) contentNode, contentArray);
        }
    }

    private void addTextContent(String text, ArrayNode contentArray) {
        if (text != null && !text.isEmpty()) {
            ObjectNode contentObj = objectMapper.createObjectNode();
            contentObj.put(CcrConstants.FIELD_TYPE, CcrConstants.FIELD_TEXT);
            contentObj.put(CcrConstants.FIELD_TEXT, text);
            contentArray.add(contentObj);
        }
    }

    private void addArrayContent(ArrayNode contentNode, ArrayNode contentArray) {
        for (JsonNode item : contentNode) {
            if (item.isObject()) {
                ObjectNode itemObj = item.deepCopy();
                ensureThinkingSignature(itemObj);
                contentArray.add(itemObj);
            } else {
                contentArray.add(item);
            }
        }
    }

    private void ensureThinkingSignature(ObjectNode itemObj) {
        if (CcrConstants.FIELD_THINKING.equals(itemObj.path(CcrConstants.FIELD_TYPE).asText()) 
                && !itemObj.has("signature")) {
            itemObj.put("signature", "sign_" + UUID.randomUUID().toString().substring(0, 8));
        }
    }

    private void addToolCallsContent(JsonNode message, ArrayNode contentArray) {
        JsonNode toolCallsNode = message.get(CcrConstants.FIELD_TOOL_CALLS);
        if (toolCallsNode instanceof ArrayNode) {
            for (JsonNode toolCall : toolCallsNode) {
                ObjectNode toolUseObj = objectMapper.createObjectNode();
                toolUseObj.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_TYPE_TOOL_USE);
                toolUseObj.put(CcrConstants.FIELD_ID, toolCall.get(CcrConstants.FIELD_ID).asText());
                JsonNode function = toolCall.get("function");
                toolUseObj.put("name", function.get("name").asText());
                try {
                    toolUseObj.set("input", objectMapper.readTree(function.get("arguments").asText()));
                } catch (Exception e) {
                    log.warn("Failed to parse tool call arguments: {}", e.getMessage());
                    toolUseObj.put("input", function.get("arguments").asText());
                }
                contentArray.add(toolUseObj);
            }
        }
    }

    private void processOpenAiUsage(JsonNode rootNode, ObjectNode anthropicResponseNode) {
        ObjectNode usage = objectMapper.createObjectNode();
        if (rootNode.has(CcrConstants.FIELD_USAGE)) {
            JsonNode openAiUsage = rootNode.get(CcrConstants.FIELD_USAGE);
            int promptTokens = openAiUsage.has(CcrConstants.FIELD_PROMPT_TOKENS) ? openAiUsage.get(CcrConstants.FIELD_PROMPT_TOKENS).asInt() : 0;
            int completionTokens = openAiUsage.has(CcrConstants.FIELD_COMPLETION_TOKENS) ? openAiUsage.get(CcrConstants.FIELD_COMPLETION_TOKENS).asInt() : 0;
            
            int cached = 0;
        if (openAiUsage.path("prompt_tokens_details").has("cached_tokens")) {
                cached = openAiUsage.get("prompt_tokens_details").get("cached_tokens").asInt();
                usage.put(CcrConstants.FIELD_CACHE_READ_INPUT_TOKENS, cached);
            }
            
            usage.put(CcrConstants.FIELD_INPUT_TOKENS, Math.max(0, promptTokens - cached));
            usage.put(CcrConstants.FIELD_OUTPUT_TOKENS, completionTokens);
        } else {
            usage.put(CcrConstants.FIELD_INPUT_TOKENS, 0);
            usage.put(CcrConstants.FIELD_OUTPUT_TOKENS, 0);
        }
        anthropicResponseNode.set(CcrConstants.FIELD_USAGE, usage);
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
            return handleOpenAiSseDone();
        }

        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choicesNode = root.get(CcrConstants.OPENAI_CHOICES);
            if (!(choicesNode instanceof ArrayNode) || choicesNode.size() == 0) return null;
            
            JsonNode firstChoice = choicesNode.get(0);
            JsonNode delta = firstChoice.get(CcrConstants.FIELD_DELTA);
            String finishReason = (firstChoice.has(CcrConstants.OPENAI_FINISH_REASON) && !firstChoice.get(CcrConstants.OPENAI_FINISH_REASON).isNull())
                    ? firstChoice.get(CcrConstants.OPENAI_FINISH_REASON).asText() : null;

            StringBuilder outputBuilder = new StringBuilder();
            
            handleMessageStartSse(root, context, outputBuilder);
            
            if (delta != null) {
                handleThinkingDeltaSse(delta, context, outputBuilder);
                handleTextDeltaSse(delta, context, outputBuilder);
                handleToolCallDeltaSse(delta, context, outputBuilder);
            }

            handleStreamEndSse(root, finishReason, context, outputBuilder);

            return outputBuilder.length() > 0 ? outputBuilder.toString() : null;
        } catch (JsonProcessingException e) {
            log.error("Failed to parse OpenAI SSE when transforming to Anthropic: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Unknown error occurred when transforming OpenAI SSE to Anthropic: {}", e.getMessage());
            return null;
        }
    }

    private String handleOpenAiSseDone() {
        ObjectNode stop = objectMapper.createObjectNode();
        stop.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_MESSAGE_STOP);
        return CcrConstants.SSE_DATA_PREFIX + stop.toString() + CcrConstants.SSE_LINE_SEPARATOR;
    }

    private void handleMessageStartSse(JsonNode root, StreamContext context, StringBuilder outputBuilder) {
        if (context.isMessageStarted()) return;
        
        ObjectNode messageStart = objectMapper.createObjectNode();
        messageStart.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_MESSAGE_START);
        
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
        
        messageStart.set(CcrConstants.FIELD_MESSAGE, message);
        outputBuilder.append(CcrConstants.SSE_DATA_PREFIX).append(messageStart.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
        context.setMessageStarted(true);
    }

    private void handleThinkingDeltaSse(JsonNode delta, StreamContext context, StringBuilder outputBuilder) {
        if (!delta.has(CcrConstants.FIELD_REASONING_CONTENT) || delta.get(CcrConstants.FIELD_REASONING_CONTENT).isNull()) return;
        
        String thinkingContent = delta.get(CcrConstants.FIELD_REASONING_CONTENT).asText();
        if (thinkingContent.isEmpty()) return;
        
        if (!context.isThinkingStarted()) {
            int blockIndex = context.getNextBlockIndex();
            context.setThinkingBlockIndex(blockIndex);
            context.setNextBlockIndex(blockIndex + 1);
            
            ObjectNode contentBlockStart = objectMapper.createObjectNode();
            contentBlockStart.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_START);
            contentBlockStart.put(CcrConstants.FIELD_INDEX, blockIndex);
            
            ObjectNode contentBlock = objectMapper.createObjectNode();
            contentBlock.put(CcrConstants.FIELD_TYPE, CcrConstants.FIELD_THINKING);
            contentBlock.put(CcrConstants.FIELD_THINKING, "");
            contentBlock.put("signature", "sign_" + UUID.randomUUID().toString().substring(0, 8));
            
            contentBlockStart.set("content_block", contentBlock);
            outputBuilder.append(CcrConstants.SSE_DATA_PREFIX).append(contentBlockStart.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
            context.setThinkingStarted(true);
            context.setCurrentBlockIndex(blockIndex);
        }
        
        ObjectNode contentBlockDelta = objectMapper.createObjectNode();
        contentBlockDelta.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_DELTA);
        contentBlockDelta.put(CcrConstants.FIELD_INDEX, context.getThinkingBlockIndex());
        
        ObjectNode deltaNode = objectMapper.createObjectNode();
        deltaNode.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_TYPE_THINKING_DELTA);
        deltaNode.put(CcrConstants.FIELD_THINKING, thinkingContent);
        
        contentBlockDelta.set(CcrConstants.FIELD_DELTA, deltaNode);
        outputBuilder.append(CcrConstants.SSE_DATA_PREFIX).append(contentBlockDelta.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
    }

    private void handleTextDeltaSse(JsonNode delta, StreamContext context, StringBuilder outputBuilder) {
        if (!delta.has(CcrConstants.FIELD_CONTENT) || delta.get(CcrConstants.FIELD_CONTENT).isNull()) return;
        
        String content = delta.get(CcrConstants.FIELD_CONTENT).asText();
        if (content.isEmpty()) return;
        
        // 如果之前在做 Thinking 或 Tool Call，现在转 Text，需要发 Stop
        if (context.isThinkingStarted() || (context.getCurrentBlockIndex() != -1 && !context.isTextStarted())) {
            sendContentBlockStop(context, outputBuilder);
            context.setThinkingStarted(false);
            context.setCurrentBlockIndex(-1);
        }
        
        if (!context.isTextStarted()) {
            int blockIndex = context.getNextBlockIndex();
            context.setTextBlockIndex(blockIndex);
            context.setNextBlockIndex(blockIndex + 1);
            
            ObjectNode contentBlockStart = objectMapper.createObjectNode();
            contentBlockStart.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_START);
            contentBlockStart.put(CcrConstants.FIELD_INDEX, blockIndex);
            
            ObjectNode contentBlock = objectMapper.createObjectNode();
            contentBlock.put(CcrConstants.FIELD_TYPE, CcrConstants.FIELD_TEXT);
            contentBlock.put(CcrConstants.FIELD_TEXT, "");
            
            contentBlockStart.set("content_block", contentBlock);
            outputBuilder.append(CcrConstants.SSE_DATA_PREFIX).append(contentBlockStart.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
            context.setTextStarted(true);
            context.setCurrentBlockIndex(blockIndex);
        }
        
        ObjectNode contentBlockDelta = objectMapper.createObjectNode();
        contentBlockDelta.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_DELTA);
        contentBlockDelta.put(CcrConstants.FIELD_INDEX, context.getTextBlockIndex());
        
        ObjectNode deltaNode = objectMapper.createObjectNode();
        deltaNode.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_TYPE_TEXT_DELTA);
        deltaNode.put(CcrConstants.FIELD_TEXT, content);
        
        contentBlockDelta.set(CcrConstants.FIELD_DELTA, deltaNode);
        outputBuilder.append(CcrConstants.SSE_DATA_PREFIX).append(contentBlockDelta.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
    }

    private void handleToolCallDeltaSse(JsonNode delta, StreamContext context, StringBuilder outputBuilder) {
        JsonNode toolCallsNode = delta.get(CcrConstants.FIELD_TOOL_CALLS);
        if (!(toolCallsNode instanceof ArrayNode)) return;
        
        ArrayNode toolCalls = (ArrayNode) toolCallsNode;
        for (JsonNode toolCall : toolCalls) {
            int toolCallIndex = toolCall.path(CcrConstants.FIELD_INDEX).asInt(0);
            
            if (!context.getToolCallIndexToContentBlockIndex().containsKey(toolCallIndex)) {
                if (context.getCurrentBlockIndex() != -1) {
                    sendContentBlockStop(context, outputBuilder);
                    context.setThinkingStarted(false);
                    context.setTextStarted(false);
                    context.setCurrentBlockIndex(-1);
                }
                
                int blockIndex = context.getNextBlockIndex();
                context.setNextBlockIndex(blockIndex + 1);
                context.getToolCallIndexToContentBlockIndex().put(toolCallIndex, blockIndex);
                
                sendToolUseBlockStart(toolCall, blockIndex, outputBuilder);
                context.setCurrentBlockIndex(blockIndex);
            }
            
            String arguments = toolCall.path("function").path("arguments").asText("");
            if (!arguments.isEmpty()) {
                int blockIndex = context.getToolCallIndexToContentBlockIndex().get(toolCallIndex);
                sendInputJsonDelta(blockIndex, arguments, outputBuilder);
                context.setCurrentBlockIndex(blockIndex);
            }
        }
    }

    private void sendToolUseBlockStart(JsonNode toolCall, int blockIndex, StringBuilder outputBuilder) {
        ObjectNode contentBlockStart = objectMapper.createObjectNode();
        contentBlockStart.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_START);
        contentBlockStart.put(CcrConstants.FIELD_INDEX, blockIndex);
        
        ObjectNode contentBlock = objectMapper.createObjectNode();
        contentBlock.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_TYPE_TOOL_USE);
        contentBlock.put(CcrConstants.FIELD_ID, toolCall.path(CcrConstants.FIELD_ID).isMissingNode() 
                ? "toolu_" + UUID.randomUUID().toString().substring(0, 8) : toolCall.get(CcrConstants.FIELD_ID).asText());
        contentBlock.put("name", toolCall.path("function").path("name").asText("unknown"));
        contentBlock.set("input", objectMapper.createObjectNode());
        
        contentBlockStart.set("content_block", contentBlock);
        outputBuilder.append(CcrConstants.SSE_DATA_PREFIX).append(contentBlockStart.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
    }

    private void sendInputJsonDelta(int blockIndex, String arguments, StringBuilder outputBuilder) {
        ObjectNode contentBlockDelta = objectMapper.createObjectNode();
        contentBlockDelta.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_DELTA);
        contentBlockDelta.put(CcrConstants.FIELD_INDEX, blockIndex);
        
        ObjectNode deltaNode = objectMapper.createObjectNode();
        deltaNode.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_TYPE_INPUT_JSON_DELTA);
        deltaNode.put("partial_json", arguments);
        
        contentBlockDelta.set(CcrConstants.FIELD_DELTA, deltaNode);
        outputBuilder.append(CcrConstants.SSE_DATA_PREFIX).append(contentBlockDelta.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
    }

    private void handleStreamEndSse(JsonNode root, String finishReason, StreamContext context, StringBuilder outputBuilder) {
        if (finishReason == null && !root.has(CcrConstants.FIELD_USAGE)) return;
        
        if (context.getCurrentBlockIndex() != -1) {
            sendContentBlockStop(context, outputBuilder);
            context.setThinkingStarted(false);
            context.setTextStarted(false);
            context.setCurrentBlockIndex(-1);
        }

        ObjectNode messageDelta = objectMapper.createObjectNode();
        messageDelta.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_MESSAGE_DELTA);
        
        ObjectNode deltaNode = objectMapper.createObjectNode();
        deltaNode.put(CcrConstants.FIELD_STOP_REASON, mapOpenAiFinishReasonToAnthropic(finishReason));
        deltaNode.putNull("stop_sequence");
        messageDelta.set(CcrConstants.FIELD_DELTA, deltaNode);

        ObjectNode usage = buildAnthropicUsageSse(root);
        messageDelta.set(CcrConstants.FIELD_USAGE, usage);
        outputBuilder.append(CcrConstants.SSE_DATA_PREFIX).append(messageDelta.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
        
        ObjectNode messageStop = objectMapper.createObjectNode();
        messageStop.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_MESSAGE_STOP);
        outputBuilder.append(CcrConstants.SSE_DATA_PREFIX).append(messageStop.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
    }

    private ObjectNode buildAnthropicUsageSse(JsonNode root) {
        ObjectNode usage = objectMapper.createObjectNode();
        if (root.has(CcrConstants.FIELD_USAGE)) {
            JsonNode openAiUsage = root.get(CcrConstants.FIELD_USAGE);
            int inputTokens = openAiUsage.path(CcrConstants.FIELD_PROMPT_TOKENS).asInt(0);
            int outputTokens = openAiUsage.path(CcrConstants.FIELD_COMPLETION_TOKENS).asInt(0);
            int cached = openAiUsage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
            
            usage.put(CcrConstants.FIELD_INPUT_TOKENS, Math.max(0, inputTokens - cached));
            usage.put(CcrConstants.FIELD_OUTPUT_TOKENS, outputTokens);
            usage.put(CcrConstants.FIELD_CACHE_READ_INPUT_TOKENS, cached);
        } else {
            usage.put(CcrConstants.FIELD_INPUT_TOKENS, 0);
            usage.put(CcrConstants.FIELD_OUTPUT_TOKENS, 0);
            usage.put(CcrConstants.FIELD_CACHE_READ_INPUT_TOKENS, 0);
        }
        return usage;
    }

    private void sendContentBlockStop(StreamContext context, StringBuilder outputBuilder) {
        ObjectNode contentBlockStop = objectMapper.createObjectNode();
        contentBlockStop.put(CcrConstants.FIELD_TYPE, CcrConstants.ANT_EVENT_CONTENT_BLOCK_STOP);
        contentBlockStop.put(CcrConstants.FIELD_INDEX, context.getCurrentBlockIndex());
        outputBuilder.append(CcrConstants.SSE_DATA_PREFIX).append(contentBlockStop.toString()).append(CcrConstants.SSE_LINE_SEPARATOR);
    }

    /**
     * 将 Anthropic 响应转换为 OpenAI 格式 (非流式)
     * 将 Anthropic 的 content 数组转回 OpenAI 的 choices 格式
     */
    @Override
    public String transformAnthropicResponseToOpenAi(String body) {
        try {
            JsonNode rootNode = objectMapper.readTree(body);
            ObjectNode openAiResponseNode = objectMapper.createObjectNode();
            
            setOpenAiBasicResponseFields(rootNode, openAiResponseNode);
            processAnthropicContent(rootNode, openAiResponseNode);
            processAnthropicUsage(rootNode, openAiResponseNode);

            return openAiResponseNode.toString();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Anthropic response when transforming to OpenAI: {}", e.getMessage());
            return body;
        } catch (Exception e) {
            log.error("Unknown error occurred when transforming Anthropic response to OpenAI: {}", e.getMessage());
            return body;
        }
    }

    private void setOpenAiBasicResponseFields(JsonNode rootNode, ObjectNode openAiResponseNode) {
        openAiResponseNode.put(CcrConstants.FIELD_ID, rootNode.has(CcrConstants.FIELD_ID) ? rootNode.get(CcrConstants.FIELD_ID).asText() : "chatcmpl-" + UUID.randomUUID());
        openAiResponseNode.put(CcrConstants.FIELD_OBJECT, CcrConstants.OPENAI_OBJECT_CHAT_COMPLETION);
        openAiResponseNode.put(CcrConstants.FIELD_CREATED, System.currentTimeMillis() / 1000);
        openAiResponseNode.put(CcrConstants.FIELD_MODEL, rootNode.has(CcrConstants.FIELD_MODEL) ? rootNode.get(CcrConstants.FIELD_MODEL).asText() : "unknown");
    }

    private void processAnthropicContent(JsonNode rootNode, ObjectNode openAiResponseNode) {
        ArrayNode choices = objectMapper.createArrayNode();
        ObjectNode choice = objectMapper.createObjectNode();
        choice.put(CcrConstants.FIELD_INDEX, 0);
        
        ObjectNode message = objectMapper.createObjectNode();
        message.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_ASSISTANT);
        
        JsonNode contentNode = rootNode.get(CcrConstants.FIELD_CONTENT);
        if (contentNode instanceof ArrayNode) {
            StringBuilder contentText = new StringBuilder();
            ArrayNode toolCalls = objectMapper.createArrayNode();
            
            for (JsonNode block : contentNode) {
                String blockType = block.path(CcrConstants.FIELD_TYPE).asText("");
                if (CcrConstants.FIELD_TEXT.equals(blockType)) {
                    contentText.append(block.path(CcrConstants.FIELD_TEXT).asText(""));
                } else if (CcrConstants.FIELD_THINKING.equals(blockType)) {
                    message.put(CcrConstants.FIELD_REASONING_CONTENT, block.path(CcrConstants.FIELD_THINKING).asText(""));
                } else if (CcrConstants.ANT_TYPE_TOOL_USE.equals(blockType)) {
                    toolCalls.add(buildOpenAiToolCallFromAnthropic(block));
                }
            }
            message.put(CcrConstants.FIELD_CONTENT, contentText.toString());
            if (toolCalls.size() > 0) {
                message.set(CcrConstants.FIELD_TOOL_CALLS, toolCalls);
            }
        } else {
            message.put(CcrConstants.FIELD_CONTENT, "");
        }
        
        choice.set(CcrConstants.FIELD_MESSAGE, message);
        String antStopReason = rootNode.has(CcrConstants.FIELD_STOP_REASON) ? rootNode.get(CcrConstants.FIELD_STOP_REASON).asText() : null;
        choice.put(CcrConstants.OPENAI_FINISH_REASON, mapAnthropicStopReasonToOpenAi(antStopReason));
        choices.add(choice);
        openAiResponseNode.set(CcrConstants.FIELD_CHOICES, choices);
    }

    private ObjectNode buildOpenAiToolCallFromAnthropic(JsonNode block) {
        ObjectNode toolCall = objectMapper.createObjectNode();
        toolCall.put(CcrConstants.FIELD_ID, block.path(CcrConstants.FIELD_ID).asText(""));
        toolCall.put(CcrConstants.FIELD_TYPE, "function");
        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", block.path("name").asText(""));
        JsonNode input = block.get("input");
        if (input != null) {
            function.put("arguments", input.isTextual() ? input.asText() : input.toString());
        } else {
            function.put("arguments", "{}");
        }
        toolCall.set("function", function);
        return toolCall;
    }

    private void processAnthropicUsage(JsonNode rootNode, ObjectNode openAiResponseNode) {
        if (!rootNode.has(CcrConstants.FIELD_USAGE)) return;
        
        ObjectNode usage = objectMapper.createObjectNode();
        JsonNode antUsage = rootNode.get(CcrConstants.FIELD_USAGE);
        int inputTokens = antUsage.path(CcrConstants.FIELD_INPUT_TOKENS).asInt(0);
        int outputTokens = antUsage.path(CcrConstants.FIELD_OUTPUT_TOKENS).asInt(0);
        int cachedTokens = antUsage.path(CcrConstants.FIELD_CACHE_READ_INPUT_TOKENS).asInt(0);
        
        usage.put(CcrConstants.FIELD_PROMPT_TOKENS, inputTokens + cachedTokens);
        usage.put(CcrConstants.FIELD_COMPLETION_TOKENS, outputTokens);
        usage.put(CcrConstants.FIELD_TOTAL_TOKENS, inputTokens + cachedTokens + outputTokens);
        
        if (cachedTokens > 0) {
            ObjectNode details = objectMapper.createObjectNode();
            details.put("cached_tokens", cachedTokens);
            usage.set("prompt_tokens_details", details);
        }
        
        openAiResponseNode.set(CcrConstants.FIELD_USAGE, usage);
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
            JsonNode rootNode = objectMapper.readTree(data);
            String type = rootNode.path(CcrConstants.FIELD_TYPE).asText("");
            
            if (CcrConstants.ANT_EVENT_MESSAGE_STOP.equals(type)) {
                return CcrConstants.SSE_DATA_PREFIX + CcrConstants.SSE_DONE + CcrConstants.SSE_LINE_SEPARATOR;
            }

            ObjectNode openAiChunk = createOpenAiChunkBase(rootNode);
            ObjectNode choice = objectMapper.createObjectNode();
            choice.put(CcrConstants.FIELD_INDEX, 0);
            ObjectNode delta = objectMapper.createObjectNode();

            boolean shouldSend = dispatchAnthropicSseEvent(type, rootNode, openAiChunk, choice, delta);

            if (!shouldSend) return null;

            choice.set(CcrConstants.FIELD_DELTA, delta);
            ArrayNode choicesArray = objectMapper.createArrayNode();
            choicesArray.add(choice);
            openAiChunk.set("choices", choicesArray);
            
            return CcrConstants.SSE_DATA_PREFIX + openAiChunk.toString() + "\n\n";
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Anthropic SSE when transforming to OpenAI: {}", e.getMessage());
            return line;
        } catch (Exception e) {
            log.error("Unknown error occurred when transforming Anthropic SSE to OpenAI: {}", e.getMessage());
            return line;
        }
    }

    private ObjectNode createOpenAiChunkBase(JsonNode rootNode) {
        ObjectNode openAiChunk = objectMapper.createObjectNode();
        openAiChunk.put(CcrConstants.FIELD_ID, "chatcmpl-" + UUID.randomUUID());
        openAiChunk.put(CcrConstants.FIELD_OBJECT, CcrConstants.OPENAI_OBJECT_CHAT_COMPLETION_CHUNK);
        openAiChunk.put(CcrConstants.FIELD_CREATED, System.currentTimeMillis() / 1000);
        return openAiChunk;
    }

    private boolean dispatchAnthropicSseEvent(String type, JsonNode rootNode, ObjectNode openAiChunk, ObjectNode choice, ObjectNode delta) {
        switch (type) {
            case CcrConstants.ANT_EVENT_MESSAGE_START:
                return handleAnthropicMessageStartSse(rootNode, openAiChunk, delta);
            case CcrConstants.ANT_EVENT_CONTENT_BLOCK_START:
                return handleAnthropicContentBlockStartSse(rootNode, choice);
            case CcrConstants.ANT_EVENT_CONTENT_BLOCK_DELTA:
                return handleAnthropicContentBlockDeltaSse(rootNode, choice, delta);
            case CcrConstants.ANT_EVENT_MESSAGE_DELTA:
                return handleAnthropicMessageDeltaSse(rootNode, openAiChunk, choice);
            default:
                return false;
        }
    }

    private boolean handleAnthropicMessageStartSse(JsonNode rootNode, ObjectNode openAiChunk, ObjectNode delta) {
        delta.put(CcrConstants.FIELD_ROLE, CcrConstants.ROLE_ASSISTANT);
        if (rootNode.path(CcrConstants.FIELD_MESSAGE).has(CcrConstants.FIELD_MODEL)) {
            openAiChunk.set(CcrConstants.FIELD_MODEL, rootNode.get(CcrConstants.FIELD_MESSAGE).get(CcrConstants.FIELD_MODEL));
        }
        return true;
    }

    private boolean handleAnthropicContentBlockStartSse(JsonNode rootNode, ObjectNode choice) {
        JsonNode contentBlockNode = rootNode.get("content_block");
        if (contentBlockNode != null && CcrConstants.ANT_TYPE_TOOL_USE.equals(contentBlockNode.path(CcrConstants.FIELD_TYPE).asText())) {
            ArrayNode openAiToolCalls = objectMapper.createArrayNode();
            ObjectNode openAiToolCall = objectMapper.createObjectNode();
            openAiToolCall.put(CcrConstants.FIELD_INDEX, rootNode.path(CcrConstants.FIELD_INDEX).asInt(0) - 2);
            openAiToolCall.put(CcrConstants.FIELD_ID, contentBlockNode.path(CcrConstants.FIELD_ID).asText(""));
            openAiToolCall.put(CcrConstants.FIELD_TYPE, "function");
            ObjectNode function = objectMapper.createObjectNode();
            function.put("name", contentBlockNode.path("name").asText(""));
            function.put("arguments", "");
            openAiToolCall.set("function", function);
            openAiToolCalls.add(openAiToolCall);
            choice.set(CcrConstants.FIELD_TOOL_CALLS, openAiToolCalls);
            return true;
        }
        return false;
    }

    private boolean handleAnthropicContentBlockDeltaSse(JsonNode rootNode, ObjectNode choice, ObjectNode delta) {
        JsonNode antDelta = rootNode.get(CcrConstants.FIELD_DELTA);
        if (antDelta == null) return false;
        
        String antDeltaType = antDelta.path(CcrConstants.FIELD_TYPE).asText("");
        if (CcrConstants.ANT_TYPE_TEXT_DELTA.equals(antDeltaType)) {
            delta.put(CcrConstants.FIELD_CONTENT, antDelta.path(CcrConstants.FIELD_TEXT).asText(""));
            return true;
        } else if (CcrConstants.ANT_TYPE_THINKING_DELTA.equals(antDeltaType)) {
            delta.put(CcrConstants.FIELD_REASONING_CONTENT, antDelta.path(CcrConstants.FIELD_THINKING).asText(""));
            return true;
        } else if (CcrConstants.ANT_TYPE_INPUT_JSON_DELTA.equals(antDeltaType)) {
            ArrayNode openAiToolCalls = objectMapper.createArrayNode();
            ObjectNode openAiToolCall = objectMapper.createObjectNode();
            openAiToolCall.put(CcrConstants.FIELD_INDEX, rootNode.path(CcrConstants.FIELD_INDEX).asInt(0) - 2);
            ObjectNode function = objectMapper.createObjectNode();
            function.put("arguments", antDelta.path("partial_json").asText(""));
            openAiToolCall.set("function", function);
            openAiToolCalls.add(openAiToolCall);
            choice.set(CcrConstants.FIELD_TOOL_CALLS, openAiToolCalls);
            return true;
        }
        return false;
    }

    private boolean handleAnthropicMessageDeltaSse(JsonNode rootNode, ObjectNode openAiChunk, ObjectNode choice) {
        JsonNode antDelta = rootNode.get(CcrConstants.FIELD_DELTA);
        if (antDelta != null) {
            String stopReason = antDelta.path(CcrConstants.FIELD_STOP_REASON).isMissingNode() ? null : antDelta.get(CcrConstants.FIELD_STOP_REASON).asText();
            choice.put(CcrConstants.OPENAI_FINISH_REASON, mapAnthropicStopReasonToOpenAi(stopReason));
        } else {
            choice.put(CcrConstants.OPENAI_FINISH_REASON, CcrConstants.OPENAI_FINISH_REASON_STOP);
        }
        
        if (rootNode.has(CcrConstants.FIELD_USAGE)) {
            openAiChunk.set(CcrConstants.FIELD_USAGE, buildOpenAiUsageFromAnthropic(rootNode.get(CcrConstants.FIELD_USAGE)));
        }
        return true;
    }

    private ObjectNode buildOpenAiUsageFromAnthropic(JsonNode antUsage) {
        ObjectNode openAiUsage = objectMapper.createObjectNode();
        int input = antUsage.path(CcrConstants.FIELD_INPUT_TOKENS).asInt(0);
        int output = antUsage.path(CcrConstants.FIELD_OUTPUT_TOKENS).asInt(0);
        int cached = antUsage.path(CcrConstants.FIELD_CACHE_READ_INPUT_TOKENS).asInt(0);
        
        openAiUsage.put(CcrConstants.FIELD_PROMPT_TOKENS, input + cached);
        openAiUsage.put(CcrConstants.FIELD_COMPLETION_TOKENS, output);
        openAiUsage.put(CcrConstants.FIELD_TOTAL_TOKENS, input + cached + output);
        
        if (cached > 0) {
            ObjectNode details = objectMapper.createObjectNode();
            details.put("cached_tokens", cached);
            openAiUsage.set("prompt_tokens_details", details);
        }
        return openAiUsage;
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
