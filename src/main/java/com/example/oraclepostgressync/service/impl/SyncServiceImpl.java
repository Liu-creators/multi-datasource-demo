package com.example.oraclepostgressync.service.impl;

import com.example.oraclepostgressync.config.SyncConfig;
import com.example.oraclepostgressync.service.SyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.lang.NonNull;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SyncServiceImpl implements SyncService {

    // 默认配置参数
    private static final int DEFAULT_BATCH_SIZE = 1000;      // 默认批处理大小
    private static final int DEFAULT_FETCH_SIZE = 2000;      // 默认每次查询记录数
    private static final int DEFAULT_MAX_RETRIES = 3;        // 默认最大重试次数
    private static final int DEFAULT_RETRY_INTERVAL = 1000;  // 默认重试间隔(毫秒)
    private static final int DEFAULT_THREAD_POOL_SIZE = 5;   // 默认线程池大小

    // 数据源注入
    @Autowired
    @Qualifier("oracleJdbcTemplate")
    private JdbcTemplate oracleJdbcTemplate;    // Oracle数据源

    @Autowired
    @Qualifier("postgresJdbcTemplate")
    private JdbcTemplate postgresJdbcTemplate;  // PostgreSQL数据源

    @Autowired
    private SyncConfig syncConfig;              // 同步配置

    private final ExecutorService executorService;  // 线程池服务

    // 构造函数，初始化线程池
    public SyncServiceImpl() {
        this.executorService = Executors.newFixedThreadPool(DEFAULT_THREAD_POOL_SIZE);
    }

    /**
     * 同步指定表的数据
     * @param config 表同步配置
     */
    @Override
    public void syncTable(SyncConfig.TableSyncConfig config) {
        try {
            log.info("开始同步表: {}", config.getTableName());
            
            // 获取配置参数，如果未配置则使用默认值
            int batchSize = Optional.ofNullable(syncConfig.getBatchSize()).orElse(DEFAULT_BATCH_SIZE);
            int fetchSize = Optional.ofNullable(config.getFetchSize()).orElse(DEFAULT_FETCH_SIZE);

            // 确保目标表存在
            ensureTableExists(config);

            // 获取需要同步的列
            List<String> columns = getColumnsToSync(config);
            String columnsStr = String.join(",", columns);
            
            // 构建基础查询SQL
            String baseSql = buildBaseSql(config, columnsStr);
            
            // 分批处理数据
            processDataInBatches(config, columns, baseSql, batchSize, fetchSize);

        } catch (Exception e) {
            log.error("同步表 {} 时发生错误", config.getTableName(), e);
            throw new RuntimeException("数据同步失败", e);
        }
    }

    /**
     * 确保目标表存在，不存在则创建
     */
    private void ensureTableExists(SyncConfig.TableSyncConfig config) {
        if (!isTableExists(config.getTableName(), syncConfig.getPostgresSchema())) {
            createTable(config);
            log.info("表 {} 不存在,已创建", config.getTableName());
        }
    }

    /**
     * 构建基础查询SQL
     */
    private String buildBaseSql(SyncConfig.TableSyncConfig config, String columnsStr) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT ").append(columnsStr)
                .append(" FROM ").append(syncConfig.getOracleSchema())
                .append(".").append(config.getTableName());

        if (StringUtils.hasText(config.getWhereCondition())) {
            sqlBuilder.append(" WHERE ").append(config.getWhereCondition());
        }

        return sqlBuilder.toString();
    }

    /**
     * 分批处理数据
     */
    private void processDataInBatches(SyncConfig.TableSyncConfig config, List<String> columns, 
            String baseSql, int batchSize, int fetchSize) {
        int offset = 0;
        int totalCount = 0;
        
        while (true) {
            // 构建分页SQL并查询数据
            String paginationSql = buildPaginationSql(baseSql, offset, fetchSize);
            List<Map<String, Object>> batchData = retryTemplate(() -> 
                oracleJdbcTemplate.queryForList(paginationSql));

            if (batchData.isEmpty()) {
                break;
            }

            // 将数据分割成小批次并并行处理
            List<List<Map<String, Object>>> subBatches = splitIntoBatches(batchData, batchSize);
            List<CompletableFuture<Void>> futures = subBatches.stream()
                    .map(batch -> CompletableFuture.runAsync(() -> 
                        processBatchWithRetry(config, columns, batch), executorService))
                    .collect(Collectors.toList());

            // 等待所有批次处理完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            totalCount += batchData.size();
            offset += fetchSize;
            log.info("表 {} 已同步 {} 条数据", config.getTableName(), totalCount);
        }
    }

    /**
     * 重试模板方法
     */
    private <T> T retryTemplate(Supplier<T> operation) {
        int retries = 0;
        while (retries < DEFAULT_MAX_RETRIES) {
            try {
                return operation.get();
            } catch (Exception e) {
                retries++;
                if (retries == DEFAULT_MAX_RETRIES) {
                    throw e;
                }
                try {
                    Thread.sleep(DEFAULT_RETRY_INTERVAL);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
        throw new RuntimeException("重试次数超过最大限制");
    }

    /**
     * 带重试机制的批处理
     */
    private void processBatchWithRetry(SyncConfig.TableSyncConfig config, 
            List<String> columns, List<Map<String, Object>> batch) {
        retryTemplate(() -> {
            processBatch(config, columns, batch);
            return null;
        });
    }

    /**
     * 将数据列表分割成小批次
     * @param data 原始数据列表
     * @param batchSize 批次大小
     * @return 分割后的批次列表
     */
    private List<List<Map<String, Object>>> splitIntoBatches(List<Map<String, Object>> data, int batchSize) {
        List<List<Map<String, Object>>> batches = new ArrayList<>();
        for (int i = 0; i < data.size(); i += batchSize) {
            batches.add(data.subList(i, Math.min(i + batchSize, data.size())));
        }
        return batches;
    }

    /**
     * 构建插入SQL语句
     */
    private String buildInsertSql(String tableName, List<String> columns) {
        String columnStr = columns.stream().map(String::toLowerCase).collect(Collectors.joining(","));
        String placeholders = String.join(",", Collections.nCopies(columns.size(), "?"));
        return String.format("INSERT INTO %s.%s (%s) VALUES (%s)", 
            syncConfig.getPostgresSchema(),
            tableName.toLowerCase(), 
            columnStr, 
            placeholders);
    }

    /**
     * 获取需要同步的列
     * 如果未指定列，则同步所有列
     */
    private List<String> getColumnsToSync(SyncConfig.TableSyncConfig config) {
        if (!CollectionUtils.isEmpty(config.getColumns())) {
            return config.getColumns();
        } else {
            // 如果未指定同步列,则同步所有列
            List<Map<String, Object>> columnInfo = oracleJdbcTemplate.queryForList(
                "SELECT * FROM " + syncConfig.getOracleSchema() + "." + config.getTableName() + " WHERE ROWNUM < 2");
            return new ArrayList<>(columnInfo.get(0).keySet());
        }
    }

    /**
     * 检查表是否存在
     */
    private boolean isTableExists(String tableName, String schema) {
        try {
            postgresJdbcTemplate.queryForObject(
                    "SELECT 1 FROM information_schema.tables WHERE table_name = ? AND table_schema = ?",
                    Integer.class,
                    tableName.toLowerCase(),
                    schema.toLowerCase()
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 创建表
     * 从Oracle获取表结构并在PostgreSQL中创建对应的表
     */
    private void createTable(SyncConfig.TableSyncConfig config) {
        List<Map<String, Object>> columnInfo = oracleJdbcTemplate.queryForList(
            "SELECT * FROM " + syncConfig.getOracleSchema() + "." + config.getTableName() + " WHERE ROWNUM < 2");
        
        StringBuilder createTableSql = new StringBuilder("CREATE TABLE ");
        createTableSql.append(syncConfig.getPostgresSchema())
                .append(".")
                .append(config.getTableName().toLowerCase())
                .append(" (");

        List<String> columnDefs = new ArrayList<>();
        for (Map.Entry<String, Object> entry : columnInfo.get(0).entrySet()) {
            columnDefs.add(entry.getKey().toLowerCase() + " " + getColumnType(entry.getValue()));
        }

        createTableSql.append(String.join(",", columnDefs))
                .append(")");

        postgresJdbcTemplate.execute(createTableSql.toString());
    }

    /**
     * 根据Java对象类型获取PostgreSQL对应的数据类型
     */
    private String getColumnType(Object value) {
        if (value instanceof String) {
            return "VARCHAR";
        } else if (value instanceof Integer) {
            return "INTEGER";
        } else if (value instanceof Long) {
            return "BIGINT";
        } else if (value instanceof Double) {
            return "DOUBLE PRECISION";
        } else if (value instanceof java.sql.Timestamp) {
            return "TIMESTAMP";
        } else {
            return "VARCHAR";
        }
    }

    private boolean checkRecordExists(String tableName, String primaryKey, Object primaryKeyValue) {
        String sql = String.format("SELECT 1 FROM %s.%s WHERE %s = ?", 
                syncConfig.getPostgresSchema(), 
                tableName.toLowerCase(), 
                primaryKey.toLowerCase());
        try {
            Integer result = postgresJdbcTemplate.queryForObject(sql, Integer.class, primaryKeyValue);
            return result != null;
        } catch (Exception e) {
            return false;
        }
    }


    private String buildPaginationSql(String sql, int offset, int limit) {
        return String.format(
            "SELECT a.* FROM ( SELECT ROWNUM rnum, t.* FROM (%s) t WHERE ROWNUM <= %d) a WHERE rnum > %d",
            sql, offset + limit, offset
        );
    }

    private void processBatch(SyncConfig.TableSyncConfig config, List<String> columns, List<Map<String, Object>> batch) {
        List<Map<String, Object>> insertList = new ArrayList<>();
        List<Map<String, Object>> updateList = new ArrayList<>();

        // 分类需要插入和更新的记录
        for (Map<String, Object> record : batch) {
            Object primaryKeyValue = record.get(config.getPrimaryKey());
            if (primaryKeyValue != null) {
                if (checkRecordExists(config.getTableName(), config.getPrimaryKey(), primaryKeyValue)) {
                    updateList.add(record);
                } else {
                    insertList.add(record);
                }
            }
        }

        // 批量处理插入和更新
        if (!insertList.isEmpty()) {
            batchInsert(config.getTableName(), columns, insertList);
            log.debug("表 {} 插入 {} 条记录", config.getTableName(), insertList.size());
        }
        
        if (!updateList.isEmpty()) {
            batchUpdate(config.getTableName(), columns, updateList, config.getPrimaryKey());
            log.debug("表 {} 更新 {} 条记录", config.getTableName(), updateList.size());
        }
    }

    private void batchUpdate(String tableName, List<String> columns, List<Map<String, Object>> dataList, String primaryKey) {
        List<String> updateColumns = columns.stream()
                .filter(col -> !col.equalsIgnoreCase(primaryKey))
                .collect(Collectors.toList());
                
        String sql = buildUpdateSql(tableName, updateColumns, primaryKey);
        
        postgresJdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
                Map<String, Object> data = dataList.get(i);
                int paramIndex = 1;
                for (String col : updateColumns) {
                    ps.setObject(paramIndex++, data.get(col));
                }
                ps.setObject(paramIndex, data.get(primaryKey));
            }

            @Override
            public int getBatchSize() {
                return dataList.size();
            }
        });
    }

    private void batchInsert(String tableName, List<String> columns, List<Map<String, Object>> dataList) {
        String sql = buildInsertSql(tableName, columns);
        
        postgresJdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
                Map<String, Object> data = dataList.get(i);
                int paramIndex = 1;
                for (String col : columns) {
                    ps.setObject(paramIndex++, data.get(col));
                }
            }

            @Override
            public int getBatchSize() {
                return dataList.size();
            }
        });
    }

    private String buildUpdateSql(String tableName, List<String> updateColumns, String primaryKey) {
        String setClause = updateColumns.stream()
                .map(col -> col.toLowerCase() + "=?")
                .collect(Collectors.joining(","));
                
        return String.format("UPDATE %s.%s SET %s WHERE %s=?",
                syncConfig.getPostgresSchema(),
                tableName.toLowerCase(),
                setClause,
                primaryKey.toLowerCase());
    }
}