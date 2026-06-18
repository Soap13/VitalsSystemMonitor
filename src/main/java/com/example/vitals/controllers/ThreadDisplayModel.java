package com.example.vitals.controllers;

import java.lang.management.ThreadInfo; /**
 * 专门为 TableView 视图打造的、支持原地刷新不乱跳的线程数据模型
 */
public class ThreadDisplayModel {
    private final long threadId;
    private final javafx.beans.property.SimpleStringProperty threadName;
    private final javafx.beans.property.SimpleStringProperty threadState;
    private final javafx.beans.property.SimpleLongProperty blockedCount;
    private final javafx.beans.property.SimpleLongProperty waitedCount;
    private ThreadInfo rawInfo; // 保存原始对象，用于提取堆栈

    public ThreadDisplayModel(ThreadInfo info) {
        this.threadId = info.getThreadId();
        this.threadName = new javafx.beans.property.SimpleStringProperty(info.getThreadName());
        this.threadState = new javafx.beans.property.SimpleStringProperty(info.getThreadState().toString());
        this.blockedCount = new javafx.beans.property.SimpleLongProperty(info.getBlockedCount());
        this.waitedCount = new javafx.beans.property.SimpleLongProperty(info.getWaitedCount());
        this.rawInfo = info;
    }

    // 原地更新数据，触发 JavaFX 局部 UI 刷新，彻底告别整体重绘和乱跳
    public void update(ThreadInfo info) {
        this.threadName.set(info.getThreadName());
        this.threadState.set(info.getThreadState().toString());
        this.blockedCount.set(info.getBlockedCount());
        this.waitedCount.set(info.getWaitedCount());
        this.rawInfo = info;
    }

    public long getThreadId() { return threadId; }
    public String getThreadName() { return threadName.get(); }
    public String getThreadState() { return threadState.get(); }
    public long getBlockedCount() { return blockedCount.get(); }
    public long getWaitedCount() { return waitedCount.get(); }
    public ThreadInfo getRawInfo() { return rawInfo; }
}
