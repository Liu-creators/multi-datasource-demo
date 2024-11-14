package com.example.oraclepostgressync.service;

import com.example.oraclepostgressync.config.SyncConfig;

public interface SyncService {
    void syncTable(SyncConfig.TableSyncConfig config);
}
