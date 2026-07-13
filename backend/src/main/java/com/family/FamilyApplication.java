package com.family;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 家庭管理系统启动类
 */
@SpringBootApplication
@MapperScan("com.family.mapper")
@EnableScheduling
public class FamilyApplication {

    public static void main(String[] args) {
        SpringApplication.run(FamilyApplication.class, args);
    }

}
