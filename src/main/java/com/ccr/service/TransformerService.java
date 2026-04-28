package com.ccr.service;

/**
 * 协议转换服务接口，负责在 OpenAI 和 Anthropic 两种协议格式之间进行双向转换
 */
public interface TransformerService {
    
    /**
     * 将 OpenAI 格式的请求体转换为 Anthropic 格式
     */
    String transformOpenAiToAnthropic(String body);

    /**
     * 将 Anthropic 格式的请求体转换为 OpenAI 格式
     */
    String transformAnthropicToOpenAi(String body);

    /**
     * 将 OpenAI 响应转换为 Anthropic 格式 (非流式)
     */
    String transformOpenAiResponseToAnthropic(String body);

    /**
     * 将 OpenAI SSE 事件转换为 Anthropic SSE 事件
     */
    String transformOpenAiSseToAnthropic(String line);

    /**
     * 将 Anthropic 响应转换为 OpenAI 格式 (非流式)
     */
    String transformAnthropicResponseToOpenAi(String body);

    /**
     * 将 Anthropic SSE 事件转换为 OpenAI SSE 事件
     */
    String transformAnthropicSseToOpenAi(String line);
}
