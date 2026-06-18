package com.example.vitals.services;

import com.example.vitals.models.DbSnapshot;
import com.example.vitals.models.DbSnapshot.SqlInfo;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import javafx.util.Duration;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DbMonitorService extends ScheduledService<DbSnapshot> {
    private String url;
    private String username;
    private String password;
    private Connection connection;

    public DbMonitorService() {
        setPeriod(Duration.seconds(2)); // 数据库监控不需要高刷，2秒一次即可
    }

    public void updateConfig(String host, String port, String sid, String user, String pass) {
        // 拼接 Oracle 标准 Thin 连接串
        this.url = "jdbc:oracle:thin:@" + host + ":" + port + ":" + sid;
        this.username = user;
        this.password = pass;
    }

    private void ensureConnected() throws SQLException {
        if (connection == null || connection.isClosed()) {
            try {
                Class.forName("oracle.jdbc.driver.OracleDriver");
            } catch (ClassNotFoundException e) {
                throw new SQLException("未找到 Oracle JDBC 驱动，请检查依赖。");
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
    protected Task<DbSnapshot> createTask() {
        return new Task<>() {
            @Override
            protected DbSnapshot call() throws Exception {
                ensureConnected();

                int activeSessions = 0;
                int totalSessions = 0;
                int maxSessions = 100;
                double sgaBuffer = 0, sgaShared = 0, pgaAlloc = 0;
                List<SqlInfo> sqlList = new ArrayList<>();

                // 1. 查询会话状态与上限数
                String sessionSql = "SELECT COUNT(*) as total, SUM(DECODE(status, 'ACTIVE', 1, 0)) as active FROM v$session WHERE type = 'USER'";
                try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sessionSql)) {
                    if (rs.next()) {
                        totalSessions = rs.getInt("total");
                        activeSessions = rs.getInt("active");
                    }
                }

                // 2. 查询 Oracle 参数配置的最大会话上限
                String limitSql = "SELECT value FROM v$parameter WHERE name = 'sessions'";
                try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(limitSql)) {
                    if (rs.next()) { maxSessions = rs.getInt("value"); }
                }

                // 3. 查询 SGA 和 PGA 内存大盘
                String memSql = "SELECT name, bytes/1024/1024 as mb FROM v$sgainfo WHERE name IN ('Buffer Cache Size', 'Shared Pool Size') " +
                        "UNION ALL " +
                        "SELECT 'PGA Allocated', value/1024/1024 FROM v$pgastat WHERE name = 'total PGA allocated'";
                try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(memSql)) {
                    while (rs.next()) {
                        String name = rs.getString("name");
                        double mb = rs.getDouble("mb");
                        if (name.contains("Buffer")) sgaBuffer = mb;
                        else if (name.contains("Shared")) sgaShared = mb;
                        else pgaAlloc = mb;
                    }
                }

                // 4. 查询当前正在执行的、最耗时的前 10 条活跃 SQL (排查卡死的绝对核心)
                String topSql = "SELECT s.sid, s.username, s.status, s.last_call_et, q.sql_text " +
                        "FROM v$session s " +
                        "left JOIN v$sql q ON s.sql_hash_value = q.hash_value " +
                        //"WHERE s.type = 'USER' AND s.status = 'ACTIVE' AND ROWNUM <= 10 " +
                        "ORDER BY s.last_call_et DESC";
                try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(topSql)) {
                    while (rs.next()) {
                        sqlList.add(new SqlInfo(
                                rs.getString("sid"),
                                rs.getString("username"),
                                rs.getString("status"),
                                rs.getDouble("last_call_et"), // 已执行时间（秒）
                                rs.getString("sql_text")
                        ));
                    }
                }

                // 在 DbMonitorService.java 的 Task 内部：
                List<DbSnapshot.ConnDistribution> distList = new ArrayList<>();

                String distSql = "SELECT NVL(s.machine, 'UNKNOWN'), NVL(p.program, 'UNKNOWN'), NVL(s.status, 'BACKGROUND'),NVL(s.username,''), COUNT(*) as conn_count " +
                        "FROM v$process p LEFT JOIN v$session s ON s.paddr = p.addr " +
                        "GROUP BY s.machine, p.program,s.USERNAME, s.status " +
                        "ORDER BY conn_count DESC";

                try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(distSql)) {
                    while (rs.next()) {
                        distList.add(new DbSnapshot.ConnDistribution(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getInt(5)
                        ));
                    }
                }

                // 5. 查询最耗时的 TOP 50 SQL（按平均执行时间排序）
                List<DbSnapshot.SlowSqlInfo> slowSqlList = new ArrayList<>();
                String slowSqlQuery = "SELECT * FROM (" +
                        "SELECT sa.SQL_TEXT, sa.SQL_FULLTEXT, sa.EXECUTIONS, " +
                        "ROUND(sa.ELAPSED_TIME / 1000000, 2) AS TOTAL_TIME, " +
                        "ROUND(sa.ELAPSED_TIME / 1000000 / DECODE(sa.EXECUTIONS, 0, 1, sa.EXECUTIONS), 2) AS AVG_TIME, " +
                        "sa.COMMAND_TYPE, sa.PARSING_USER_ID, u.username, sa.HASH_VALUE " +
                        "FROM v$sqlarea sa " +
                        "LEFT JOIN all_users u ON sa.PARSING_USER_ID = u.user_id " +
                        "WHERE u.USERNAME = ? AND sa.EXECUTIONS > 0 " +
                        "ORDER BY (sa.ELAPSED_TIME / DECODE(sa.EXECUTIONS, 0, 1, sa.EXECUTIONS)) DESC" +
                        ") WHERE ROWNUM < 50";

                try (PreparedStatement pstmt = connection.prepareStatement(slowSqlQuery)) {
                    pstmt.setString(1, username.toUpperCase());
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            slowSqlList.add(new DbSnapshot.SlowSqlInfo(
                                    rs.getString("SQL_TEXT"),
                                    rs.getString("SQL_FULLTEXT"),
                                    rs.getInt("EXECUTIONS"),
                                    rs.getDouble("TOTAL_TIME"),
                                    rs.getDouble("AVG_TIME"),
                                    rs.getInt("COMMAND_TYPE"),
                                    rs.getInt("PARSING_USER_ID"),
                                    rs.getString("username"),
                                    rs.getLong("HASH_VALUE")
                            ));
                        }
                    }
                }
                // 6. 查询死锁和锁等待信息
                List<DbSnapshot.DeadlockInfo> deadlockList = new ArrayList<>();
                String deadlockSql = "SELECT l.session_id, s.username, o.object_name, " +
                        "l.lock_type, l.mode_held, l.mode_requested, l.ctime, " +
                        "CASE WHEN s.blocking_session IS NOT NULL THEN 'Y' ELSE 'N' END as blocking " +
                        "FROM dba_waiters w " +
                        "JOIN v$locked_object l ON l.session_id = w.holding_session " +
                        "LEFT JOIN dba_objects o ON l.object_id = o.object_id " +
                        "LEFT JOIN v$session s ON l.session_id = s.sid " +
                        "UNION ALL " +
                        "SELECT s.sid as session_id, s.username, o.object_name, " +
                        "'TM' as lock_type, " +
                        "DECODE(l.lmode, 0, 'None', 1, 'Null', 2, 'Row-S', 3, 'Row-X', 4, 'Share', 5, 'S/Row-X', 6, 'Exclusive') as mode_held, " +
                        "DECODE(l.request, 0, 'None', 1, 'Null', 2, 'Row-S', 3, 'Row-X', 4, 'Share', 5, 'S/Row-X', 6, 'Exclusive') as mode_requested, " +
                        "l.ctime, " +
                        "CASE WHEN l.block > 0 THEN 'Y' ELSE 'N' END as blocking " +
                        "FROM v$lock l " +
                        "LEFT JOIN dba_objects o ON l.id1 = o.object_id " +
                        "LEFT JOIN v$session s ON l.sid = s.sid " +
                        "WHERE l.type IN ('TX', 'TM') AND l.request > 0";

                try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(deadlockSql)) {
                    while (rs.next()) {
                        deadlockList.add(new DbSnapshot.DeadlockInfo(
                                rs.getString("session_id"),
                                rs.getString("username"),
                                rs.getString("object_name"),
                                rs.getString("lock_type"),
                                rs.getString("mode_held"),
                                rs.getString("mode_requested"),
                                rs.getInt("ctime"),
                                "Y".equals(rs.getString("blocking"))
                        ));
                    }
                } catch (SQLException e) {
                    // 如果没有DBA权限，使用简化的锁查询
                    String simpleLockSql = "SELECT s.sid as session_id, s.username, o.object_name, " +
                            "DECODE(l.type, 'TX', 'Transaction', 'TM', 'DML', l.type) as lock_type, " +
                            "DECODE(l.lmode, 0, 'None', 1, 'Null', 2, 'Row-S', 3, 'Row-X', 4, 'Share', 5, 'S/Row-X', 6, 'Exclusive') as mode_held, " +
                            "DECODE(l.request, 0, 'None', 1, 'Null', 2, 'Row-S', 3, 'Row-X', 4, 'Share', 5, 'S/Row-X', 6, 'Exclusive') as mode_requested, " +
                            "l.ctime, " +
                            "CASE WHEN l.block > 0 THEN 'Y' ELSE 'N' END as blocking " +
                            "FROM v$lock l " +
                            "LEFT JOIN dba_objects o ON l.id1 = o.object_id " +
                            "LEFT JOIN v$session s ON l.sid = s.sid " +
                            "WHERE l.type IN ('TX', 'TM') AND (l.request > 0 OR l.block > 0)";

                    try (Statement stmt2 = connection.createStatement(); ResultSet rs2 = stmt2.executeQuery(simpleLockSql)) {
                        while (rs2.next()) {
                            deadlockList.add(new DbSnapshot.DeadlockInfo(
                                    rs2.getString("session_id"),
                                    rs2.getString("username"),
                                    rs2.getString("object_name"),
                                    rs2.getString("lock_type"),
                                    rs2.getString("mode_held"),
                                    rs2.getString("mode_requested"),
                                    rs2.getInt("ctime"),
                                    "Y".equals(rs2.getString("blocking"))
                            ));
                        }
                    }
                }

                // 最后装配进返回的快照中：
                return new DbSnapshot(activeSessions, totalSessions, maxSessions, sgaBuffer, sgaShared, pgaAlloc, sqlList, distList, slowSqlList, deadlockList);
            }
        };
    }
}