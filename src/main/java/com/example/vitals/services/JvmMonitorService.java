package com.example.vitals.services;

import com.example.vitals.models.JvmSnapshot;
import com.sun.management.OperatingSystemMXBean;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import javafx.util.Duration;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.List;

public class JvmMonitorService extends ScheduledService<JvmSnapshot> {

    // 核心代理接口
    private MemoryMXBean memoryMXBean;
    private ThreadMXBean threadMXBean;
    private OperatingSystemMXBean osMXBean;
    private JMXConnector jmxConnector;

    public JvmMonitorService() {
        setPeriod(Duration.seconds(1));
        initLocalConnection(); // 默认初始化为本地监控
    }

    /**
     * 初始化本地当前进程的监控
     */
    public void initLocalConnection() {
        closeRemoteConnection();
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.threadMXBean = ManagementFactory.getThreadMXBean();
        this.osMXBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    }

    /**
     * 根据输入的 IP:Port 或标准的 JMX URL 初始化连接
     */
    public void initRemoteConnection(String targetUrl) throws Exception {
        closeRemoteConnection();

        // 如果用户只输了 IP:Port，自动帮其补全为标准的 JMX RMI 协议格式
        String rawUrl = targetUrl.trim();
        if (!rawUrl.startsWith("service:jmx:")) {
            rawUrl = "service:jmx:rmi:///jndi/rmi://" + rawUrl + "/jmxrmi";
        }

        JMXServiceURL url = new JMXServiceURL(rawUrl);
        // 建立远程 JMX 连接通道
        this.jmxConnector = JMXConnectorFactory.connect(url, null);
        MBeanServerConnection connection = jmxConnector.getMBeanServerConnection();

        // 【关键点】：通过平台代理方法，直接获取远程 JVM 的数据接口对象
        this.memoryMXBean = ManagementFactory.newPlatformMXBeanProxy(connection, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
        this.threadMXBean = ManagementFactory.newPlatformMXBeanProxy(connection, ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
        this.osMXBean = ManagementFactory.newPlatformMXBeanProxy(connection, ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME, OperatingSystemMXBean.class);
    }

    public void closeRemoteConnection() {
        if (jmxConnector != null) {
            try {
                jmxConnector.close();
            } catch (Exception ignored) {}
            jmxConnector = null;
        }
    }

    @Override
    protected Task<JvmSnapshot> createTask() {
        return new Task<>() {
            @Override
            protected JvmSnapshot call() throws Exception {
                // 此时调用的如果是远程代理，拿到的就是远程服务器上的内存数据
                double heapUsed = memoryMXBean.getHeapMemoryUsage().getUsed() / (1024.0 * 1024.0);
                double heapMax = memoryMXBean.getHeapMemoryUsage().getMax() / (1024.0 * 1024.0);

                int threadCount = threadMXBean.getThreadCount();
                int peakThreads = threadMXBean.getPeakThreadCount();

                double jvmCpu = osMXBean.getProcessCpuLoad() * 100;
                if (jvmCpu < 0) jvmCpu = 0;

                ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
                List<ThreadInfo> threadDetails = Arrays.asList(threadInfos);

                return new JvmSnapshot(heapUsed, heapMax, threadCount, peakThreads, jvmCpu, threadDetails);
            }
        };
    }
}