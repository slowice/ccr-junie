package com.ccr.service;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 代理转发服务接口，处理请求转发、协议转换及响应透传
 */
public interface ProxyService {
    /**
     * 执行代理请求的核心入口
     * 
     * @param body 请求体字符串
     * @param isIncomingOpenAi 客户端请求是否为 OpenAI 格式 (true: OpenAI, false: Anthropic)
     * @return 响应数据流 (ServerSentEvent 格式)
     */
    Flux<ServerSentEvent<String>> proxyRequest(String body, boolean isIncomingOpenAi);
}
