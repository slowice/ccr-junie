package com.ccr.service;

import com.ccr.config.CcrConfig;
import com.ccr.constant.CcrConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 路由服务，负责根据请求内容识别场景并选择最合适的供应商和模型
 */
@Slf4j
@Service
public class RouterService {

    private final CcrConfig ccrConfig;
    private final ObjectMapper objectMapper;

    public RouterService(CcrConfig ccrConfig, ObjectMapper objectMapper) {
        this.ccrConfig = ccrConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * 路由结果类
     */
    @Data
    @AllArgsConstructor
    public static class RouteResult {
        private CcrConfig.Provider provider; // 选定的供应商
        private String targetModel;          // 目标模型名称
    }

    /**
     * 获取路由信息
     * 
     * @param requestBody 请求体字符串
     * @return 路由结果 (Provider + TargetModel)
     */
    public RouteResult getRoute(String requestBody) {
        String targetModelStr = null;
        try {
            JsonNode root = objectMapper.readTree(requestBody);
            String inputModel = root.has(CcrConstants.FIELD_MODEL) ? root.get(CcrConstants.FIELD_MODEL).asText() : "";

            // 1. 如果请求中模型已经是 "ProviderName,ModelName" 格式，直接解析
            if (inputModel.contains(",")) {
                targetModelStr = inputModel;
            } else {
                // 2. 自动检测请求场景（长上下文、思考、后台等）
                String scenario = detectScenario(root, inputModel);
                log.info("Detected scenario: {}", scenario);
                // 根据场景从配置中读取对应的模型配置
                targetModelStr = ccrConfig.getRouterModel(scenario);
                if (targetModelStr == null) {
                    targetModelStr = ccrConfig.getRouterModel(CcrConstants.SCENARIO_DEFAULT);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse request body, using default route: {}", e.getMessage());
            targetModelStr = ccrConfig.getRouterModel(CcrConstants.SCENARIO_DEFAULT);
        }

        // 如果未配置路由，则兜底使用第一个供应商
        if (targetModelStr == null) {
            return new RouteResult(ccrConfig.getProviders().get(0), "default-model");
        }

        // 解析 "ProviderName,ModelName" 字符串
        String[] parts = targetModelStr.split(",");
        String providerName = parts[0];
        String modelName = parts.length > 1 ? parts[1] : parts[0];
        
        // 匹配供应商
        CcrConfig.Provider provider = ccrConfig.getProviders().stream()
                .filter(p -> p.getName().equalsIgnoreCase(providerName))
                .findFirst()
                .orElse(ccrConfig.getProviders().get(0));

        return new RouteResult(provider, modelName);
    }

    /**
     * 根据请求体特征识别当前的使用场景
     */
    private String detectScenario(JsonNode root, String inputModel) {
        // 1. 长上下文识别：根据估算的 Token 数决定
        int tokenCount = calculateTokenCount(root);
        if (tokenCount > ccrConfig.getLongContextThreshold()) {
            return CcrConstants.SCENARIO_LONG_CONTEXT;
        }

        // 2. 后台任务识别：如果模型名包含 "haiku"，通常是 Claude Code 的后台任务
        if (inputModel.toLowerCase().contains("haiku")) {
            return CcrConstants.SCENARIO_BACKGROUND;
        }

        // 3. 联网搜索场景识别：检测是否包含 web_search 工具
        if (root.has(CcrConstants.FIELD_TOOLS)) {
            JsonNode tools = root.get(CcrConstants.FIELD_TOOLS);
            if (tools.isArray()) {
                for (JsonNode tool : tools) {
                    if (tool.has(CcrConstants.FIELD_TYPE) && tool.get(CcrConstants.FIELD_TYPE).asText().startsWith(CcrConstants.ANT_TOOL_WEB_SEARCH)) {
                        return CcrConstants.SCENARIO_WEB_SEARCH;
                    }
                }
            }
        }

        // 4. 深度思考场景识别：检测请求中是否包含 thinking 配置
        if (root.has(CcrConstants.FIELD_THINKING)) {
            return CcrConstants.SCENARIO_THINK;
        }

        // 默认场景
        return CcrConstants.SCENARIO_DEFAULT;
    }

    /**
     * 简易 Token 计数器（参考原项目逻辑，按 字符数/4 估算）
     */
    private int calculateTokenCount(JsonNode root) {
        int charCount = 0;
        // 统计 messages 中的内容长度
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
        // 统计 system prompt 的长度
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
