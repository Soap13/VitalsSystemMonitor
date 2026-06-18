package com.example.vitals.models;

import java.lang.management.ThreadInfo;
import java.util.List;

public class JvmSnapshot {
    private final double heapUsedMb;
    private final double heapMaxMb;
    private final int threadCount;
    private final int peakThreadCount;
    private final double jvmCpuLoad;
    // 新增：存储所有线程详细信息的对象列表
    private final List<ThreadInfo> threadDetails;

    public JvmSnapshot(double heapUsedMb, double heapMaxMb, int threadCount, int peakThreadCount, double jvmCpuLoad, List<ThreadInfo> threadDetails) {
        this.heapUsedMb = heapUsedMb;
        this.heapMaxMb = heapMaxMb;
        this.threadCount = threadCount;
        this.peakThreadCount = peakThreadCount;
        this.jvmCpuLoad = jvmCpuLoad;
        this.threadDetails = threadDetails;
    }

    public double getHeapUsedMb() { return heapUsedMb; }
    public double getHeapMaxMb() { return heapMaxMb; }
    public int getThreadCount() { return threadCount; }
    public int getPeakThreadCount() { return peakThreadCount; }
    public double getJvmCpuLoad() { return jvmCpuLoad; }
    public List<ThreadInfo> getThreadDetails() { return threadDetails; }
}