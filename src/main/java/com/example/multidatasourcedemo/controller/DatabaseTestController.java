package com.example.multidatasourcedemo.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class DatabaseTestController {

    @Autowired
    @Qualifier("oracleJdbcTemplate")
    private JdbcTemplate oracleJdbcTemplate;

    @Autowired
    @Qualifier("postgresJdbcTemplate")
    private JdbcTemplate postgresJdbcTemplate;

    @Autowired
    @Qualifier("mysqlJdbcTemplate")
    private JdbcTemplate mysqlJdbcTemplate;

    @GetMapping("/test-oracle")
    public String testOracle() {
        try {
            String result = oracleJdbcTemplate.queryForObject("SELECT SYSDATE FROM DUAL", String.class);
            log.info("Oracle连接成功! 当前时间: {}", result);
            return "Oracle连接成功: " + result;
        } catch (Exception e) {
            log.error("Oracle连接失败!", e);
            return "Oracle连接失败: " + e.getMessage();
        }
    }

    @GetMapping("/test-postgres")
    public String testPostgres() {
        try {
            String result = postgresJdbcTemplate.queryForObject("SELECT NOW()", String.class);
            log.info("PostgreSQL连接成功! 当前时间: {}", result);
            return "PostgreSQL连接成功: " + result;
        } catch (Exception e) {
            log.error("PostgreSQL连接失败!", e);
            return "PostgreSQL连接失败: " + e.getMessage();
        }
    }

    @GetMapping("/test-mysql")
    public String testMySQL() {
        try {
            String result = mysqlJdbcTemplate.queryForObject("SELECT NOW()", String.class);
            log.info("MySQL连接成功! 当前时间: {}", result);
            return "MySQL连接成功: " + result;
        } catch (Exception e) {
            log.error("MySQL连接失败!", e);
            return "MySQL连接失败: " + e.getMessage();
        }
    }


}