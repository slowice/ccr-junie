package com.ccr.model;

import lombok.Data;

@Data
public class StreamContext {
    private boolean messageStarted = false;
    private boolean thinkingStarted = false;
    private boolean textStarted = false;
    private int nextBlockIndex = 0;
    private int currentBlockIndex = -1;
    private int thinkingBlockIndex = -1;
    private int textBlockIndex = -1;
    private String model = "unknown";
}
