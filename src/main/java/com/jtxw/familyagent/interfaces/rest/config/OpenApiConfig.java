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
    public OpenAPI familyRepurchaseOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Family Repurchase Agent API")
                        .version("0.4.0")
                        .description("本地优先家庭复购品价格决策 Agent的 REST Tool API。"));
    }
}
