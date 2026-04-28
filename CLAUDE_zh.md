# CLAUDE_zh.md (ccr-junie)

## 项目概述
这是 Claude Code Router 核心代理功能的 Java (Spring Boot) 实现版本。它旨在作为 TypeScript 实现的轻量级、高性能替代方案，特别针对流式传输 (SSE) 透传进行了优化。

**当前状态**：已验证正常工作。
该模块能够正确处理来自 Claude Code 的流式和非流式请求，并将其转发至智谱 AI（或任何兼容 Anthropic 的 API）。
此外，它还支持 `/v1/chat/completions` 接口，并提供 OpenAI 与 Anthropic 格式之间的自动协议转换。

## 运行环境
- **Java 版本**: 17
- **框架**: Spring Boot 3.2.5 (WebFlux)
- **端口**: 3456 (默认)
- **目标 URL**: 在 `src/main/resources/application.properties` 中配置

## 构建与运行命令

### 构建项目
```bash
mvn clean package
```

### 运行项目
```bash
mvn spring-boot:run
```

### 运行测试
```bash
mvn test
```

## 核心架构
- **路由系统 (v2)**：`RouterService` 实现了与原 TypeScript 项目一致的路由逻辑：
  - 支持显式的 `Provider,Model` 输入。
  - 自动识别场景：`longContext`（如果预估 Token > `longContextThreshold`）、`webSearch`（存在 web_search 工具）、`think`（存在 `thinking` 字段）以及 `background`（模型名包含 `haiku`）。
  - Token 估算规则为 `字符数 / 4`。
- **模型重写**：在转发之前，服务器会自动更新请求体中的 `model` 字段，以匹配路由配置中指定的目标模型名称。
- **配置管理**：通过 `CcrConfig` 管理 `application.properties`。路由映射使用 `供应商名称,模型名称` 格式，以保持与 `ccr` 项目风格一致。

- **转换器系统 (Transformer System)**：`TransformerService` 提供协议自动转换功能：
  - **OpenAI -> Anthropic**：将 `/v1/chat/completions` 请求转换为 Anthropic 格式（提取 system prompt，设置 max_tokens）。
  - **Anthropic -> OpenAI**：将 Anthropic 响应转回 OpenAI 格式（包括 SSE 流）。

## 关键指南
0. **参考实现原则**：`ccr-junie` 中的所有逻辑必须严格参考并镜像原 `ccr` (TypeScript) 项目的实现。这适用于每一个模块（路由、转换、配置等）。禁止在不查阅 TS 代码逻辑的情况下基于猜测实现功能。
1. **模块完整性**：核心代理和路由逻辑已稳定。对任何模块的修改都必须与对应 TypeScript 版本的逻辑保持同步。
2. **响应式规范**：本项目使用 Spring WebFlux。确保所有新特性保持非阻塞特性。
3. **多供应商支持**：添加新供应商时，需同时更新 `application.properties` 中的 `ccr.providers` 和 `ccr.router` 映射。
4. **代码规范**：代码要尽可能规范。使用 Logger 代替 `System.out`。避免硬编码字符串，使用对象或常量代替以增加可复用性。Service 层应遵循 `Interface + Impl` 模式。

## 验证清单
在提交对此模块的任何更改前：
- [ ] `mvn test` 通过。
- [ ] 使用 `curl` 手动验证流式 (`Accept: text/event-stream`) 和非流式响应。
- [ ] (可选) 使用 `--settings` 指向此本地服务器启动 Claude Code，验证端到端功能。

## 路线图 / 缺失特性 (对比 TS 版本)
- **高级转换器 (Transformers)**：支持更多非 Anthropic 兼容的供应商（Google, AWS, Groq）。
- **精准分词 (Tokenization)**：引入 `tiktoken` 等效库（如 JTokkit），以实现精确的 `longContext` 路由。
- **插件系统**：移植 `token-speed`、Webhook 和 Output Manager 功能。
- **项目级路由**：自动检测 `.claude/projects/` 目录下的项目级配置。
- **管理界面与 CLI**：移植 React UI 和 CLI 管理命令。
- **系统特性**：身份验证 (`APIKEY`)、缓存机制以及配置中的环境变量插值。
