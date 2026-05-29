package com.jtxw.familyagent.mcp;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * @Author: jtxw
 * @Date: 2026/05/28/00:16
 * @Description: MCP Server 运行配置，负责读取后端地址和导入白名单目录
 */
public class AppConfig {
    static final String DEFAULT_API_BASE_URL = "http://localhost:8080";
    static final List<String> DEFAULT_IMPORT_DIRS = List.of("examples", "data/imports", "imports");

    private final URI apiBaseUri;
    private final List<Path> importAllowedDirs;

    public AppConfig(URI apiBaseUri, List<Path> importAllowedDirs) {
        this.apiBaseUri = apiBaseUri;
        this.importAllowedDirs = importAllowedDirs;
    }

    /**
     * 从环境变量读取 MCP Server 运行配置
     *
     * @param env 当前进程环境变量
     * @return MCP Server 运行配置
     */
    public static AppConfig fromEnv(Map<String, String> env) {
        String apiBaseUrl = env.getOrDefault("FAMILY_AGENT_API_BASE_URL", DEFAULT_API_BASE_URL);
        String allowedDirs = env.get("FAMILY_AGENT_IMPORT_ALLOWED_DIRS");
        List<String> importDirs = allowedDirs == null || allowedDirs.isBlank()
                ? DEFAULT_IMPORT_DIRS
                : Arrays.stream(allowedDirs.split(File.pathSeparator))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();

        return new AppConfig(URI.create(stripTrailingSlash(apiBaseUrl)), importDirs.stream()
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .toList());
    }

    public URI apiBaseUri() {
        return apiBaseUri;
    }

    public List<Path> importAllowedDirs() {
        return importAllowedDirs;
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null || value.isBlank() ? DEFAULT_API_BASE_URL : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
