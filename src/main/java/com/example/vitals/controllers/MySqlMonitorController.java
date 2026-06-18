package com.example.vitals.controllers;

import com.example.vitals.models.MySqlSnapshot;
import com.example.vitals.services.MySqlMonitorService;
import com.example.vitals.services.MySqlMonitorService.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;

public class MySqlMonitorController {
    @FXML private TextField txtHost, txtPort, txtDatabase, txtUser;
    @FXML private PasswordField txtPass;
    @FXML private Button btnConnect;
    @FXML private Label lblConnections, lblThreads;
    @FXML private Label lblInnodbBuffer, lblKeyBuffer, lblQueryCache;

    @FXML private TableView<ProcessInfo> tblProcesses;
    @FXML private TableColumn<ProcessInfo, String> colId, colUser, colHost, colDb, colCommand, colState, colInfo;
    @FXML private TableColumn<ProcessInfo, Integer> colTime;
    @FXML private TextArea txtFullSql;

    @FXML private TableView<SlowQueryInfo> tblSlowQuery;
    @FXML private TableColumn<SlowQueryInfo, String> colSlowRank, colSlowSql, colSlowFirstSeen, colSlowLastSeen;
    @FXML private TableColumn<SlowQueryInfo, Long> colSlowExecutions, colSlowRowsExamined, colSlowRowsSent;
    @FXML private TableColumn<SlowQueryInfo, Double> colSlowTotalTime, colSlowAvgTime;
    @FXML private TextArea txtSlowSqlFull;

    @FXML private TableView<LockInfo> tblLocks;
    @FXML private TableColumn<LockInfo, String> colWaitingThread, colBlockingThread, colLockedTable, colLockedIndex;
    @FXML private TableColumn<LockInfo, String> colWaitingQuery, colBlockingQuery;
    @FXML private TableColumn<LockInfo, Integer> colWaitTime;
    @FXML private TableColumn<LockInfo, Boolean> colIsBlocking;
    @FXML private Label lblLockCount;

    private MySqlMonitorService mySqlMonitorService;
    private boolean isMonitoring = false;
    private String selectedId = null;

