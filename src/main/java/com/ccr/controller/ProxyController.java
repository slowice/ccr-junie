package com.ccr.controller;

import com.ccr.service.ProxyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 代理控制器，负责接收客户端请求并转发
 * 支持 Anthropic (/v1/messages) 和 OpenAI (/v1/chat/completions) 两种协议入口
 */
@RestController
public class ProxyController {
    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private final ProxyService proxyService;

    public ProxyController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    /**
     * 处理 Anthropic 格式的请求 (/v1/messages)
     * 
     * @param body 请求体字符串
     * @return 响应数据流 (Flux<ServerSentEvent<String>>)
     */
    @PostMapping("/v1/messages")
    public Flux<ServerSentEvent<String>> proxyMessages(@RequestBody String body) {
        return proxyService.proxyRequest(body, false);
    }

    /**
     * 处理 OpenAI 格式的请求 (/v1/chat/completions)
     * 
     * @param body 请求体字符串
     * @return 响应数据流 (Flux<ServerSentEvent<String>>)
     */
    @PostMapping("/v1/chat/completions")
    public Flux<ServerSentEvent<String>> proxyChatCompletions(@RequestBody String body) {
        return proxyService.proxyRequest(body, true);
    }
}
