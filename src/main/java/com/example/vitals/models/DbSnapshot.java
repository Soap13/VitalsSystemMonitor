package com.example.vitals.models;

import java.util.List;

public class DbSnapshot {
    private final int activeSessions;
    private final int totalSessions;
    private final int maxSessions;
    private final double sgaBufferCacheMb;
    private final double sgaSharedPoolMb;
    private final double pgaAllocatedMb;
    private final List<SqlInfo> topSqlList;
    private final List<SlowSqlInfo> slowSqlList;
    private final List<DeadlockInfo> deadlockList;
    public DbSnapshot(int activeSessions, int totalSessions, int maxSessions,
                      double sgaBufferCacheMb, double sgaSharedPoolMb,
                      double pgaAllocatedMb, List<SqlInfo> topSqlList,
                      List<ConnDistribution> connDistList, List<SlowSqlInfo> slowSqlList,
                      List<DeadlockInfo> deadlockList) {
        this.activeSessions = activeSessions;
        this.totalSessions = totalSessions;
        this.maxSessions = maxSessions;
        this.sgaBufferCacheMb = sgaBufferCacheMb;
        this.sgaSharedPoolMb = sgaSharedPoolMb;
        this.pgaAllocatedMb = pgaAllocatedMb;
        this.topSqlList = topSqlList;
        this.connDistList = connDistList;
        this.slowSqlList = slowSqlList;
        this.deadlockList = deadlockList;
    }


    // 提供所有的 Getter 方法...
    public int getActiveSessions() { return activeSessions; }
    public int getTotalSessions() { return totalSessions; }
    public int getMaxSessions() { return maxSessions; }
    public double getSgaBufferCacheMb() { return sgaBufferCacheMb; }
    public double getSgaSharedPoolMb() { return sgaSharedPoolMb; }
    public double getPgaAllocatedMb() { return pgaAllocatedMb; }
    public List<SqlInfo> getTopSqlList() { return topSqlList; }

    // 内部类：慢SQL/热点SQL明细
    public static class SqlInfo {
        private final String sid;
        private final String username;
        private final String status;
        private final double elapsedTimeSec;
        private final String sqlText;

        public SqlInfo(String sid, String username, String status, double elapsedTimeSec, String sqlText) {
            this.sid = sid;
            this.username = username;
            this.status = status;
            this.elapsedTimeSec = elapsedTimeSec;
            this.sqlText = sqlText;
        }

        public String getSid() { return sid; }
        public String getUsername() { return username; }
        public String getStatus() { return status; }
        public double getElapsedTimeSec() { return elapsedTimeSec; }
        public String getSqlText() { return sqlText; }
    }

    // 1. 在 DbSnapshot.java 内部追加一个模型类
    public static class ConnDistribution {
        private final String machine;
        private final String program;
        private final String status;
        private final String userName;
        private final int connCount;

        public ConnDistribution(String machine, String program, String status, String userName, int connCount) {
            this.machine = machine;
            this.program = program;
            this.status = status;
            this.userName = userName;
            this.connCount = connCount;
        }

        public String getMachine() { return machine; }
        public String getProgram() { return program; }
        public String getStatus() { return status; }
        public String getUserName() { return userName; }
        public int getConnCount() { return connCount; }
    }

    // 2. 同时在 DbSnapshot 外层类中，追加一个 List 属性和构造传参：
    private final List<ConnDistribution> connDistList;
    public List<ConnDistribution> getConnDistList() { return connDistList; }

    public static class SlowSqlInfo {
        private final String sqlText;
        private final String sqlFullText;
        private final int executions;
        private final double totalTime;
        private final double avgTime;
        private final int commandType;
        private final int userId;
        private final String username;
        private final long hashValue;

        public SlowSqlInfo(String sqlText, String sqlFullText, int executions,
                           double totalTime, double avgTime, int commandType,
                           int userId, String username, long hashValue) {
            this.sqlText = sqlText;
            this.sqlFullText = sqlFullText;
            this.executions = executions;
            this.totalTime = totalTime;
            this.avgTime = avgTime;
            this.commandType = commandType;
            this.userId = userId;
            this.username = username;
            this.hashValue = hashValue;
        }

        public String getSqlText() { return sqlText; }
        public String getSqlFullText() { return sqlFullText; }
        public int getExecutions() { return executions; }
        public double getTotalTime() { return totalTime; }
        public double getAvgTime() { return avgTime; }
        public int getCommandType() { return commandType; }
        public int getUserId() { return userId; }
        public String getUsername() { return username; }
        public long getHashValue() { return hashValue; }
    }

    public List<SlowSqlInfo> getSlowSqlList() { return slowSqlList; }

    public static class DeadlockInfo {
        private final String sessionId;
        private final String username;
        private final String objectName;
        private final String lockType;
        private final String modeHeld;
        private final String modeRequested;
        private final Integer ctime;
        private final Boolean blocking;

        public DeadlockInfo(String sessionId, String username, String objectName,
                            String lockType, String modeHeld, String modeRequested,
                            Integer ctime, Boolean blocking) {
            this.sessionId = sessionId;
            this.username = username;
            this.objectName = objectName;
            this.lockType = lockType;
            this.modeHeld = modeHeld;
            this.modeRequested = modeRequested;
            this.ctime = ctime;
            this.blocking = blocking;
        }

        public String getSessionId() { return sessionId; }
        public String getUsername() { return username; }
        public String getObjectName() { return objectName; }
        public String getLockType() { return lockType; }
        public String getModeHeld() { return modeHeld; }
        public String getModeRequested() { return modeRequested; }
        public Integer getCtime() { return ctime; }
        public Boolean getBlocking() { return blocking; }
    }

    public List<DeadlockInfo> getDeadlockList() { return deadlockList; }
}

