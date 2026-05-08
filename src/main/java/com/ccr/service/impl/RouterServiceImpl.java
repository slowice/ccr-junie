package com.ccr.service.impl;

import com.ccr.config.CcrConfig;
import com.ccr.constant.CcrConstants;
import com.ccr.service.RouterService;
import com.ccr.service.RouterService.RouteResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 路由服务实现类，负责根据请求内容识别场景并选择最合适的供应商和模型
 */
@Service
public class RouterServiceImpl implements RouterService {
    private static final Logger log = LoggerFactory.getLogger(RouterServiceImpl.class);

    private final CcrConfig ccrConfig;
    private final ObjectMapper objectMapper;

    public RouterServiceImpl(CcrConfig ccrConfig, ObjectMapper objectMapper) {
        this.ccrConfig = ccrConfig;
        this.objectMapper = objectMapper;
    }


    /**
     * 获取路由信息
     * 
     * @param requestBody 请求体字符串
     * @return 路由结果 (Provider + TargetModel)
     */
    @Override
    public RouteResult getRoute(String requestBody) {
        String targetModelStr = resolveTargetModelString(requestBody);
        
        if (targetModelStr == null) {
            return new RouteResult(ccrConfig.getProviders().get(0), "default-model");
        }

        return parseRouteResult(targetModelStr);
    }

    private String resolveTargetModelString(String requestBody) {
        try {
            JsonNode rootNode = objectMapper.readTree(requestBody);
            String inputModel = rootNode.path(CcrConstants.FIELD_MODEL).asText("");

            if (inputModel.contains(",")) {
                return inputModel;
            }

            String scenario = detectScenario(rootNode, inputModel);
            log.info("Detected scenario: {}", scenario);
            
            String targetModelStr = ccrConfig.getRouterModel(scenario);
            return (targetModelStr != null) ? targetModelStr : ccrConfig.getRouterModel(CcrConstants.SCENARIO_DEFAULT);
            
        } catch (JsonProcessingException e) {
            log.error("Failed to parse request body, using default route: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Unknown error occurred during route selection: {}", e.getMessage());
        }
        return ccrConfig.getRouterModel(CcrConstants.SCENARIO_DEFAULT);
    }

    private RouteResult parseRouteResult(String targetModelStr) {
        String[] parts = targetModelStr.split(",");
        String providerName = parts[0];
        String modelName = parts.length > 1 ? parts[1] : parts[0];
        
        CcrConfig.Provider provider = ccrConfig.getProviders().stream()
                .filter(p -> p.getName().equalsIgnoreCase(providerName))
                .findFirst()
                .orElse(ccrConfig.getProviders().get(0));

        return new RouteResult(provider, modelName);
    }

    /**
     * 根据请求体特征识别当前的使用场景
     */
    private String detectScenario(JsonNode rootNode, String inputModel) {
        if (isLongContext(rootNode)) {
            return CcrConstants.SCENARIO_LONG_CONTEXT;
        }

        if (isBackgroundTask(inputModel)) {
            return CcrConstants.SCENARIO_BACKGROUND;
        }

        if (isWebSearchTask(rootNode)) {
            return CcrConstants.SCENARIO_WEB_SEARCH;
        }

        if (rootNode.has(CcrConstants.FIELD_THINKING)) {
            return CcrConstants.SCENARIO_THINK;
        }

        return CcrConstants.SCENARIO_DEFAULT;
    }

    private boolean isLongContext(JsonNode rootNode) {
        return calculateTokenCount(rootNode) > ccrConfig.getLongContextThreshold();
    }

    private boolean isBackgroundTask(String inputModel) {
        return inputModel.toLowerCase().contains("haiku");
    }

    private boolean isWebSearchTask(JsonNode rootNode) {
        JsonNode tools = rootNode.get(CcrConstants.FIELD_TOOLS);
        if (tools instanceof ArrayNode) {
            for (JsonNode tool : tools) {
                if (tool.path(CcrConstants.FIELD_TYPE).asText().startsWith(CcrConstants.ANT_TOOL_WEB_SEARCH)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 简易 Token 计数器（参考原项目逻辑，按 字符数/4 估算）
     */
    private int calculateTokenCount(JsonNode rootNode) {
        int charCount = 0;
        // 统计 messages 中的内容长度
        if (rootNode.has(CcrConstants.FIELD_MESSAGES) && rootNode.get(CcrConstants.FIELD_MESSAGES).isArray()) {
            for (JsonNode message : rootNode.get(CcrConstants.FIELD_MESSAGES)) {
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
        if (rootNode.has(CcrConstants.FIELD_SYSTEM)) {
            JsonNode system = rootNode.get(CcrConstants.FIELD_SYSTEM);
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
