package com.example.oraclepostgressync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "sync")
@Component
public class SyncConfig {
    private List<TableSyncConfig> tables;
    private String postgresSchema;
    private String oracleSchema;
    private Integer batchSize = 1000;           // 批处理大小
    private Integer maxRetries = 3;             // 最大重试次数
    private Integer retryInterval = 1000;       // 重试间隔(毫秒)
    private Boolean parallelSync;       // 是否启用并行同步
    private Integer threadPoolSize = 5;         // 并行处理线程池大小

    @Data
    public static class TableSyncConfig {
        private String tableName;               // 表名
        private String syncCron;                // 同步周期(cron表达式)
        private String primaryKey;              // 主键
        private List<String> columns;           // 需要同步的列
        private String whereCondition;          // 同步条件(可选)
        private Integer fetchSize = 2000;       // 每次查询的记录数
        private Integer order = 0;              // 执行顺序（串行模式时使用）
    }
}
