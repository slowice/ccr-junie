# 模型响应格式与 Claude Code 兼容性说明

本文档详细说明了 GLM-5.1、MiniMax-2.7 的原始响应格式，以及 Claude Code 所需的 Anthropic 标准格式。

## 1. GLM-5.1 / MiniMax-2.7 原始响应 (OpenAI 协议)

这两个模型通常遵循 OpenAI 接口规范，其流式响应 (SSE) 的数据块如下：

### 基础对话 Chunk
```json
data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1714880000,"model":"glm-5.1","choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}
```

### 带推理内容的 Chunk (Reasoning)
GLM-5.1 和 MiniMax-2.7 在深度思考模式下会多出一个字段：
```json
data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1714880000,"model":"glm-5.1","choices":[{"index":0,"delta":{"reasoning_content":"正在思考如何回答..."},"finish_reason":null}]}
```

### 结束帧 (Usage)
```json
data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1714880000,"model":"glm-5.1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
```

---

## 2. Claude Code 需要的返回格式 (Anthropic 协议)

Claude Code 期望服务器返回符合 Anthropic 规范的事件流。

### A. 消息开始 (message_start)
```json
data: {"type":"message_start","message":{"id":"msg_123","type":"message","role":"assistant","model":"claude-3-5-sonnet","content":[],"usage":{"input_tokens":10,"output_tokens":0}}}
```

### B. 内容块增量 (content_block_delta)
```json
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}
```

### C. 思考块增量 (thinking_delta) - 可选
如果需要展示思考过程，通常需要转换为：
```json
data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"思考内容..."}}
```

### D. 消息结束 (message_delta)
这是最关键的一帧，Claude Code 会在这里读取 `input_tokens`。如果缺失或格式不对，会报错 `undefined is not an object(evaluating 'q.input_tokens')`。
```json
data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"input_tokens":10,"output_tokens":20}}
```

---

## 3. ccr-junie 的适配逻辑

`ccr-junie` 的 `TransformerServiceImpl` 承担了将 1 转换为 2 的任务：
1. **字段映射**: 将 OpenAI 的 `prompt_tokens` 映射为 Anthropic 的 `input_tokens`。
2. **事件转换**: 自动将 OpenAI 的第一个带有 `role` 的 chunk 转换为 `message_start`。
3. **安全填充**: 如果上游模型（如某些情况下的 MiniMax）没有返回 `usage`，CCR 会自动补全 `{"input_tokens": 0, "output_tokens": 0}` 以防止 Claude Code 崩溃。
4. **推理适配**: 目前会将 `reasoning_content` 识别并进行相应的包装处理。
