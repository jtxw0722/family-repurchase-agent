package com.jtxw.familyagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * @Author: jtxw
 * @Date: 2026/05/13/00:36
 * @Description: Spring Boot 应用启动入口。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FamilyRepurchaseAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(FamilyRepurchaseAgentApplication.class, args);
    }
}
