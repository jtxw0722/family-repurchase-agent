package com.jtxw.familyagent.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * @Author: jtxw
 * @Date: 2026/05/28/00:17
 * @Description: import_file 文件路径安全校验，限制 MCP Host 可导入的本地文件范围
 */
public class ImportFilePathValidator {
    private final List<Path> allowedDirs;

    public ImportFilePathValidator(List<Path> allowedDirs) {
        this.allowedDirs = allowedDirs;
    }

    /**
     * 校验导入文件是否位于允许目录内，并确认文件类型为 CSV 或 Excel
     *
     * @param filePath MCP 工具入参中的文件路径
     * @return 原始路径和解析后的绝对路径
     */
    public SafeImportFile validate(String filePath) {
        requireText(filePath, "filePath");
        String originalPath = filePath.trim();
        Path normalizedPath = Path.of(originalPath).toAbsolutePath().normalize();

        if (!Files.exists(normalizedPath)) {
            throw new ToolExecutionException("filePath does not exist: " + filePath);
        }

        Path realPath;
        try {
            realPath = normalizedPath.toRealPath();
        } catch (IOException e) {
            throw new ToolExecutionException("failed to resolve filePath: " + filePath, e);
        }

        boolean allowed = allowedDirs.stream()
                .map(this::toRealAllowedDir)
                .anyMatch(realPath::startsWith);
        if (!allowed) {
            throw new ToolExecutionException("filePath is outside allowed import directories. Allowed directories: " + allowedDirs);
        }
        if (!Files.isRegularFile(realPath)) {
            throw new ToolExecutionException("filePath must be a file: " + filePath);
        }

        String lower = realPath.toString().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".csv") && !lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw new ToolExecutionException("filePath must be a CSV or Excel file");
        }
        return new SafeImportFile(originalPath, realPath);
    }

    private Path toRealAllowedDir(Path allowedDir) {
        try {
            if (Files.exists(allowedDir)) {
                return allowedDir.toRealPath();
            }
            return allowedDir.toAbsolutePath().normalize();
        } catch (IOException e) {
            return allowedDir.toAbsolutePath().normalize();
        }
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ToolExecutionException(name + " must be a non-empty string");
        }
    }

    public record SafeImportFile(String originalPath, Path resolvedPath) {
    }
}
