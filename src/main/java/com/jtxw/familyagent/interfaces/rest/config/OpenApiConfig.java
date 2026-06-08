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

    /**
     * 创建 OpenAPI 文档配置，用于 Swagger UI 展示 REST Tool API。
     *
     * @return OpenAPI 文档描述对象
     */
    @Bean(name = "familyRepurchaseOpenAPI")
    public OpenAPI familyRepurchaseOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Family Repurchase Agent API")
                        .version(resolveVersion())
                        .description("本地优先家庭复购品价格决策 Agent 的 REST Tool API。"));
    }

    /**
     * 读取 Jar Manifest 中的 Implementation-Version。
     *
     * <p>本地 IDE 运行时通常读取不到版本号，此时返回 dev；打包运行时由 Maven 写入真实版本。</p>
     *
     * @return 应用版本号
     */
    private String resolveVersion() {
        Package currentPackage = OpenApiConfig.class.getPackage();
        if (currentPackage == null) {
            return "dev";
        }
        String version = currentPackage.getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }
}
