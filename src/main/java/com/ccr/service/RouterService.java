package com.ccr.service;

import com.ccr.config.CcrConfig;
import com.ccr.constant.CcrConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RouterService {

    private final CcrConfig ccrConfig;
    private final ObjectMapper objectMapper;

    public RouterService(CcrConfig ccrConfig, ObjectMapper objectMapper) {
        this.ccrConfig = ccrConfig;
        this.objectMapper = objectMapper;
    }

    @Data
    @AllArgsConstructor
    public static class RouteResult {
        private CcrConfig.Provider provider;
        private String targetModel;
    }

    public RouteResult getRoute(String requestBody) {
        String targetModelStr = null;
        try {
            JsonNode root = objectMapper.readTree(requestBody);
            String inputModel = root.has(CcrConstants.FIELD_MODEL) ? root.get(CcrConstants.FIELD_MODEL).asText() : "";

            // 1. If input is already ProviderName,ModelName format
            if (inputModel.contains(",")) {
                targetModelStr = inputModel;
            } else {
                // 2. Detect scenario and select model
                String scenario = detectScenario(root, inputModel);
                log.info("Detected scenario: {}", scenario);
                targetModelStr = ccrConfig.getRouterModel(scenario);
                if (targetModelStr == null) {
                    targetModelStr = ccrConfig.getRouterModel(CcrConstants.SCENARIO_DEFAULT);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse request body, using default route: {}", e.getMessage());
            targetModelStr = ccrConfig.getRouterModel(CcrConstants.SCENARIO_DEFAULT);
        }

        if (targetModelStr == null) {
            return new RouteResult(ccrConfig.getProviders().get(0), "default-model");
        }

        // 解析 ProviderName,ModelName
        String[] parts = targetModelStr.split(",");
        String providerName = parts[0];
        String modelName = parts.length > 1 ? parts[1] : parts[0];
        
        CcrConfig.Provider provider = ccrConfig.getProviders().stream()
                .filter(p -> p.getName().equalsIgnoreCase(providerName))
                .findFirst()
                .orElse(ccrConfig.getProviders().get(0));

        return new RouteResult(provider, modelName);
    }

    private String detectScenario(JsonNode root, String inputModel) {
        // 1. Long Context
        int tokenCount = calculateTokenCount(root);
        if (tokenCount > ccrConfig.getLongContextThreshold()) {
            return CcrConstants.SCENARIO_LONG_CONTEXT;
        }

        // 2. Background (Haiku)
        if (inputModel.toLowerCase().contains("haiku")) {
            return CcrConstants.SCENARIO_BACKGROUND;
        }

        // 3. Web Search
        if (root.has(CcrConstants.FIELD_TOOLS)) {
            JsonNode tools = root.get(CcrConstants.FIELD_TOOLS);
            if (tools.isArray()) {
                for (JsonNode tool : tools) {
                    if (tool.has(CcrConstants.FIELD_TYPE) && tool.get(CcrConstants.FIELD_TYPE).asText().startsWith("web_search")) {
                        return CcrConstants.SCENARIO_WEB_SEARCH;
                    }
                }
            }
        }

        // 4. Thinking
        if (root.has(CcrConstants.FIELD_THINKING)) {
            return CcrConstants.SCENARIO_THINK;
        }

        return CcrConstants.SCENARIO_DEFAULT;
    }

    private int calculateTokenCount(JsonNode root) {
        int charCount = 0;
        // Count characters in messages
        if (root.has(CcrConstants.FIELD_MESSAGES) && root.get(CcrConstants.FIELD_MESSAGES).isArray()) {
            for (JsonNode message : root.get(CcrConstants.FIELD_MESSAGES)) {
                JsonNode content = message.get(CcrConstants.FIELD_CONTENT);
                if (content != null) {
                    if (content.isTextual()) {
                        charCount += content.asText().length();
                    } else if (content.isArray()) {
                        for (JsonNode part : content) {
                            if (part.has(CcrConstants.FIELD_TEXT)) {
                                charCount += part.get(CcrConstants.FIELD_TEXT).asText().length();
                            }
                        }
                    }
                }
            }
        }
        if (root.has(CcrConstants.FIELD_SYSTEM)) {
            JsonNode system = root.get(CcrConstants.FIELD_SYSTEM);
            if (system.isTextual()) {
                charCount += system.asText().length();
            } else if (system.isArray()) {
                for (JsonNode part : system) {
                    if (part.has(CcrConstants.FIELD_TEXT)) {
                        charCount += part.get(CcrConstants.FIELD_TEXT).asText().length();
                    }
                }
            }
        }
        return charCount / 4;
    }
}
