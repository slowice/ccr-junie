package com.ccr.service;

import com.ccr.config.CcrConfig;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 路由服务接口，负责根据请求内容识别场景并选择最合适的供应商和模型
 */
public interface RouterService {

    /**
     * 路由结果类
     */
    public static class RouteResult {
        private CcrConfig.Provider provider; // 选定的供应商
        private String targetModel;          // 目标模型名称

        public RouteResult(CcrConfig.Provider provider, String targetModel) {
            this.provider = provider;
            this.targetModel = targetModel;
        }

        public CcrConfig.Provider getProvider() { return provider; }
        public String getTargetModel() { return targetModel; }
    }

    /**
     * 获取路由信息
     * 
     * @param requestBody 请求体字符串
     * @return 路由结果 (Provider + TargetModel)
     */
    RouteResult getRoute(String requestBody);
}
