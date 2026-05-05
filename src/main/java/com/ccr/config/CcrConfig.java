package com.ccr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * CCR 项目的核心配置类。
 * 通过 spring-boot-starter-configuration-processor 自动映射 application.properties 中以 "ccr" 为前缀的配置项。
 */
@Configuration
@ConfigurationProperties(prefix = "ccr")
public class CcrConfig {
    /**
     * 供应商列表，对应配置中的 ccr.providers
     */
    private List<Provider> providers;

    /**
     * 路由映射表，对应配置中的 ccr.router
     */
    private Map<String, String> router;

    public List<Provider> getProviders() {
        return providers;
    }

    public void setProviders(List<Provider> providers) {
        this.providers = providers;
    }

    public Map<String, String> getRouter() {
        return router;
    }

    public void setRouter(Map<String, String> router) {
        this.router = router;
    }

    /**
     * 获取长上下文判定的 Token 阈值。
     * 默认值为 60000。
     *
     * @return Token 阈值
     */
    public int getLongContextThreshold() {
        if (router == null) return 60000;
        String val = router.get("longContextThreshold");
        return val != null ? Integer.parseInt(val) : 60000;
    }

    /**
     * 根据场景 Key 获取路由配置的模型名称。
     *
     * @param key 场景 Key（如 default, think, background 等）
     * @return 映射后的模型名称（格式通常为 "ProviderName,ModelName"）
     */
    public String getRouterModel(String key) {
        return router != null ? router.get(key) : null;
    }

    /**
     * 供应商配置类。
     */
    public static class Provider {
        /**
         * 供应商名称
         */
        private String name;

        /**
         * 供应商的 API 基础地址
         */
        private String url;

        /**
         * 供应商的 API 密钥
         */
        private String apiKey;

        /**
         * 转换器配置
         */
        private TransformerConfig transformer;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public TransformerConfig getTransformer() { return transformer; }
        public void setTransformer(TransformerConfig transformer) { this.transformer = transformer; }

        /**
         * 判断当前供应商是否使用 Anthropic 协议。
         *
         * @return 如果配置了 Anthropic 转换器则返回 true
         */
        public boolean isAnthropic() {
            return transformer != null && transformer.getUse() != null && transformer.getUse().contains("Anthropic");
        }
    }

    /**
     * 转换器详细配置。
     */
    public static class TransformerConfig {
        /**
         * 使用的转换器列表（如 ["Anthropic"]）
         */
        private List<String> use;

        public List<String> getUse() { return use; }
        public void setUse(List<String> use) { this.use = use; }
    }
}
