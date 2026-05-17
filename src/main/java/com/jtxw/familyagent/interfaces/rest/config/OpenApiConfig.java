package com.jtxw.familyagent.interfaces.rest.config;


import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author: jtxw
 * @Date: 2026/05/17/9:11
 * @Description: OpenAPI 文档配置
 */

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI familyConsumptionOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Family Consumption Agent API")
                        .version("0.2.0")
                        .description("本地优先家庭消费分析项目的 REST Tool API。"));
    }
}