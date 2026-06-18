package com.example.vitals.models;

import com.example.vitals.services.MySqlMonitorService.*;
import java.util.List;

public class MySqlSnapshot {
    private final int activeConnections;
    private final int maxConnections;
    private final double threadsRunning;
    private final double threadsConnected;
    private final double innodbBufferPoolSize;
    private final double innodbBufferPoolUsed;
    private final double innodbBufferPoolUsagePercent;
    private final double keyBufferSize;
    private final double keyBufferUsed;
    private final double queryCacheSize;
    private final double queryCacheUsed;
    private final List<ProcessInfo> processList;
    private final List<SlowQueryInfo> slowQueryList;
    private final List<LockInfo> lockList;

    public MySqlSnapshot(int activeConnections, int maxConnections,
                         double threadsRunning, double threadsConnected,
                         double innodbBufferPoolSize, double innodbBufferPoolUsed,
                         double innodbBufferPoolUsagePercent,
                         double keyBufferSize, double keyBufferUsed,
                         double queryCacheSize, double queryCacheUsed,
                         List<ProcessInfo> processList,
                         List<SlowQueryInfo> slowQueryList,
                         List<LockInfo> lockList) {
        this.activeConnections = activeConnections;
        this.maxConnections = maxConnections;
        this.threadsRunning = threadsRunning;
        this.threadsConnected = threadsConnected;
        this.innodbBufferPoolSize = innodbBufferPoolSize;
        this.innodbBufferPoolUsed = innodbBufferPoolUsed;
        this.innodbBufferPoolUsagePercent = innodbBufferPoolUsagePercent;
        this.keyBufferSize = keyBufferSize;
        this.keyBufferUsed = keyBufferUsed;
        this.queryCacheSize = queryCacheSize;
        this.queryCacheUsed = queryCacheUsed;
        this.processList = processList;
        this.slowQueryList = slowQueryList;
        this.lockList = lockList;
    }

    public int getActiveConnections() { return activeConnections; }
    public int getMaxConnections() { return maxConnections; }
    public double getThreadsRunning() { return threadsRunning; }
    public double getThreadsConnected() { return threadsConnected; }
    public double getInnodbBufferPoolSize() { return innodbBufferPoolSize; }
    public double getInnodbBufferPoolUsed() { return innodbBufferPoolUsed; }
    public double getInnodbBufferPoolUsagePercent() { return innodbBufferPoolUsagePercent; }
    public double getKeyBufferSize() { return keyBufferSize; }
    public double getKeyBufferUsed() { return keyBufferUsed; }
    public double getQueryCacheSize() { return queryCacheSize; }
    public double getQueryCacheUsed() { return queryCacheUsed; }
    public List<ProcessInfo> getProcessList() { return processList; }
    public List<SlowQueryInfo> getSlowQueryList() { return slowQueryList; }
    public List<LockInfo> getLockList() { return lockList; }
}
