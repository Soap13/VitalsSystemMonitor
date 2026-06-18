package com.example.vitals.controllers;

import com.example.vitals.models.DbSnapshot;
import com.example.vitals.models.DbSnapshot.SqlInfo;
import com.example.vitals.services.DbMonitorService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;

public class DbMonitorController {
    @FXML private TextField txtHost, txtPort, txtSid, txtUser;
    @FXML private PasswordField txtPass;
    @FXML private Button btnConnect;
    @FXML private Label lblSessions, lblSga, lblPga;

    @FXML private TableView<SqlInfo> tblSql;
    @FXML private TableColumn<SqlInfo, String> colSid, colUser, colStatus, colSqlShort;
    @FXML private TableColumn<SqlInfo, Double> colTime;
    @FXML private TextArea txtFullSql;

    private DbMonitorService dbMonitorService;
    private boolean isMonitoring = false;
    private String selectedSid = null; // 记住用户点击的Oracle会话ID


    @FXML private TableView<DbSnapshot.ConnDistribution> tblConnDist;
    @FXML private TableColumn<DbSnapshot.ConnDistribution, String> colMachine, colProgram, colConnStatus,userName;
    @FXML private TableColumn<DbSnapshot.ConnDistribution, Integer> colConnCount;

    @FXML private TableView<DbSnapshot.SlowSqlInfo> tblSlowSql;
    @FXML private TableColumn<DbSnapshot.SlowSqlInfo, String> colSlowRank, colSlowUser, colSlowSqlText;
    @FXML private TableColumn<DbSnapshot.SlowSqlInfo, Integer> colSlowExecutions, colSlowCmdType;
    @FXML private TableColumn<DbSnapshot.SlowSqlInfo, Double> colSlowTotalTime, colSlowAvgTime;
    @FXML private TextArea txtSlowSqlFull;

