package com.jtxw.familyagent.infrastructure.persistence;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/14:22
 * @Description: 数据库初始化组件，负责创建运行目录并执行 SQLite 表结构脚本。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        initialize();
    }

    public synchronized void initialize() {
        try {
            ensureRuntimeDirectories();

            ClassPathResource resource = new ClassPathResource("db/schema.sql");
            String sql = resource.getContentAsString(StandardCharsets.UTF_8);
            for (String statement : sql.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isBlank()) {
                    jdbcTemplate.execute(trimmed);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("初始化数据库失败", e);
        }
    }

    private void ensureRuntimeDirectories() throws IOException {
        Files.createDirectories(Path.of("data"));
        Files.createDirectories(Path.of("data", "inbox"));
        Files.createDirectories(Path.of("reports"));
    }
}
