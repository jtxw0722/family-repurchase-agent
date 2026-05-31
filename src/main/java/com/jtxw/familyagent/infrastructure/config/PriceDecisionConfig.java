package com.jtxw.familyagent.infrastructure.config;


import com.jtxw.familyagent.domain.policy.PriceDecisionThresholds;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author: jtxw
 * @Date: 2026/05/31/9:30
 * @Description: 价格判断配置装配
 */

@Configuration
@EnableConfigurationProperties(PriceDecisionProperties.class)
public class PriceDecisionConfig {

    @Bean
    public PriceDecisionThresholds priceDecisionThresholds(PriceDecisionProperties properties) {
        return properties.toThresholds();
    }
}