    @FXML
    public void initialize() {
        colId.setCellValueFactory(new PropertyValueFactory<>("id"));
        colUser.setCellValueFactory(new PropertyValueFactory<>("user"));
        colHost.setCellValueFactory(new PropertyValueFactory<>("host"));
        colDb.setCellValueFactory(new PropertyValueFactory<>("db"));
        colCommand.setCellValueFactory(new PropertyValueFactory<>("command"));
        colTime.setCellValueFactory(new PropertyValueFactory<>("time"));
        colState.setCellValueFactory(new PropertyValueFactory<>("state"));
        colInfo.setCellValueFactory(new PropertyValueFactory<>("info"));

        tblProcesses.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                selectedId = newVal.getId();
                txtFullSql.setText(newVal.getInfo());
            }
        });

        colSlowRank.setCellFactory(col -> new TableCell<SlowQueryInfo, String>() {
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
        colSlowSql.setCellValueFactory(new PropertyValueFactory<>("sqlText"));
        colSlowExecutions.setCellValueFactory(new PropertyValueFactory<>("executions"));
        colSlowTotalTime.setCellValueFactory(new PropertyValueFactory<>("totalTime"));
        colSlowAvgTime.setCellValueFactory(new PropertyValueFactory<>("avgTime"));
        colSlowRowsExamined.setCellValueFactory(new PropertyValueFactory<>("rowsExamined"));
        colSlowRowsSent.setCellValueFactory(new PropertyValueFactory<>("rowsSent"));
        colSlowFirstSeen.setCellValueFactory(new PropertyValueFactory<>("firstSeen"));
        colSlowLastSeen.setCellValueFactory(new PropertyValueFactory<>("lastSeen"));

        tblSlowQuery.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                txtSlowSqlFull.setText(newVal.getSqlText());
            }
        });

        colWaitingThread.setCellValueFactory(new PropertyValueFactory<>("waitingThread"));
        colBlockingThread.setCellValueFactory(new PropertyValueFactory<>("blockingThread"));
        colLockedTable.setCellValueFactory(new PropertyValueFactory<>("lockedTable"));
        colLockedIndex.setCellValueFactory(new PropertyValueFactory<>("lockedIndex"));
        colWaitingQuery.setCellValueFactory(new PropertyValueFactory<>("waitingQuery"));
        colBlockingQuery.setCellValueFactory(new PropertyValueFactory<>("blockingQuery"));
        colWaitTime.setCellValueFactory(new PropertyValueFactory<>("waitTime"));
        colIsBlocking.setCellValueFactory(new PropertyValueFactory<>("isBlocking"));

        colIsBlocking.setCellFactory(col -> new TableCell<LockInfo, Boolean>() {
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

        mySqlMonitorService = new MySqlMonitorService();
        mySqlMonitorService.setOnSucceeded(event -> {
            MySqlSnapshot snapshot = mySqlMonitorService.getValue();
            if (snapshot != null) updateUI(snapshot);
        });

        mySqlMonitorService.setOnFailed(event -> {
            Throwable e = event.getSource().getException();
            e.printStackTrace();
            showError("监控失败", "无法连接到MySQL数据库:\n" + e.getMessage());
            stopMonitoring();
        });
    }

    @FXML
    private void handleConnect(ActionEvent event) {
        if (isMonitoring) {
            stopMonitoring();
        } else {
            mySqlMonitorService.updateConfig(txtHost.getText(), txtPort.getText(),
                    txtDatabase.getText(), txtUser.getText(), txtPass.getText());
            mySqlMonitorService.restart();

            isMonitoring = true;
            btnConnect.setText("停止监控");
            btnConnect.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
            toggleInputs(true);
        }
    }

    private void stopMonitoring() {
        mySqlMonitorService.cancel();
        mySqlMonitorService.disconnect();
        isMonitoring = false;
        btnConnect.setText("连接数据库");
        btnConnect.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        toggleInputs(false);
    }

    private void updateUI(MySqlSnapshot snapshot) {
        lblConnections.setText(String.format("%d / %d (Max Connections)",
                snapshot.getActiveConnections(),
                snapshot.getMaxConnections()));
        lblThreads.setText(String.format("运行中: %.0f / 已连接: %.0f",
                snapshot.getThreadsRunning(),
                snapshot.getThreadsConnected()));

        lblInnodbBuffer.setText(String.format("InnoDB缓冲池: %.1f MB / %.1f MB (%.1f%%)",
                snapshot.getInnodbBufferPoolUsed(),
                snapshot.getInnodbBufferPoolSize(),
                snapshot.getInnodbBufferPoolUsagePercent()));

        lblKeyBuffer.setText(String.format("Key缓冲区: %.2f MB / %.2f MB",
                snapshot.getKeyBufferUsed(),
                snapshot.getKeyBufferSize()));

        lblQueryCache.setText(String.format("查询缓存: %.2f MB / %.2f MB",
                snapshot.getQueryCacheUsed(),
                snapshot.getQueryCacheSize()));

        ObservableList<ProcessInfo> processData = FXCollections.observableArrayList(snapshot.getProcessList());
        tblProcesses.setItems(processData);

        if (selectedId != null) {
            for (ProcessInfo info : processData) {
                if (info.getId().equals(selectedId)) {
                    tblProcesses.getSelectionModel().select(info);
                    txtFullSql.setText(info.getInfo());
                    break;
                }
            }
        }

        ObservableList<SlowQueryInfo> slowQueryData = FXCollections.observableArrayList(snapshot.getSlowQueryList());
        tblSlowQuery.setItems(slowQueryData);

        ObservableList<LockInfo> lockData = FXCollections.observableArrayList(snapshot.getLockList());
        tblLocks.setItems(lockData);
        lblLockCount.setText(String.format("检测到 %d 个锁等待", lockData.size()));

        long blockingCount = lockData.stream().filter(l -> l.getIsBlocking()).count();
        if (blockingCount > 0) {
            lblLockCount.setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
        } else {
            lblLockCount.setStyle("-fx-text-fill: #27ae60;");
        }
    }

    private void toggleInputs(boolean disable) {
        txtHost.setDisable(disable);
        txtPort.setDisable(disable);
        txtDatabase.setDisable(disable);
        txtUser.setDisable(disable);
        txtPass.setDisable(disable);
    }

    private void showError(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
