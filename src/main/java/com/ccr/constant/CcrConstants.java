package com.ccr.constant;

public class CcrConstants {
    // Scenario names
    public static final String SCENARIO_DEFAULT = "default";
    public static final String SCENARIO_THINK = "think";
    public static final String SCENARIO_BACKGROUND = "background";
    public static final String SCENARIO_LONG_CONTEXT = "longContext";
    public static final String SCENARIO_WEB_SEARCH = "webSearch";

    // JSON Fields
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
    public static final String FIELD_ID = "id";

    // Roles
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    // HTTP Headers
    public static final String HEADER_X_API_KEY = "x-api-key";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_BEARER_PREFIX = "Bearer ";

    // OpenAI Constants
    public static final String OPENAI_OBJECT_CHAT_COMPLETION = "chat.completion";
    public static final String OPENAI_OBJECT_CHAT_COMPLETION_CHUNK = "chat.completion.chunk";
    public static final String OPENAI_FINISH_REASON_STOP = "stop";

    // Anthropic SSE Events
    public static final String ANT_EVENT_MESSAGE_START = "message_start";
    public static final String ANT_EVENT_CONTENT_BLOCK_DELTA = "content_block_delta";
    public static final String ANT_EVENT_MESSAGE_DELTA = "message_delta";

    // SSE Prefix
    public static final String SSE_DATA_PREFIX = "data: ";
    public static final String SSE_DONE = "[DONE]";
}
