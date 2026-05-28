package com.jtxw.familyagent.mcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportFilePathValidatorTest {
    @Test
    void shouldAcceptCsvFileUnderAllowedDirectory() throws Exception {
        Path tempDir = createWorkspaceTempDir();
        Path importsDir = Files.createDirectory(tempDir.resolve("imports"));
        Path importFile = Files.createFile(importsDir.resolve("orders.csv"));
        ImportFilePathValidator validator = new ImportFilePathValidator(List.of(importsDir.toAbsolutePath().normalize()));

        ImportFilePathValidator.SafeImportFile result = validator.validate(importFile.toString());

        assertThat(result.resolvedPath()).isEqualTo(importFile.toAbsolutePath().normalize());
    }

    @Test
    void shouldRejectFileOutsideAllowedDirectory() throws Exception {
        Path tempDir = createWorkspaceTempDir();
        Path importsDir = Files.createDirectory(tempDir.resolve("imports"));
        Path privateDir = Files.createDirectory(tempDir.resolve("private"));
        Path privateFile = Files.createFile(privateDir.resolve("orders.csv"));
        ImportFilePathValidator validator = new ImportFilePathValidator(List.of(importsDir.toAbsolutePath().normalize()));

        assertThatThrownBy(() -> validator.validate(privateFile.toString()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside allowed import directories");
    }

    @Test
    void shouldRejectUnsupportedFileType() throws Exception {
        Path tempDir = createWorkspaceTempDir();
        Path importsDir = Files.createDirectory(tempDir.resolve("imports"));
        Path importFile = Files.createFile(importsDir.resolve("orders.txt"));
        ImportFilePathValidator validator = new ImportFilePathValidator(List.of(importsDir.toAbsolutePath().normalize()));

        assertThatThrownBy(() -> validator.validate(importFile.toString()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("CSV or Excel");
    }

    @Test
    void shouldRejectSymlinkEscapingAllowedDirectory() throws Exception {
        Path tempDir = createWorkspaceTempDir();
        Path importsDir = Files.createDirectory(tempDir.resolve("imports"));
        Path privateDir = Files.createDirectory(tempDir.resolve("private"));
        Path privateFile = Files.createFile(privateDir.resolve("orders.csv"));
        Path symlink = importsDir.resolve("link.csv");

        try {
            Files.createSymbolicLink(symlink, privateFile);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            return;
        }

        ImportFilePathValidator validator = new ImportFilePathValidator(List.of(importsDir.toAbsolutePath().normalize()));

        assertThatThrownBy(() -> validator.validate(symlink.toString()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside allowed import directories");
    }

    private Path createWorkspaceTempDir() throws IOException {
        Path targetDir = Path.of("target");
        Files.createDirectories(targetDir);
        return Files.createTempDirectory(targetDir, "import-validator-");
    }
}
