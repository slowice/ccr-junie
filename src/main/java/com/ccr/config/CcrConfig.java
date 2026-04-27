package com.ccr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "ccr")
public class CcrConfig {
    private List<Provider> providers;
    private Map<String, String> router;

    public int getLongContextThreshold() {
        if (router == null) return 60000;
        String val = router.get("longContextThreshold");
        return val != null ? Integer.parseInt(val) : 60000;
    }

    public String getRouterModel(String key) {
        return router != null ? router.get(key) : null;
    }

    @Data
    public static class Provider {
        private String name;
        private String url;
        private String apiKey;
        private TransformerConfig transformer;

        public boolean isAnthropic() {
            return transformer != null && transformer.getUse() != null && transformer.getUse().contains("Anthropic");
        }
    }

    @Data
    public static class TransformerConfig {
        private List<String> use;
    }
}
