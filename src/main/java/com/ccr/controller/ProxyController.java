package com.ccr.controller;

import com.ccr.service.ProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 代理控制器，负责接收客户端请求并转发
 * 支持 Anthropic (/v1/messages) 和 OpenAI (/v1/chat/completions) 两种协议入口
 */
@Slf4j
@RestController
public class ProxyController {

    private final ProxyService proxyService;

    public ProxyController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    /**
     * 处理 Anthropic 格式的请求 (/v1/messages)
     * 
     * @param body 请求体字符串
     * @param response ServerHttpResponse 对象，用于设置状态码和响应头
     * @return 响应数据流 (Flux<DataBuffer>)
     */
    @PostMapping("/v1/messages")
    public Flux<DataBuffer> proxyMessages(@RequestBody String body, ServerHttpResponse response) {
        return proxyService.proxyRequest(body, false, response);
    }

    /**
     * 处理 OpenAI 格式的请求 (/v1/chat/completions)
     * 
     * @param body 请求体字符串
     * @param response ServerHttpResponse 对象，用于设置状态码和响应头
     * @return 响应数据流 (Flux<DataBuffer>)
     */
    @PostMapping("/v1/chat/completions")
    public Flux<DataBuffer> proxyChatCompletions(@RequestBody String body, ServerHttpResponse response) {
        return proxyService.proxyRequest(body, true, response);
    }
}
