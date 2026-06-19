package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.ParseOrderImageCommand;
import com.jtxw.familyagent.domain.model.ParseOrderImageResult;
import com.jtxw.familyagent.domain.policy.ProductRule;
import com.jtxw.familyagent.domain.policy.ProductRuleMatcher;
import com.jtxw.familyagent.domain.policy.ProductRuleProvider;
import com.jtxw.familyagent.domain.policy.UnitFamily;
import com.jtxw.familyagent.infrastructure.ocr.DisabledOcrClient;
import com.jtxw.familyagent.infrastructure.ocr.OcrClient;
import com.jtxw.familyagent.infrastructure.ocr.OcrResult;
import com.jtxw.familyagent.infrastructure.ocr.OrderImageTextParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/19 23:20:00
 * @Description: 订单截图解析应用服务测试，覆盖路径安全、默认禁用 OCR 和只读候选返回边界
 */
class ParseOrderImageApplicationServiceTest {
    /** JUnit 创建的隔离图片允许目录。 */
    @TempDir
    private Path allowedDirectory;

    @Test
    void shouldRejectBlankImagePath() {
        ParseOrderImageApplicationService service = createService(fakeOcrClient());

        assertThatThrownBy(() -> service.parse(command(" ", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("imagePath 不能为空");
    }

    @Test
    void shouldRejectImageOutsideAllowedDirectory() throws IOException {
        Path outsideFile = Files.createTempFile("outside-order-", ".png");
        ParseOrderImageApplicationService service = createService(fakeOcrClient());

        assertThatThrownBy(() -> service.parse(command(outsideFile.toString(), true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片路径不在允许目录内");
        Files.deleteIfExists(outsideFile);
    }

    @Test
    void shouldRejectMissingImage() {
        Path missingFile = allowedDirectory.resolve("missing.png");
        ParseOrderImageApplicationService service = createService(fakeOcrClient());

        assertThatThrownBy(() -> service.parse(command(missingFile.toString(), true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片文件不存在");
    }

    @Test
    void shouldRejectUnsupportedImageSuffix() throws IOException {
        Path textFile = Files.writeString(allowedDirectory.resolve("order.txt"), "synthetic");
        ParseOrderImageApplicationService service = createService(fakeOcrClient());

        assertThatThrownBy(() -> service.parse(command(textFile.toString(), true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不支持的图片格式：txt");
    }

    @Test
    void shouldReturnClearErrorWhenOcrDisabled() throws IOException {
        Path imageFile = Files.createFile(allowedDirectory.resolve("order.png"));
        ParseOrderImageApplicationService service = createService(new DisabledOcrClient());

        assertThatThrownBy(() -> service.parse(command(imageFile.toString(), true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OCR 未启用，请配置本地 OCR 或视觉模型后再使用 parse_order_image。");
    }

    @Test
    void shouldReturnCandidateFromFakeOcrText() throws IOException {
        Path imageFile = Files.createFile(allowedDirectory.resolve("order.jpg"));
        ParseOrderImageApplicationService service = createService(fakeOcrClient());

        ParseOrderImageResult result = service.parse(command(imageFile.toString(), true));

        assertThat(result.success()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.candidates().get(0).productName()).isEqualTo("合成测试纸巾");
        assertThat(result.candidates().get(0).normalization()).isNotNull();
        assertThat(result.warnings()).contains("parse_order_image 仅返回候选样本，不会写入 purchase_records；请确认后调用现有 record_purchase，或后续规划中的 record_sample。");
    }

    @Test
    void shouldRemainReadOnlyWhenDryRunIsFalse() throws IOException {
        Path imageFile = Files.createFile(allowedDirectory.resolve("order.webp"));
        ParseOrderImageApplicationService service = createService(fakeOcrClient());

        ParseOrderImageResult result = service.parse(command(imageFile.toString(), false));

        assertThat(result.warnings()).contains("parse_order_image 第一阶段只返回候选样本，不写入 purchase_records。");
        assertThat(ParseOrderImageApplicationService.class.getDeclaredFields())
                .noneMatch(field -> field.getType().getSimpleName().equals("PurchaseRecordRepository"));
    }

    @Test
    void shouldUseDefaultOwnerWhenCommandOwnerMissing() throws IOException {
        String owner = parseOwner(null, "jtxw");

        assertThat(owner).isEqualTo("jtxw");
    }

    @Test
    void shouldUseDefaultOwnerWhenCommandOwnerBlank() throws IOException {
        String owner = parseOwner(" ", "jtxw");

        assertThat(owner).isEqualTo("jtxw");
    }

    @Test
    void shouldPreferCommandOwnerWhenProvided() throws IOException {
        String owner = parseOwner("otherOwner", "jtxw");

        assertThat(owner).isEqualTo("otherOwner");
    }

    @Test
    void shouldAttachNormalizationPreviewToCandidates() throws IOException {
        Path imageFile = Files.createFile(allowedDirectory.resolve("normalization-matched.jpg"));
        ProductRule coffeeRule = new ProductRule(
                "instant_coffee", "即饮咖啡", 100,
                List.of("咖啡"), List.of("咖啡机"), "L", UnitFamily.VOLUME);
        ParseOrderImageApplicationService service = createService(
                imagePath -> new OcrResult("商品名称：合成即饮咖啡\n规格：268ml\n实付：12.50元",
                        0.9D, List.of()),
                previewService(coffeeRule));

        ParseOrderImageResult result = service.parse(command(imageFile.toString(), true));

        assertThat(result.candidates().get(0).normalization()).isNotNull();
        assertThat(result.candidates().get(0).normalization().matched()).isTrue();
        assertThat(result.candidates().get(0).normalization().normalizedName()).isEqualTo("即饮咖啡");
        assertThat(result.candidates().get(0).normalization().targetUnit()).isEqualTo("L");
    }

    @Test
    void shouldReturnUnmatchedNormalizationPreviewWhenNoRuleMatched() throws IOException {
        Path imageFile = Files.createFile(allowedDirectory.resolve("normalization-unmatched.jpg"));
        ParseOrderImageApplicationService service = createService(fakeOcrClient());

        ParseOrderImageResult result = service.parse(command(imageFile.toString(), true));

        assertThat(result.candidates().get(0).normalization().matched()).isFalse();
        assertThat(result.candidates().get(0).normalization().warning()).contains("未命中归一化规则");
        assertThat(result.success()).isTrue();
    }

    private ParseOrderImageApplicationService createService(OcrClient ocrClient) {
        return new ParseOrderImageApplicationService(ocrClient, new OrderImageTextParser(), List.of(allowedDirectory));
    }

    private ParseOrderImageApplicationService createService(OcrClient ocrClient,
                                                             NormalizationPreviewService previewService) {
        return new ParseOrderImageApplicationService(ocrClient, new OrderImageTextParser(), previewService,
                List.of(allowedDirectory), "jtxw");
    }

    private NormalizationPreviewService previewService(ProductRule... rules) {
        ProductRuleProvider ruleProvider = () -> List.of(rules);
        return new NormalizationPreviewService(new ProductRuleMatcher(ruleProvider), ruleProvider);
    }

    private String parseOwner(String commandOwner, String defaultOwner) throws IOException {
        Path imageFile = Files.createFile(allowedDirectory.resolve("owner-" + System.nanoTime() + ".jpg"));
        ParseOrderImageApplicationService service = new ParseOrderImageApplicationService(
                fakeOcrClient(), new OrderImageTextParser(), List.of(allowedDirectory), defaultOwner);
        ParseOrderImageCommand command = new ParseOrderImageCommand(
                imageFile.toString(), commandOwner, "tmall", null, null, true);

        return service.parse(command).candidates().get(0).owner();
    }

    private OcrClient fakeOcrClient() {
        return imagePath -> new OcrResult("商品名称：合成测试纸巾\n规格：100抽\n实付：12.50元", 0.9D, List.of());
    }

    private ParseOrderImageCommand command(String imagePath, Boolean dryRun) {
        return new ParseOrderImageCommand(imagePath, "jtxw", "pdd", null, null, dryRun);
    }

}
