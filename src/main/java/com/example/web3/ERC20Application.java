package com.example.web3;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @author jiangyuxuan
 * @date 2024/11/04
 */
@SpringBootApplication
@MapperScan("com.example.web3.mapper")
@EnableConfigurationProperties
@EnableScheduling
public class ERC20Application {

    public static void main(String[] args) {
        SpringApplication.run(ERC20Application.class, args);
    }
}

