# 响应协议格式指南 (OpenAI vs Anthropic)

本文档旨在说明 OpenAI Chat Completion 协议与 Anthropic Messages 协议在模型响应（尤其是包含工具调用和思维链时）的标准结构，作为 `ccr-junie` 转换逻辑的参考。

## 1. OpenAI 协议响应结构 (上游模型)

### 1.1 非流式工具调用 (Chat Completion)
当模型决定调用工具时，`message` 对象中会包含 `tool_calls` 列表。

```json
{
  "id": "chatcmpl-987",
  "object": "chat.completion",
  "created": 1715000000,
  "model": "gpt-4o",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": null,
        "tool_calls": [
          {
            "id": "call_123",
            "type": "function",
            "function": {
              "name": "get_weather",
              "arguments": "{\"location\":\"Beijing\"}"
            }
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ],
  "usage": {
    "prompt_tokens": 100,
    "completion_tokens": 30,
    "total_tokens": 130
  }
}
```

### 1.2 流式工具调用 (SSE Chunk)
流式响应通过多个 Chunk 增量返回。工具调用通常包含一个“定义帧”和若干“参数增量帧”。

```json
// 1. 定义帧：指定工具 ID 和名称
data: {"id":"cc-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_123","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}

// 2. 增量帧：传输 arguments 字符串片段
data: {"id":"cc-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"loca"}}]},"finish_reason":null}]}
data: {"id":"cc-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"tion\":\"Be"}}]},"finish_reason":null}]}

// 3. 结束帧：标识 tool_calls 结束
data: {"id":"cc-1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":100,"completion_tokens":30,"total_tokens":130}}
```

### 1.3 思维链内容 (Reasoning Content)
对于支持推理的模型（如 DeepSeek-R1, o1），通常在 `delta` 中返回 `reasoning_content`。

```json
data: {"id":"cc-2","choices":[{"index":0,"delta":{"reasoning_content":"正在分析气象数据..."},"finish_reason":null}]}
```

---

## 2. Anthropic 协议响应结构 (Claude Code 要求)

### 2.1 非流式工具调用 (Messages)
Anthropic 将工具调用视为 `content` 数组中的一个 `tool_use` 类型块。

```json
{
  "id": "msg_01Xnu998YpLp1",
  "type": "message",
  "role": "assistant",
  "model": "claude-3-5-sonnet",
  "content": [
    {
      "type": "text",
      "text": "好的，我来帮你查询天气。"
    },
    {
      "type": "tool_use",
      "id": "toolu_01A024",
      "name": "get_weather",
      "input": {"location": "Beijing"}
    }
  ],
  "stop_reason": "tool_use",
  "stop_sequence": null,
  "usage": {
    "input_tokens": 100,
    "output_tokens": 30
  }
}
```

### 2.2 流式响应事件流 (SSE)
Anthropic 的流式输出由一系列结构化事件组成，必须严格遵守顺序。

#### A. 消息开始 (message_start)
```json
data: {"type":"message_start","message":{"id":"msg_123","type":"message","role":"assistant","model":"claude-3-5-sonnet","content":[],"usage":{"input_tokens":100,"output_tokens":0}}}
```

#### B. 工具调用块 (tool_use sequence)
当发生工具调用时，会依次发送 start -> delta -> stop 事件。

```json
// 1. 开启工具块：包含 ID 和名称
data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_123","name":"get_weather","input":{}}}

// 2. 参数增量： partial_json 必须是合法的 JSON 字符串片段
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"location\":"}}
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":" \"Beijing\"}"}}

// 3. 结束工具块
data: {"type":"content_block_stop","index":1}
```

#### C. 思维链块 (thinking)
Claude 3.7+ 支持原生的 `thinking` 块。

```json
data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","signature":"sig_abc"}}
data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"思考中..."}}
data: {"type":"content_block_stop","index":0}
```

#### D. 消息增量与结束 (message_delta & message_stop)
在 `message_delta` 中返回最终的 `stop_reason` 和 `usage`。

```json
data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":30}}
data: {"type":"message_stop"}
```

## 3. 核心转换映射关系

| 维度 | OpenAI (输入) | Anthropic (输出) | 备注 |
| :--- | :--- | :--- | :--- |
| **结束原因** | `finish_reason: "tool_calls"` | `stop_reason: "tool_use"` | |
| **工具 ID** | `tool_calls[i].id` | `tool_use.id` | Anthropic 要求以 `toolu_` 开头 |
| **工具参数** | `function.arguments` (String) | `input` (Object) 或 `input_json_delta` | 转换时需确保 String 转 JSON 对象 |
| **思维链** | `reasoning_content` | `thinking` | Anthropic 需包含 `signature` |
| **缓存统计** | `prompt_tokens_details.cached_tokens` | `cache_read_input_tokens` | 属于 `usage` 对象字段 |
| **输入 Token** | `prompt_tokens` | `input_tokens` | Anthropic 的 `input_tokens` 不含缓存部分 |