    @FXML private TableView<DbSnapshot.DeadlockInfo> tblDeadlocks;
    @FXML private TableColumn<DbSnapshot.DeadlockInfo, String> colDeadlockSid, colDeadlockUser, colDeadlockObject;
    @FXML private TableColumn<DbSnapshot.DeadlockInfo, String> colDeadlockType, colDeadlockHeld, colDeadlockRequested;
    @FXML private TableColumn<DbSnapshot.DeadlockInfo, Integer> colDeadlockCtime;
    @FXML private TableColumn<DbSnapshot.DeadlockInfo, Boolean> colDeadlockBlocking;
    @FXML private Label lblDeadlockCount;
    @FXML
    public void initialize() {
        // 初始化表格列映射
        colSid.setCellValueFactory(new PropertyValueFactory<>("sid"));
        colUser.setCellValueFactory(new PropertyValueFactory<>("username"));
        colStatus.setCellValueFactory(new PropertyValueFactory<>("status"));
        colTime.setCellValueFactory(new PropertyValueFactory<>("elapsedTimeSec"));
        colSqlShort.setCellValueFactory(new PropertyValueFactory<>("sqlText"));

        // 绑定连接分布表
        colMachine.setCellValueFactory(new PropertyValueFactory<>("machine"));
        colProgram.setCellValueFactory(new PropertyValueFactory<>("program"));
        colConnStatus.setCellValueFactory(new PropertyValueFactory<>("status"));
        userName.setCellValueFactory(new PropertyValueFactory<>("userName"));
        colConnCount.setCellValueFactory(new PropertyValueFactory<>("connCount"));

        // 监听表格选中行事件
        tblSql.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                selectedSid = newVal.getSid();
                txtFullSql.setText(newVal.getSqlText());
            }
        });

        // 初始化慢SQL表格列映射
        colSlowRank.setCellFactory(col -> new javafx.scene.control.TableCell<DbSnapshot.SlowSqlInfo, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow().getIndex() < 0) {
                    setText(null);
                } else {
                    setText(String.valueOf(getTableRow().getIndex() + 1));
                }
            }
        });
        colSlowUser.setCellValueFactory(new PropertyValueFactory<>("username"));
        colSlowExecutions.setCellValueFactory(new PropertyValueFactory<>("executions"));
        colSlowTotalTime.setCellValueFactory(new PropertyValueFactory<>("totalTime"));
        colSlowAvgTime.setCellValueFactory(new PropertyValueFactory<>("avgTime"));
        colSlowCmdType.setCellValueFactory(new PropertyValueFactory<>("commandType"));
        colSlowSqlText.setCellValueFactory(new PropertyValueFactory<>("sqlText"));

        // 监听慢SQL表格选中行事件
        tblSlowSql.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                txtSlowSqlFull.setText(newVal.getSqlFullText());
            }
        });


        // 初始化死锁表格列映射
        colDeadlockSid.setCellValueFactory(new PropertyValueFactory<>("sessionId"));
        colDeadlockUser.setCellValueFactory(new PropertyValueFactory<>("username"));
        colDeadlockObject.setCellValueFactory(new PropertyValueFactory<>("objectName"));
        colDeadlockType.setCellValueFactory(new PropertyValueFactory<>("lockType"));
        colDeadlockHeld.setCellValueFactory(new PropertyValueFactory<>("modeHeld"));
        colDeadlockRequested.setCellValueFactory(new PropertyValueFactory<>("modeRequested"));
        colDeadlockCtime.setCellValueFactory(new PropertyValueFactory<>("ctime"));
        colDeadlockBlocking.setCellValueFactory(new PropertyValueFactory<>("blocking"));

        // 为阻塞会话设置特殊样式
        colDeadlockBlocking.setCellFactory(col -> new javafx.scene.control.TableCell<DbSnapshot.DeadlockInfo, Boolean>() {
            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item ? "是" : "否");
                    if (item) {
                        setStyle("-fx-background-color: #ffcccc; -fx-text-fill: #c0392b; -fx-font-weight: bold;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });


        dbMonitorService = new DbMonitorService();
        dbMonitorService.setOnSucceeded(event -> {
            DbSnapshot snapshot = dbMonitorService.getValue();
            if (snapshot != null) updateUI(snapshot);
        });

        dbMonitorService.setOnFailed(event -> {
            Throwable e = event.getSource().getException();
            e.printStackTrace();
            showError("监控失败", "无法连接到Oracle数据库，请检查网络或账号权限:\n" + e.getMessage());
            stopMonitoring();
        });
    }

    @FXML
    private void handleConnect(ActionEvent event) {
        if (isMonitoring) {
            stopMonitoring();
        } else {
            dbMonitorService.updateConfig(txtHost.getText(), txtPort.getText(), txtSid.getText(), txtUser.getText(), txtPass.getText());
            dbMonitorService.restart();

            isMonitoring = true;
            btnConnect.setText("停止监控");
            btnConnect.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
            toggleInputs(true);
        }
    }

    private void stopMonitoring() {
        dbMonitorService.cancel();
        dbMonitorService.disconnect();
        isMonitoring = false;
        btnConnect.setText("连接数据库");
        btnConnect.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        toggleInputs(false);
    }

    private void updateUI(DbSnapshot snapshot) {
        // 更新指标卡
        lblSessions.setText(String.format("%d / %d (Max Limit: %d)", snapshot.getActiveSessions(), snapshot.getTotalSessions(), snapshot.getMaxSessions()));
        lblSga.setText(String.format("高速缓存: %.1f MB / 共享池: %.1f MB", snapshot.getSgaBufferCacheMb(), snapshot.getSgaSharedPoolMb()));
        lblPga.setText(String.format("%.1f MB", snapshot.getPgaAllocatedMb()));

        // 更新SQL表格
        ObservableList<SqlInfo> sqlData = FXCollections.observableArrayList(snapshot.getTopSqlList());
        tblSql.setItems(sqlData);

        // 稳住选中高亮
        if (selectedSid != null) {
            for (SqlInfo info : sqlData) {
                if (info.getSid().equals(selectedSid)) {
                    tblSql.getSelectionModel().select(info);
                    txtFullSql.setText(info.getSqlText()); // 实时更新完整SQL内容
                    break;
                }
            }
        }
        // 更新你的连接分布大盘
        tblConnDist.setItems(FXCollections.observableArrayList(snapshot.getConnDistList()));

        // 更新慢SQL表格
        ObservableList<DbSnapshot.SlowSqlInfo> slowSqlData = FXCollections.observableArrayList(snapshot.getSlowSqlList());
        tblSlowSql.setItems(slowSqlData);

        // 更新死锁信息表格
        ObservableList<DbSnapshot.DeadlockInfo> deadlockData = FXCollections.observableArrayList(snapshot.getDeadlockList());
        tblDeadlocks.setItems(deadlockData);
        lblDeadlockCount.setText(String.format("检测到 %d 个锁等待/死锁", deadlockData.size()));

        // 如果有阻塞会话，显示警告
        long blockingCount = deadlockData.stream().filter(d -> d.getBlocking()).count();
        if (blockingCount > 0) {
            lblDeadlockCount.setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
        } else {
            lblDeadlockCount.setStyle("-fx-text-fill: #27ae60;");
        }
    }

    private void toggleInputs(boolean disable) {
        txtHost.setDisable(disable); txtPort.setDisable(disable);
        txtSid.setDisable(disable); txtUser.setDisable(disable); txtPass.setDisable(disable);
    }

    private void showError(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title); alert.setHeaderText(null); alert.setContentText(content);
        alert.showAndWait();
    }
}