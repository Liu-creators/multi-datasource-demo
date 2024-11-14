package com.example.oraclepostgressync.task;

import com.example.oraclepostgressync.config.SyncConfig;
import com.example.oraclepostgressync.service.SyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.ScheduledFuture;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Comparator;
import java.util.stream.Collectors;

@Component
@Slf4j
@EnableScheduling
public class SyncScheduler {

    @Autowired
    private SyncService syncService;

    @Autowired
    private SyncConfig syncConfig;

    private final ThreadPoolTaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new HashMap<>();
    private final Object syncLock = new Object();  // 用于串行同步的锁
    private volatile int currentOrder = 1;  // 当前执行顺序
    private final Object orderLock = new Object();  // 用于控制执行顺序的锁

    public SyncScheduler() {
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setPoolSize(10);
        this.taskScheduler.setThreadNamePrefix("SyncScheduler-");
        this.taskScheduler.initialize();
    }

    @PostConstruct
    public void initSync() {
        log.info("开始初始化同步任务调度...");
        log.info("同步模式: {}", Boolean.TRUE.equals(syncConfig.getParallelSync()) ? "并行" : "串行");
        
        if (Boolean.TRUE.equals(syncConfig.getParallelSync())) {
            // 并行模式：直接调度所有表的同步任务
            initParallelSync();
        } else {
            // 串行模式：按顺序调度表的同步任务
            initSerialSync();
        }
    }

    private void initParallelSync() {
        syncConfig.getTables().forEach(config -> {
            try {
                scheduleParallelSync(config);
                log.info("表 {} 的并行同步任务已调度, cron表达式: {}", 
                    config.getTableName(), config.getSyncCron());
            } catch (Exception e) {
                log.error("为表 {} 配置并行同步计划时出错: {}", 
                    config.getTableName(), e.getMessage(), e);
            }
        });
    }

    private void initSerialSync() {
        // 按order排序
        List<SyncConfig.TableSyncConfig> sortedTables = syncConfig.getTables().stream()
            .sorted(Comparator.comparing(SyncConfig.TableSyncConfig::getOrder))
            .collect(Collectors.toList());

        sortedTables.forEach(config -> {
            try {
                scheduleSerialSync(config);
                log.info("表 {} 的串行同步任务已调度, cron表达式: {}, 执行顺序: {}", 
                    config.getTableName(), config.getSyncCron(), config.getOrder());
            } catch (Exception e) {
                log.error("为表 {} 配置串行同步计划时出错: {}", 
                    config.getTableName(), e.getMessage(), e);
            }
        });
    }

    private void scheduleParallelSync(SyncConfig.TableSyncConfig config) {
        cancelExistingTask(config.getTableName());
        ScheduledFuture<?> scheduledTask = taskScheduler.schedule(
            () -> executeSync(config),
            new CronTrigger(config.getSyncCron())
        );
        scheduledTasks.put(config.getTableName(), scheduledTask);
    }

    private void scheduleSerialSync(SyncConfig.TableSyncConfig config) {
        cancelExistingTask(config.getTableName());
        ScheduledFuture<?> scheduledTask = taskScheduler.schedule(
            () -> {
                synchronized (syncLock) {
                    // 检查是否轮到当前表执行
                    if (config.getOrder() != currentOrder) {
                        log.debug("表 {} 等待执行，当前执行顺序: {}, 表顺序: {}", 
                            config.getTableName(), currentOrder, config.getOrder());
                        return;
                    }

                    try {
                        executeSync(config);
                    } finally {
                        // 更新执行顺序
                        synchronized (orderLock) {
                            currentOrder++;
                            // 如果所有表都执行完一轮，重置顺序
                            if (currentOrder > getMaxOrder()) {
                                currentOrder = 1;
                                log.info("所有表同步完成一轮，重置执行顺序");
                            }
                        }
                    }
                }
            },
            new CronTrigger(config.getSyncCron())
        );
        scheduledTasks.put(config.getTableName(), scheduledTask);
    }

    private int getMaxOrder() {
        return syncConfig.getTables().stream()
                .mapToInt(SyncConfig.TableSyncConfig::getOrder)
                .max()
                .orElse(0);
    }

    private void executeSync(SyncConfig.TableSyncConfig config) {
        try {
            if (Boolean.TRUE.equals(syncConfig.getParallelSync())) {
            log.info("开始执行表 {} 的并行同步任务", config.getTableName());
        } else {
            log.info("开始执行表 {} 的串行同步任务 [执行顺序: {}]",
                     config.getTableName(), config.getOrder());
        }

        syncService.syncTable(config);

        if (Boolean.TRUE.equals(syncConfig.getParallelSync())) {
            log.info("表 {} 的并行同步任务执行完成", config.getTableName());
        } else {
            log.info("表 {} 的串行同步任务执行完成 [执行顺序: {}]",
                     config.getTableName(), config.getOrder());
        }

        } catch (Exception e) {
            log.error("表 {} 的同步任务执行失败: {}", 
                config.getTableName(), e.getMessage(), e);
        }
    }

    private void cancelExistingTask(String tableName) {
        ScheduledFuture<?> existingTask = scheduledTasks.get(tableName);
        if (existingTask != null) {
            existingTask.cancel(false);
            scheduledTasks.remove(tableName);
            log.info("已取消表 {} 的现有同步任务", tableName);
        }
    }

    // 添加手动重置执行顺序的方法
    public void resetOrder() {
        synchronized (orderLock) {
            currentOrder = 1;
            log.info("手动重置执行顺序为1");
        }
    }

    // 修改停止任务方法，同时重置顺序
    public void stopAllTasks() {
        scheduledTasks.forEach((tableName, task) -> {
            task.cancel(false);
            log.info("已停止表 {} 的同步任务", tableName);
        });
        scheduledTasks.clear();
        resetOrder();
    }
}
