package com.ccr.constant;

/**
 * CCR 常量定义类，集中管理项目中使用的字符串、字段名及协议常量
 */
public class CcrConstants {
    /**
     * 路由场景名称
     */
    public static final String SCENARIO_DEFAULT = "default";
    public static final String SCENARIO_THINK = "think";
    public static final String SCENARIO_BACKGROUND = "background";
    public static final String SCENARIO_LONG_CONTEXT = "longContext";
    public static final String SCENARIO_WEB_SEARCH = "webSearch";

    /**
     * JSON 字段名
     */
    public static final String FIELD_MODEL = "model";
    public static final String FIELD_STREAM = "stream";
    public static final String FIELD_MESSAGES = "messages";
    public static final String FIELD_SYSTEM = "system";
    public static final String FIELD_ROLE = "role";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_MAX_TOKENS = "max_tokens";
    public static final String FIELD_TEMPERATURE = "temperature";
    public static final String FIELD_THINKING = "thinking";
    public static final String FIELD_TOOLS = "tools";
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_DELTA = "delta";
    public static final String FIELD_USAGE = "usage";
    public static final String FIELD_PROMPT_TOKENS = "prompt_tokens";
    public static final String FIELD_COMPLETION_TOKENS = "completion_tokens";
    public static final String FIELD_TOTAL_TOKENS = "total_tokens";
    public static final String FIELD_INPUT_TOKENS = "input_tokens";
    public static final String FIELD_OUTPUT_TOKENS = "output_tokens";
    public static final String FIELD_ID = "id";
    public static final String FIELD_STOP_REASON = "stop_reason";

    /**
     * 角色定义
     */
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    /**
     * HTTP 头部信息
     */
    public static final String HEADER_X_API_KEY = "x-api-key";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_BEARER_PREFIX = "Bearer ";

    /**
     * OpenAI 协议相关常量
     */
    public static final String OPENAI_OBJECT_CHAT_COMPLETION = "chat.completion";
    public static final String OPENAI_OBJECT_CHAT_COMPLETION_CHUNK = "chat.completion.chunk";
    public static final String OPENAI_FINISH_REASON_STOP = "stop";
    public static final String OPENAI_CHOICES = "choices";
    public static final String OPENAI_FINISH_REASON = "finish_reason";

    /**
     * Anthropic 协议相关常量
     */
    public static final String ANT_TYPE_MESSAGE = "message";
    public static final String ANT_STOP_REASON_END_TURN = "end_turn";
    public static final String ANT_TYPE_TEXT_DELTA = "text_delta";
    public static final String ANT_TOOL_WEB_SEARCH = "web_search";

    /**
     * Anthropic SSE 事件类型
     */
    public static final String ANT_EVENT_MESSAGE_START = "message_start";
    public static final String ANT_EVENT_CONTENT_BLOCK_DELTA = "content_block_delta";
    public static final String ANT_EVENT_MESSAGE_DELTA = "message_delta";

    // 通用 JSON 字段
    public static final String FIELD_MESSAGE = "message";
    public static final String FIELD_CHOICES = "choices";
    public static final String FIELD_INDEX = "index";
    public static final String FIELD_OBJECT = "object";
    public static final String FIELD_CREATED = "created";

    // 媒体类型
    public static final String MEDIA_TYPE_EVENT_STREAM = "event-stream";

    /**
     * SSE 传输协议相关
     */
    public static final String SSE_DATA_PREFIX = "data: ";
    public static final String SSE_DONE = "[DONE]";
    public static final String SSE_LINE_SEPARATOR = "\n\n";
}
