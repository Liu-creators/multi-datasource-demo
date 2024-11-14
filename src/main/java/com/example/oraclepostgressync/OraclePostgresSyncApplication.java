package com.example.oraclepostgressync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OraclePostgresSyncApplication {

    public static void main(String[] args){
        SpringApplication.run(OraclePostgresSyncApplication.class , args);
    }
}
