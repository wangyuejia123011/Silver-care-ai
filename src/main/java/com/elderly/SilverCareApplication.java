package com.elderly;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.elderly.mapper")
@EnableScheduling // 开启定时任务（日报生成）
public class SilverCareApplication {
    public static void main(String[] args) {
        SpringApplication.run(SilverCareApplication.class, args);
    }
}

