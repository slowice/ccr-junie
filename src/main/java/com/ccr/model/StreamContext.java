package com.ccr.model;

import lombok.Data;
import java.util.HashMap;
import java.util.Map;

/**
 * 流式处理上下文，用于在协议转换过程中维护状态信息
 */
@Data
public class StreamContext {
    /**
     * message_start 事件是否已发送
     */
    private boolean messageStarted = false;

    /**
     * thinking 内容块是否已开始
     */
    private boolean thinkingStarted = false;

    /**
     * text 内容块是否已开始
     */
    private boolean textStarted = false;

    /**
     * 下一个内容块的索引
     */
    private int nextBlockIndex = 0;

    /**
     * 当前活动的内容块索引
     */
    private int currentBlockIndex = -1;

    /**
     * thinking 块的固定索引
     */
    private int thinkingBlockIndex = -1;

    /**
     * text 块的固定索引
     */
    private int textBlockIndex = -1;

    /**
     * 使用的模型名称
     */
    private String model = "unknown";

    /**
     * 工具调用 ID 与 Anthropic 内容块索引的映射表
     */
    private Map<Integer, Integer> toolCallIndexToContentBlockIndex = new HashMap<>();
}
