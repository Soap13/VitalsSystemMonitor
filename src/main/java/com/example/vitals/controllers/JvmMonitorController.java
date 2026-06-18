package com.example.vitals.controllers;

import com.example.vitals.models.JvmSnapshot;
import com.example.vitals.services.JvmMonitorService;
import com.sun.management.HotSpotDiagnosticMXBean;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;
import javafx.event.ActionEvent;

import javax.management.MBeanServer;
import java.io.File;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JvmMonitorController {

    @FXML private LineChart<Number, Number> jvmHeapChart;
    @FXML private Label lblCurrentHeap;
    @FXML private Label lblThreadCount;
    @FXML private Label lblJvmCpu;

    // 1. 将 TableView 的泛型从 ThreadInfo 改为 ThreadDisplayModel
    @FXML private TableView<ThreadDisplayModel> tblThreads;
    // 2. 同理，把下面所有 TableColumn 的第一个泛型也都改成 ThreadDisplayModel
    @FXML private TableColumn<ThreadDisplayModel, Long> colThreadId;
    @FXML private TableColumn<ThreadDisplayModel, String> colThreadName;
    @FXML private TableColumn<ThreadDisplayModel, String> colThreadState;
    @FXML private TableColumn<ThreadDisplayModel, Long> colBlockedCount;
    @FXML private TableColumn<ThreadDisplayModel, Long> colWaitedCount;
    @FXML private NumberAxis xAxis;
    // 在类变量区新增组件绑定
    @FXML private TextArea txtStackTrace;
    // 用来记忆用户当前选中的是哪辆线程 ID，防止一秒刷新一次时把用户的选中状态给重置了
    private Long selectedThreadId = null;

    private XYChart.Series<Number, Number> heapSeries;
    private long timeX = 0;
    private JvmMonitorService monitorService;
    private final ObservableList<ThreadInfo> threadObservableList = FXCollections.observableArrayList();

    @FXML private ComboBox<String> cmbTargetType;
    @FXML private TextField txtJmxUrl;
    @FXML private Button btnConnect;

    private boolean isMonitoring = false;

    // 1. 在 JvmMonitorController 类变量区，替换原有的 List 声明：
    @FXML private TextField txtThreadFilter;

    // 核心：使用包装后的 Model
    private final ObservableList<ThreadDisplayModel> masterThreadList = FXCollections.observableArrayList();
    private FilteredList<ThreadDisplayModel> filteredThreadList;

    @FXML
    public void initialize() {
        // =====================================================================
        // 1. 初始化图表（折线图、抗抖动及 2K 屏自适应设置）
        // =====================================================================
        heapSeries = new XYChart.Series<>();
        heapSeries.setName("JVM Heap Used (MB)");
        jvmHeapChart.getData().add(heapSeries);

        // 双重锁定：关闭动画与数据点小蓝圈，防止运行久了之后右侧数据点挤压、糊成一团
        jvmHeapChart.setAnimated(false);
        jvmHeapChart.setCreateSymbols(false);

        // 隐藏 X 轴多余的刻度，保证界面干净
        xAxis.setTickLabelsVisible(false);
        xAxis.setTickMarkVisible(false);

        // =====================================================================
        // 2. 初始化连接配置栏（本地/远程切换控制）
        // =====================================================================
        cmbTargetType.getItems().addAll("本地程序 (当前)", "远程 JMX 目标");
        cmbTargetType.getSelectionModel().selectFirst();

        // 监听下拉框变化，只有选择远程目标时，才允许在输入框中写 IP:Port
        cmbTargetType.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            boolean isLocal = newVal.contains("本地");
            txtJmxUrl.setDisable(isLocal);
            if (isLocal) {
                txtJmxUrl.setText("");
            }
        });

        // =====================================================================
        // 3. 初始化线程表格与过滤系统（防刷新抖动、支持按名字和状态关键字搜索）
        // =====================================================================
        // 映射自我们新写的 ThreadDisplayModel 的属性
        colThreadId.setCellValueFactory(new PropertyValueFactory<>("threadId"));
        colThreadName.setCellValueFactory(new PropertyValueFactory<>("threadName"));
        colThreadState.setCellValueFactory(new PropertyValueFactory<>("threadState"));
        colBlockedCount.setCellValueFactory(new PropertyValueFactory<>("blockedCount"));
        colWaitedCount.setCellValueFactory(new PropertyValueFactory<>("waitedCount"));

        // 构建带过滤功能的数据包装源
        filteredThreadList = new FilteredList<>(masterThreadList, p -> true);

        // 挂载搜索框的文本监听：输入内容时，表格会实时局部渲染，不阻断后台采集
        txtThreadFilter.textProperty().addListener((observable, oldValue, newValue) -> {
            filteredThreadList.setPredicate(thread -> {
                if (newValue == null || newValue.isEmpty()) {
                    return true;
                }
                String lowerCaseFilter = newValue.toLowerCase();
                return thread.getThreadName().toLowerCase().contains(lowerCaseFilter) ||
                        thread.getThreadState().toLowerCase().contains(lowerCaseFilter);
            });
        });

        // 表格真正绑定的是过滤后的容器
        tblThreads.setItems(filteredThreadList);

        // 核心高亮锁定：点击某行时记录选中的线程 ID，并解析展示它当前的堆栈轨迹
        tblThreads.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                selectedThreadId = newValue.getThreadId(); // 锁死 ID，防止刷新时高亮丢失
                displayStackTrace(newValue.getRawInfo());  // 打印堆栈明细
            }
        });

        // =====================================================================
        // 4. 配置定时数据采集服务（JvmMonitorService 核心生命周期监控）
        // =====================================================================
        monitorService = new JvmMonitorService();

        // 成功抓取数据时的回调：递交 Snapshot 并驱动 UI 刷新
        monitorService.setOnSucceeded(event -> {
            JvmSnapshot snapshot = monitorService.getValue();
            if (snapshot != null) {
                updateUI(snapshot);
            }
        });

        // 远程 JMX 连接失败时的异常捕获（如 Tomcat 没开端口、IP 输错）
        monitorService.setOnFailed(event -> {
            Throwable e = event.getSource().getException();
            if (e != null) {
                e.printStackTrace();
                showErrorAlert("连接失败", "无法建立到 JVM 目标的通信，请检查地址或 JMX 服务状态。\n" + e.getMessage());
            }
            resetConnectButton(); // 恢复“开始监控”按钮的原始绿色视觉状态
        });
        // ❌ 【就是这里】：把原本放在这里的 monitorService.start(); 删掉！！！
        // 让它保持静默状态，等待用户点击“开始监控”按钮。
    }

    private void updateUI(JvmSnapshot snapshot) {
        timeX++;

        // 1. 正常添加新点
        heapSeries.getData().add(new XYChart.Data<>(timeX, snapshot.getHeapUsedMb()));

        // 2. 保持滚动视窗：只保留最近 60 秒的数据
        if (heapSeries.getData().size() > 60) {
            heapSeries.getData().remove(0);
        }

        // 3. 【核心修正】：手动控制 X 轴范围，强行平移视窗，防止挤压
        // 这样能确保 X 轴的显示宽度永远只有 60 秒，旧数据移出视野，新数据平铺展开
        if (timeX > 60) {
            xAxis.setAutoRanging(false);       // 关闭自动缩放
            xAxis.setLowerBound(timeX - 60);   // 视窗左边界
            xAxis.setUpperBound(timeX);        // 视窗右边界
        } else {
            // 如果刚启动不满 60 秒，固定显示 0 到 60
            xAxis.setAutoRanging(false);
            xAxis.setLowerBound(0);
            xAxis.setUpperBound(60);
        }

        // 更新文本标签...
        lblCurrentHeap.setText(String.format("%.1f MB / %.1f MB", snapshot.getHeapUsedMb(), snapshot.getHeapMaxMb()));

        // 更新图表
        heapSeries.getData().add(new XYChart.Data<>(timeX, snapshot.getHeapUsedMb()));
        if (heapSeries.getData().size() > 60) {
            heapSeries.getData().remove(0);
        }

        // 更新文本标签
        lblCurrentHeap.setText(String.format("%.1f MB / %.1f MB", snapshot.getHeapUsedMb(), snapshot.getHeapMaxMb()));
        lblThreadCount.setText(String.format("Threads: %d (Peak: %d)", snapshot.getThreadCount(), snapshot.getPeakThreadCount()));
        lblJvmCpu.setText(String.format("JVM CPU: %.1f%%", snapshot.getJvmCpuLoad()));

        // 新增：刷新线程列表数据
        threadObservableList.setAll(snapshot.getThreadDetails());
        List<ThreadInfo> newThreadInfos = snapshot.getThreadDetails();

        // 1. 为了防止乱跳，先建立一个当前内存中行的映射表 (ID -> Model)
        Map<Long, ThreadDisplayModel> existingMap = new HashMap<>();
        for (ThreadDisplayModel model : masterThreadList) {
            existingMap.put(model.getThreadId(), model);
        }

        // 2. 【核心修复】：显式定义这个局部变量，用来抓取当前选中线程的最新原生堆栈
        ThreadInfo currentlySelectedRawInfo = null;
        ObservableList<ThreadDisplayModel> updatedList = FXCollections.observableArrayList();

        for (ThreadInfo info : newThreadInfos) {
            long id = info.getThreadId();

            // 如果刚好碰到了用户正点击、正盯着的那个线程，赶紧把原生引用记下来
            if (selectedThreadId != null && id == selectedThreadId) {
                currentlySelectedRawInfo = info;
            }

            if (existingMap.containsKey(id)) {
                // 如果线程本来就存在，直接原地刷新数值，表格不重绘，滚动条绝对稳如老狗
                ThreadDisplayModel model = existingMap.get(id);
                model.update(info);
                updatedList.add(model);
            } else {
                // 只有新诞生的线程才创建新行
                updatedList.add(new ThreadDisplayModel(info));
            }
        }

        // 3. 统一推回底层数据源
        masterThreadList.setAll(updatedList);

        // 4. 维持住用户的选中高亮状态，并刷新最新的下方堆栈
        if (selectedThreadId != null) {
            boolean reselected = false;
            for (ThreadDisplayModel model : filteredThreadList) {
                if (model.getThreadId() == selectedThreadId) {
                    tblThreads.getSelectionModel().select(model); // 高亮这一行

                    // 如果抓到了该线程最新的原生快照，立刻刷新下方文本框的代码行号
                    if (currentlySelectedRawInfo != null) {
                        displayStackTrace(currentlySelectedRawInfo);
                    }
                    reselected = true;
                    break;
                }
            }

            // 如果该线程在最新的这一秒钟已经寿终正寝（执行完销毁了），则清空选择器和堆栈区
            if (!reselected) {
                tblThreads.getSelectionModel().clearSelection();
                txtStackTrace.setText("");
                selectedThreadId = null;
            }
        }
    }

    public void stopMonitoring() {
        if (monitorService != null && monitorService.isRunning()) {
            monitorService.cancel();
        }
    }

