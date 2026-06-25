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
import com.jtxw.familyagent.infrastructure.ocr.OrderImageModelException;
import com.jtxw.familyagent.infrastructure.ocr.OrderImageRecognitionService;
import com.jtxw.familyagent.infrastructure.ocr.OrderImageTextParser;
import com.jtxw.familyagent.infrastructure.ocr.ParseOrderImageModelProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/24 09:21:54
 * @Description: 订单截图解析应用服务测试，覆盖路径安全、Base64 优先、默认禁用 OCR 和只读候选返回边界
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
                .hasMessage("imageBase64 和 imagePath 至少需要提供一个");
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
        assertThat(result.warnings()).contains(
                "parse_order_image 仅返回候选样本，不会写入 purchase_records，也不会自动调用 record_purchase；请确认后调用 record_purchase 正式入库。");
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

    @Test
    void shouldParseModelRawTextWithoutCallingLocalOcr() throws IOException {
        Path imageFile = Files.createFile(allowedDirectory.resolve("model-success.jpg"));
        AtomicBoolean localCalled = new AtomicBoolean();
        ParseOrderImageModelProperties properties = modelProperties(true);
        OrderImageRecognitionService recognitionService = new OrderImageRecognitionService(
                path -> {
                    localCalled.set(true);
                    return fakeOcrClient().recognize(path);
                },
                path -> new OcrResult("商品名称：模型合成纸巾\n规格：100抽\n实付：9.90元",
                        null, List.of()),
                properties);
        ParseOrderImageApplicationService service = new ParseOrderImageApplicationService(
                recognitionService, new OrderImageTextParser(), NormalizationPreviewService.withoutRules(),
                List.of(allowedDirectory), "jtxw");

        ParseOrderImageResult result = service.parse(command(imageFile.toString(), true));

        assertThat(result.success()).isTrue();
        assertThat(result.rawText()).contains("模型合成纸巾");
        assertThat(result.candidates().get(0).productName()).isEqualTo("模型合成纸巾");
        assertThat(localCalled).isFalse();
    }

    @Test
    void shouldParseLocalRawTextAndReturnWarningAfterModelFallback() throws IOException {
        Path imageFile = Files.createFile(allowedDirectory.resolve("model-fallback.png"));
        ParseOrderImageModelProperties properties = modelProperties(true);
        OrderImageRecognitionService recognitionService = new OrderImageRecognitionService(
                fakeOcrClient(),
                path -> { throw new OrderImageModelException("合成连接超时"); },
                properties);
        ParseOrderImageApplicationService service = new ParseOrderImageApplicationService(
                recognitionService, new OrderImageTextParser(), NormalizationPreviewService.withoutRules(),
                List.of(allowedDirectory), "jtxw");

        ParseOrderImageResult result = service.parse(command(imageFile.toString(), true));

        assertThat(result.success()).isTrue();
        assertThat(result.rawText()).contains("合成测试纸巾");
        assertThat(result.warnings())
                .anyMatch(warning -> warning.contains("视觉模型识别失败，已回退本地 OCR"));
    }

    @Test
    void shouldPreferImageBase64WhenImagePathAlsoProvided() throws IOException {
        Path outsideFile = Files.createTempFile("outside-order-", ".png");
        ParseOrderImageApplicationService service = createService(fakeOcrClient());
        String imageBase64 = dataUrl(new byte[]{1, 2, 3});
        ParseOrderImageCommand command = new ParseOrderImageCommand(
                outsideFile.toString(), imageBase64, "order.jpg", null,
                "jtxw", "pdd", null, null, true);

        ParseOrderImageResult result = service.parse(command);

        assertThat(result.success()).isTrue();
        assertThat(result.imagePath()).isEqualTo("order.jpg");
        assertThat(result.imagePath()).doesNotContain(imageBase64);
        assertThat(result.candidates()).hasSize(1);
        Files.deleteIfExists(outsideFile);
    }

    @Test
    void shouldRejectInvalidImageBase64WithSafeError() {
        ParseOrderImageApplicationService service = createService(fakeOcrClient());
        String invalidBase64 = "not-a-real-base64";
        ParseOrderImageCommand command = new ParseOrderImageCommand(
                null, invalidBase64, "order.jpg", "image/jpeg",
                "jtxw", "pdd", null, null, true);

        assertThatThrownBy(() -> service.parse(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("imageBase64 不是合法的 Base64 图片内容")
                .hasMessageNotContaining(invalidBase64);
    }

    @Test
    void shouldReturnCandidateFromImageBase64Mode() {
        ParseOrderImageApplicationService service = createService(fakeOcrClient());
        String imageBase64 = dataUrl(new byte[]{1, 2, 3});
        ParseOrderImageCommand command = new ParseOrderImageCommand(
                null, imageBase64, null, null,
                "jtxw", "pdd", null, null, true);

        ParseOrderImageResult result = service.parse(command);

        assertThat(result.success()).isTrue();
        assertThat(result.imagePath()).isEqualTo("base64-image");
        assertThat(result.imagePath()).doesNotContain(imageBase64);
        assertThat(result.candidates()).hasSize(1);
    }

    /**
     * 使用默认归一化预览和测试安全目录创建被测解析服务。
     */
    private ParseOrderImageApplicationService createService(OcrClient ocrClient) {
        return new ParseOrderImageApplicationService(ocrClient, new OrderImageTextParser(), List.of(allowedDirectory));
    }

    /**
     * 使用指定归一化预览服务和测试安全目录创建被测解析服务。
     */
    private ParseOrderImageApplicationService createService(OcrClient ocrClient,
                                                             NormalizationPreviewService previewService) {
        return new ParseOrderImageApplicationService(ocrClient, new OrderImageTextParser(), previewService,
                List.of(allowedDirectory), "jtxw");
    }

    /**
     * 使用指定归一化规则创建归一化预览服务。
     */
    private NormalizationPreviewService previewService(ProductRule... rules) {
        ProductRuleProvider ruleProvider = () -> List.of(rules);
        return new NormalizationPreviewService(new ProductRuleMatcher(ruleProvider), ruleProvider);
    }

    /**
     * 通过实际解析流程验证 owner 解析逻辑，返回候选样本中的 owner 值。
     */
    private String parseOwner(String commandOwner, String defaultOwner) throws IOException {
        Path imageFile = Files.createFile(allowedDirectory.resolve("owner-" + System.nanoTime() + ".jpg"));
        ParseOrderImageApplicationService service = new ParseOrderImageApplicationService(
                fakeOcrClient(), new OrderImageTextParser(), List.of(allowedDirectory), defaultOwner);
        ParseOrderImageCommand command = new ParseOrderImageCommand(
                imageFile.toString(), null, null, null, commandOwner, "tmall", null, null, true);

        return service.parse(command).candidates().get(0).owner();
    }

    /**
     * 创建返回合成 OCR 文本的 fake 本地 OCR 客户端。
     */
    private OcrClient fakeOcrClient() {
        return imagePath -> new OcrResult("商品名称：合成测试纸巾\n规格：100抽\n实付：12.50元", 0.9D, List.of());
    }

    /**
     * 创建启用视觉模型的合成配置，指定是否允许本地 OCR 兜底。
     */
    private ParseOrderImageModelProperties modelProperties(boolean fallbackToLocalOcr) {
        ParseOrderImageModelProperties properties = new ParseOrderImageModelProperties();
        properties.setEnabled(true);
        properties.setFallbackToLocalOcr(fallbackToLocalOcr);
        return properties;
    }

    /**
     * 创建指定图片路径和 dryRun 标志的合成解析命令。
     */
    private ParseOrderImageCommand command(String imagePath, Boolean dryRun) {
        return new ParseOrderImageCommand(imagePath, null, null, null, "jtxw", "pdd", null, null, dryRun);
    }

    /**
     * 构造不包含真实订单截图的合成图片 data URL。
     */
    private String dataUrl(byte[] imageBytes) {
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(imageBytes);
    }

}
