# Oracle-PostgreSQL 数据同步工具

这是一个基于 Spring Boot 的数据同步工具，用于将 Oracle 数据库中的表数据同步到 PostgreSQL 数据库。支持并行/串行同步模式、定时任务调度、批量处理等特性。

## 主要特性

- 支持并行/串行两种同步模式
- 基于 Cron 表达式的灵活调度
- 批量处理提升性能
- 自动表结构同步
- 增量/全量同步支持
- 失败重试机制
- 可配置的同步规则

## 快速开始

### 1. 配置数据源
在 `application.yml` 中配置 Oracle 和 PostgreSQL 数据源：

### 2. 配置同步任务
在 `application.yml` 中配置需要同步的表：

## 核心功能说明

### 同步模式

1. 并行模式 (parallel-sync: true)
   - 多个表同时进行同步
   - 适合表之间没有依赖关系的场景
   - 可以更快完成全量同步

2. 串行模式 (parallel-sync: false)
   - 按照 order 顺序依次同步
   - 适合表之间有依赖关系的场景
   - 避免数据一致性问题

### 数据处理

- 批量处理：通过 batch-size 配置批处理大小
- 增量同步：支持通过 whereCondition 配置增量条件
- 自动建表：目标表不存在时自动创建
- 智能更新：根据主键判断是否需要更新

### 错误处理

- 自动重试：失败任务自动重试
- 可配置重试次数和间隔
- 详细的错误日志记录

## 配置说明

### 全局配置

```yaml
sync:
  postgres-schema: 目标schema
  oracle-schema: 源schema
  batch-size: 批处理大小
  parallel-sync: 是否并行处理
  maxRetries: 最大重试次数
  retryInterval: 重试间隔(毫秒)
  threadPoolSize: 线程池大小
```

### 表配置

```yaml
tables:
  - tableName: 表名
    syncCron: cron表达式
    primaryKey: 主键列名
    columns: 需要同步的列(为空则同步所有列)
    whereCondition: 同步条件
    fetchSize: 每次查询记录数
    order: 执行顺序
```

## 使用建议

1. 性能优化
   - 合理设置 batch-size 和 fetchSize
   - 对于大表建议使用增量同步
   - 适当调整线程池大小

2. 监控建议
   - 关注同步任务的日志
   - 监控数据一致性
   - 定期检查同步状态

3. 注意事项
   - 确保数据库连接配置正确
   - 注意数据类型映射
   - 考虑网络带宽限制

## 技术栈

- Spring Boot
- Spring JDBC
- HikariCP
- Oracle JDBC
- PostgreSQL JDBC
