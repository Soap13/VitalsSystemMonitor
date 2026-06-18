package com.example.vitals.services;

import com.example.vitals.models.MySqlSnapshot;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import javafx.util.Duration;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MySqlMonitorService extends ScheduledService<MySqlSnapshot> {
    private String url;
    private String username;
    private String password;
    private Connection connection;

    public MySqlMonitorService() {
        setPeriod(Duration.seconds(2));
    }

    public void updateConfig(String host, String port, String database, String user, String pass) {
        this.url = "jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
        this.username = user;
        this.password = pass;
    }

    private void ensureConnected() throws SQLException {
        if (connection == null || connection.isClosed()) {
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException e) {
                throw new SQLException("未找到 MySQL JDBC 驱动，请检查依赖。");
            }
            connection = DriverManager.getConnection(url, username, password);
        }
    }

    public void disconnect() {
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
            connection = null;
        }
    }

    @Override
    protected Task<MySqlSnapshot> createTask() {
        return new Task<>() {
            @Override
            protected MySqlSnapshot call() throws Exception {
                ensureConnected();

                int activeConnections = 0;
                int maxConnections = 151;
                double threadsRunning = 0;
                double threadsConnected = 0;

                double innodbBufferPoolSize = 0;
                double innodbBufferPoolUsed = 0;
                double innodbBufferPoolUsagePercent = 0;
                double keyBufferSize = 0;
                double keyBufferUsed = 0;
                double queryCacheSize = 0;
                double queryCacheUsed = 0;

                List<ProcessInfo> processList = new ArrayList<>();
                List<SlowQueryInfo> slowQueryList = new ArrayList<>();
                List<LockInfo> lockList = new ArrayList<>();

                // 1. 查询连接数和线程状态
                String statusSql = "SHOW STATUS LIKE 'Threads_%'";
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(statusSql)) {
                    while (rs.next()) {
                        String varName = rs.getString("Variable_name");
                        int value = rs.getInt("Value");
                        if ("Threads_running".equals(varName)) {
                            threadsRunning = value;
                        } else if ("Threads_connected".equals(varName)) {
                            threadsConnected = value;
                            activeConnections = value;
                        }
                    }
                }

                // 2. 查询最大连接数配置
                String maxConnSql = "SHOW VARIABLES LIKE 'max_connections'";
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(maxConnSql)) {
                    if (rs.next()) {
                        maxConnections = rs.getInt("Value");
                    }
                }

                String bufferPoolSql = "SELECT @@innodb_buffer_pool_size as pool_size";
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(bufferPoolSql)) {
                    if (rs.next()) {
                        innodbBufferPoolSize = rs.getDouble("pool_size") / 1024.0 / 1024.0; // 字节转MB
                    }
                }

                String bufferPoolStatusSql = "SHOW STATUS LIKE 'Innodb_buffer_pool_pages_%'";
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(bufferPoolStatusSql)) {
                    long totalPages = 0;
                    long freePages = 0;
                    long dataPages = 0;

                    while (rs.next()) {
                        String varName = rs.getString("Variable_name");
                        long value = rs.getLong("Value");

                        if ("Innodb_buffer_pool_pages_total".equals(varName)) {
                            totalPages = value;
                        } else if ("Innodb_buffer_pool_pages_free".equals(varName)) {
                            freePages = value;
                        } else if ("Innodb_buffer_pool_pages_data".equals(varName)) {
                            dataPages = value;
                        }
                    }

                    if (totalPages > 0) {
                        long usedPages = totalPages - freePages;
                        // 正确计算：页数 * 页大小(16KB) / 1024 转换为MB
                        innodbBufferPoolUsed = (usedPages * 16384L) / 1024.0 / 1024.0;
                        innodbBufferPoolUsagePercent = (usedPages * 100.0) / totalPages;
                    }
                }

                // 4. 查询 Key Buffer 使用情况（MyISAM）
                String keyBufferSql = "SHOW VARIABLES LIKE 'key_buffer_size'";
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(keyBufferSql)) {
                    if (rs.next()) {
                        keyBufferSize = rs.getDouble("Value") / 1024.0 / 1024.0; // 字节转MB
                    }
                }

                String keyBufferStatusSql = "SHOW STATUS LIKE 'Key_blocks_used'";
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(keyBufferStatusSql)) {
                    if (rs.next()) {
                        long blocksUsed = rs.getLong("Value");
                        // Key block 大小是 1024 字节
                        keyBufferUsed = (blocksUsed * 1024.0) / 1024.0 / 1024.0;
                    }
                }

                // 5. 查询 Query Cache 使用情况
                String queryCacheSql = "SHOW VARIABLES LIKE 'query_cache_size'";
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(queryCacheSql)) {
                    if (rs.next()) {
                        queryCacheSize = rs.getDouble("Value") / 1024.0 / 1024.0; // 字节转MB
                    }
                }

                String queryCacheStatusSql = "SHOW STATUS LIKE 'Qcache_free_memory'";
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(queryCacheStatusSql)) {
                    if (rs.next()) {
                        long freeMemory = rs.getLong("Value");
                        // 如果 query_cache_size 为 0，说明未启用查询缓存（MySQL 8.0 默认禁用）
                        if (queryCacheSize > 0) {
                            queryCacheUsed = ((queryCacheSize * 1024.0 * 1024.0) - freeMemory) / 1024.0 / 1024.0;
                        } else {
                            queryCacheUsed = 0;
                        }
                    }
                }

                // 6. 查询当前正在执行的进程列表（类似 Oracle 的 v$session）
                String processSql = "SELECT ID, USER, HOST, DB, COMMAND, TIME, STATE, INFO " +
                        "FROM INFORMATION_SCHEMA.PROCESSLIST " +
                        "WHERE COMMAND != 'Sleep' " +
                        "ORDER BY TIME DESC LIMIT 50";
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(processSql)) {
                    while (rs.next()) {
                        processList.add(new ProcessInfo(
                                rs.getString("ID"),
                                rs.getString("USER"),
                                rs.getString("HOST"),
                                rs.getString("DB"),
                                rs.getString("COMMAND"),
                                rs.getInt("TIME"),
                                rs.getString("STATE"),
                                rs.getString("INFO")
                        ));
                    }
                }

                // 4. 查询慢查询日志（最近50条）
                String slowQuerySql = "SELECT * FROM (" +
                        "SELECT DIGEST_TEXT as SQL_TEXT, " +
                        "COUNT_STAR as EXECUTIONS, " +
                        "ROUND(SUM_TIMER_WAIT/1000000000000, 2) as TOTAL_TIME, " +
                        "ROUND(AVG_TIMER_WAIT/1000000000000, 2) as AVG_TIME, " +
                        "SUM_ROWS_EXAMINED as ROWS_EXAMINED, " +
                        "SUM_ROWS_SENT as ROWS_SENT, " +
                        "FIRST_SEEN, LAST_SEEN " +
                        "FROM performance_schema.events_statements_summary_by_digest " +
                        "WHERE DIGEST_TEXT IS NOT NULL " +
                        "AND COUNT_STAR > 0 " +
                        "ORDER BY SUM_TIMER_WAIT DESC " +
                        "LIMIT 50) t";

                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(slowQuerySql)) {
                    while (rs.next()) {
                        slowQueryList.add(new SlowQueryInfo(
                                rs.getString("SQL_TEXT"),
                                rs.getLong("EXECUTIONS"),
                                rs.getDouble("TOTAL_TIME"),
                                rs.getDouble("AVG_TIME"),
                                rs.getLong("ROWS_EXAMINED"),
                                rs.getLong("ROWS_SENT"),
                                rs.getTimestamp("FIRST_SEEN").toString(),
                                rs.getTimestamp("LAST_SEEN").toString()
                        ));
                    }
                } catch (SQLException e) {
                    System.err.println("慢查询查询失败: " + e.getMessage());
                }


                // 优先使用 MySQL 8.0+ 的 performance_schema.data_lock_waits
                String lockSql = "SELECT r.TRX_MYSQL_THREAD_ID as WAITING_THREAD, " +
                        "r.TRX_QUERY as WAITING_QUERY, " +
                        "r.TRX_STATE as WAITING_STATE, " +
                        "b.TRX_MYSQL_THREAD_ID as BLOCKING_THREAD, " +
                        "b.TRX_QUERY as BLOCKING_QUERY, " +
                        "dl.OBJECT_SCHEMA as LOCKED_SCHEMA, " +
                        "dl.OBJECT_NAME as LOCKED_TABLE, " +
                        "dl.INDEX_NAME as LOCKED_INDEX, " +
                        "TIMESTAMPDIFF(SECOND, r.TRX_WAIT_STARTED, NOW()) as WAIT_TIME " +
                        "FROM performance_schema.data_lock_waits dw " +
                        "INNER JOIN information_schema.innodb_trx b " +
                        "    ON b.TRX_ID = dw.BLOCKING_ENGINE_TRANSACTION_ID " +
                        "INNER JOIN information_schema.innodb_trx r " +
                        "    ON r.TRX_ID = dw.REQUESTING_ENGINE_TRANSACTION_ID " +
                        "LEFT JOIN performance_schema.data_locks dl " +
                        "    ON dl.ENGINE_TRANSACTION_ID = r.TRX_ID";

                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(lockSql)) {
                    while (rs.next()) {
                        lockList.add(new LockInfo(
                                rs.getString("WAITING_THREAD"),
                                rs.getString("BLOCKING_THREAD"),
                                rs.getString("LOCKED_TABLE"),
                                rs.getString("LOCKED_INDEX"),
                                rs.getString("WAITING_QUERY"),
                                rs.getString("BLOCKING_QUERY"),
                                rs.getInt("WAIT_TIME"),
                                true
                        ));
                    }
                } catch (SQLException e) {
                    System.err.println("performance_schema 锁查询失败，尝试 INNODB_TRX: " + e.getMessage());

                    // 降级方案1：使用 information_schema.innodb_trx 查找 LOCK WAIT 状态的事务
                    String fallbackLockSql = "SELECT t.trx_id, " +
                            "t.trx_mysql_thread_id as waiting_thread, " +
                            "t.trx_state, " +
                            "t.trx_query as waiting_query, " +
                            "t.trx_wait_started, " +
                            "TIMESTAMPDIFF(SECOND, t.trx_wait_started, NOW()) as wait_time, " +
                            "t.trx_tables_in_use, " +
                            "t.trx_tables_locked " +
                            "FROM information_schema.innodb_trx t " +
                            "WHERE t.trx_state = 'LOCK WAIT'";

                    try (Statement stmt2 = connection.createStatement();
                         ResultSet rs2 = stmt2.executeQuery(fallbackLockSql)) {
                        while (rs2.next()) {
                            lockList.add(new LockInfo(
                                    rs2.getString("waiting_thread"),
                                    null,
                                    null,
                                    null,
                                    rs2.getString("waiting_query"),
                                    null,
                                    rs2.getInt("wait_time"),
                                    true
                            ));
                        }
                    } catch (SQLException e2) {
                        System.err.println("INNODB_TRX 查询也失败: " + e2.getMessage());

                        // 降级方案2：通过 PROCESSLIST 查找长时间运行的查询
                        String simpleLockSql = "SELECT ID as thread_id, " +
                                "USER, " +
                                "HOST, " +
                                "DB, " +
                                "COMMAND, " +
                                "TIME as duration, " +
                                "STATE, " +
                                "INFO as query_text " +
                                "FROM information_schema.processlist " +
                                "WHERE COMMAND != 'Sleep' " +
                                "AND TIME > 5 " +
                                "ORDER BY TIME DESC";

                        try (Statement stmt3 = connection.createStatement();
                             ResultSet rs3 = stmt3.executeQuery(simpleLockSql)) {
                            while (rs3.next()) {
                                lockList.add(new LockInfo(
                                        rs3.getString("thread_id"),
                                        null,
                                        null,
                                        null,
                                        rs3.getString("query_text") + " [State: " + rs3.getString("STATE") + ", Duration: " + rs3.getInt("duration") + "s]",
                                        null,
                                        rs3.getInt("duration"),
                                        false
                                ));
                            }
                        }
                    }
                }
                return new MySqlSnapshot(activeConnections, maxConnections, threadsRunning,
                        threadsConnected, innodbBufferPoolSize, innodbBufferPoolUsed,
                        innodbBufferPoolUsagePercent, keyBufferSize, keyBufferUsed,
                        queryCacheSize, queryCacheUsed, processList, slowQueryList, lockList);
            }
        };
    }

    public static class ProcessInfo {
        private final String id;
        private final String user;
        private final String host;
        private final String db;
        private final String command;
        private final int time;
        private final String state;
        private final String info;

        public ProcessInfo(String id, String user, String host, String db,
                           String command, int time, String state, String info) {
            this.id = id;
            this.user = user;
            this.host = host;
            this.db = db;
            this.command = command;
            this.time = time;
            this.state = state;
            this.info = info;
        }

        public String getId() { return id; }
        public String getUser() { return user; }
        public String getHost() { return host; }
        public String getDb() { return db; }
        public String getCommand() { return command; }
        public int getTime() { return time; }
        public String getState() { return state; }
        public String getInfo() { return info; }
    }

    public static class SlowQueryInfo {
        private final String sqlText;
        private final long executions;
        private final double totalTime;
        private final double avgTime;
        private final long rowsExamined;
        private final long rowsSent;
        private final String firstSeen;
        private final String lastSeen;

        public SlowQueryInfo(String sqlText, long executions, double totalTime,
                             double avgTime, long rowsExamined, long rowsSent,
                             String firstSeen, String lastSeen) {
            this.sqlText = sqlText;
            this.executions = executions;
            this.totalTime = totalTime;
            this.avgTime = avgTime;
            this.rowsExamined = rowsExamined;
            this.rowsSent = rowsSent;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
        }

        public String getSqlText() { return sqlText; }
        public long getExecutions() { return executions; }
        public double getTotalTime() { return totalTime; }
        public double getAvgTime() { return avgTime; }
        public long getRowsExamined() { return rowsExamined; }
        public long getRowsSent() { return rowsSent; }
        public String getFirstSeen() { return firstSeen; }
        public String getLastSeen() { return lastSeen; }
    }

    public static class LockInfo {
        private final String waitingThread;
        private final String blockingThread;
        private final String lockedTable;
        private final String lockedIndex;
        private final String waitingQuery;
        private final String blockingQuery;
        private final int waitTime;
        private final Boolean isBlocking;

        public LockInfo(String waitingThread, String blockingThread, String lockedTable,
                        String lockedIndex, String waitingQuery, String blockingQuery,
                        int waitTime, Boolean isBlocking) {
            this.waitingThread = waitingThread;
            this.blockingThread = blockingThread;
            this.lockedTable = lockedTable;
            this.lockedIndex = lockedIndex;
            this.waitingQuery = waitingQuery;
            this.blockingQuery = blockingQuery;
            this.waitTime = waitTime;
            this.isBlocking = isBlocking;
        }

        public String getWaitingThread() { return waitingThread; }
        public String getBlockingThread() { return blockingThread; }
        public String getLockedTable() { return lockedTable; }
        public String getLockedIndex() { return lockedIndex; }
        public String getWaitingQuery() { return waitingQuery; }
        public String getBlockingQuery() { return blockingQuery; }
        public int getWaitTime() { return waitTime; }
        public Boolean getIsBlocking() { return isBlocking; }
    }
}