/**
 * 拼装完整的线程名细信息，复刻 VisualVM 导出的 Dump 格式
 */
private void displayStackTrace(ThreadInfo info) {
    StringBuilder sb = new StringBuilder();

    // 1. 头部基础信息
    sb.append(String.format("线程名称: \"%s\" (ID=%d)\n", info.getThreadName(), info.getThreadId()));
    sb.append(String.format("线程状态: %s\n", info.getThreadState()));

    if (info.getLockName() != null) {
        sb.append(String.format("当前正等待的锁: %s (拥有者线程ID: %d)\n", info.getLockName(), info.getLockOwnerId()));
    }
    sb.append("======================================================================\n\n");

    // 2. 遍历并格式化输出每一层 Java 堆栈轨迹
    StackTraceElement[] stackTrace = info.getStackTrace();
    if (stackTrace.length == 0) {
        sb.append("  [没有可用的堆栈信息，该线程可能正处于原生/空闲状态]");
    } else {
        for (StackTraceElement element : stackTrace) {
            sb.append(String.format("  at %s.%s(%s:%d)\n",
                    element.getClassName(),
                    element.getMethodName(),
                    element.getFileName(),
                    element.getLineNumber()));
        }
    }
    // 3. 送回文本框展示
    txtStackTrace.setText(sb.toString());
}

    @FXML
    private void handleExportThreadDump(ActionEvent event) {
        // 1. 如果当前没有线程数据，直接返回
        List<ThreadInfo> currentThreads = threadObservableList;
        if (currentThreads.isEmpty()) {
            return;
        }

        // 2. 初始化 JavaFX 原生文件选择器
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("保存 Thread Dump 文件");

        // 生成一个默认的文件名，带上当前时间戳
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        fileChooser.setInitialFileName("thread_dump_" + timeStamp + ".txt");

        // 设置文件过滤器
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("文本文件 (*.txt)", "*.txt")
        );

        // 3. 弹出保存对话框（关联到当前按钮所在的窗口）
        File file = fileChooser.showSaveDialog(tblThreads.getScene().getWindow());

        if (file != null) {
            // 在后台线程或直接保存（因为数据已在内存中，直接写出即可，耗时极短）
            saveDumpToFile(file, currentThreads);
        }
    }

    /**
     * 拼装标准 JVM 格式并写入文件
     */
    private void saveDumpToFile(File file, List<ThreadInfo> threads) {
        // 使用 try-with-resources 自动关闭文件流
        try (PrintWriter writer = new PrintWriter(file, "UTF-8")) {

            String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            writer.println("======================================================================");
            writer.println("Full Thread Dump Generated by VitalsSystemMonitor");
            writer.println("Time: " + timeStr);
            writer.println("Total Threads Count: " + threads.size());
            writer.println("======================================================================\n");

            // 遍历所有线程，拼装成标准的 Dump 文本
            for (ThreadInfo info : threads) {
                writer.println(String.format("\"%s\" Id=%d Group=%s State=%s",
                        info.getThreadName(),
                        info.getThreadId(),
                        "N/A", // MXBean 拿不到 Group，填默认
                        info.getThreadState()));

                if (info.getLockName() != null) {
                    writer.println("\t- waiting on " + info.getLockName());
                    if (info.getLockOwnerName() != null) {
                        writer.println(String.format("\t- locked by \"%s\" (Id=%d)",
                                info.getLockOwnerName(), info.getLockOwnerId()));
                    }
                }

                // 打印堆栈轨迹
                for (StackTraceElement element : info.getStackTrace()) {
                    writer.println(String.format("\tat %s.%s(%s:%d)",
                            element.getClassName(),
                            element.getMethodName(),
                            element.getFileName(),
                            element.getLineNumber()));
                }
                writer.println(); // 换行隔开不同的线程
            }

            System.out.println("Thread Dump 成功导出至: " + file.getAbsolutePath());

        } catch (Exception e) {
            e.printStackTrace();
            // 这里如果是生产环境，可以加一个 JavaFX Alert 弹窗提示失败
        }
    }

    @FXML
    private void handleExportHeapDump(ActionEvent event) {
        // 1. 初始化文件选择器，限制后缀名为 .hprof (VisualVM 官方标准格式)
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("保存 JVM Heap Dump 文件");

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        fileChooser.setInitialFileName("heap_dump_" + timeStamp + ".hprof");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Java Heap Dump 文件 (*.hprof)", "*.hprof")
        );

        // 2. 弹出保存窗口
        File file = fileChooser.showSaveDialog(tblThreads.getScene().getWindow());

        if (file != null) {
            // 如果文件已存在，HotSpot API 会报错，所以如果存在则提前删除
            if (file.exists()) {
                file.delete();
            }

            // 3. 执行堆快照导出
            triggerHeapDump(file.getAbsolutePath());
        }
    }

    /**
     * 利用 HotSpot 内部诊断组件反射或直接触发 Dump
     */
    private void triggerHeapDump(String filePath) {
        try {
            // 获取本地平台的 MBean 接口服务器
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();

            // 动态将其转化为 HotSpotDiagnostic 诊断服务对象
            HotSpotDiagnosticMXBean mxBean = ManagementFactory.newPlatformMXBeanProxy(
                    server,
                    "com.sun.management:type=HotSpotDiagnostic",
                    HotSpotDiagnosticMXBean.class
            );

            // 参数说明：filePath 是保存绝对路径；true 表示只导出当前活着的（未被GC的）对象
            mxBean.dumpHeap(filePath, true);

            System.out.println("Heap Dump 成功成功，文件已保存至: " + filePath);

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("导出 Heap Dump 失败！有些 JDK 环境可能由于权限限制禁止了该操作。");
        }
    }

    @FXML
    private void handleConnect(ActionEvent event) {
        if (isMonitoring) {
            // 执行断开逻辑
            monitorService.cancel();
            monitorService.closeRemoteConnection();
            resetConnectButton();
        } else {
            // 执行连接逻辑
            try {
                String selectedMode = cmbTargetType.getSelectionModel().getSelectedItem();
                if (selectedMode.contains("远程")) {
                    String url = txtJmxUrl.getText();
                    if (url == null || url.trim().isEmpty()) {
                        showErrorAlert("参数错误", "请输入远程 JMX 地址！格式如: 192.168.1.100:9999");
                        return;
                    }
                    monitorService.initRemoteConnection(url);
                } else {
                    monitorService.initLocalConnection();
                }

                // 清理上一次的旧图表残余，重置时间轴
                heapSeries.getData().clear();
                timeX = 0;
                xAxis.setLowerBound(0);
                xAxis.setUpperBound(60);

                // 启动定时拉取
                monitorService.restart();

                // 改变按钮视觉状态
                isMonitoring = true;
                btnConnect.setText("停止监控");
                btnConnect.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-background-radius: 4px; -fx-cursor: hand;"); // 变成红色
                cmbTargetType.setDisable(true);
                txtJmxUrl.setDisable(true);

            } catch (Exception e) {
                showErrorAlert("连接配置异常", e.getMessage());
            }
        }
    }

    private void resetConnectButton() {
        isMonitoring = false;
        btnConnect.setText("开始监控");
        btnConnect.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white; -fx-background-radius: 4px; -fx-cursor: hand;"); // 恢复绿色
        cmbTargetType.setDisable(false);
        txtJmxUrl.setDisable(cmbTargetType.getSelectionModel().getSelectedIndex() == 0);
    }

    private void showErrorAlert(String title, String content) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}

