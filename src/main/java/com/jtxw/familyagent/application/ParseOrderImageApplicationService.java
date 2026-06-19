package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.ParseOrderImageCommand;
import com.jtxw.familyagent.domain.model.ParseOrderImageResult;
import com.jtxw.familyagent.domain.model.ParsedPurchaseCandidate;
import com.jtxw.familyagent.infrastructure.ocr.OcrClient;
import com.jtxw.familyagent.infrastructure.ocr.OcrResult;
import com.jtxw.familyagent.infrastructure.ocr.OrderImageTextParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * @Author: jtxw
 * @Date: 2026/06/19 20:38:00
 * @Description: 订单截图解析应用服务，负责本地图片安全校验、OCR 调用和候选样本解析编排，不访问购买记录仓储
 */
@Service
public class ParseOrderImageApplicationService {
    /**
     * 默认订单截图解析模式。
     */
    private static final String DEFAULT_PARSE_MODE = "order_screenshot";
    /**
     * 第一阶段不会写入购买记录的固定边界说明。
     */
    private static final String READ_ONLY_WARNING =
            "parse_order_image 仅返回候选样本，不会写入 purchase_records；请确认后调用现有 record_purchase，或后续规划中的 record_sample。";
    /**
     * dryRun=false 时的额外提示。
     */
    private static final String FALSE_DRY_RUN_WARNING =
            "parse_order_image 第一阶段只返回候选样本，不写入 purchase_records。";
    /**
     * 第一阶段允许读取的图片后缀。
     */
    private static final Set<String> SUPPORTED_IMAGE_SUFFIXES = Set.of("png", "jpg", "jpeg", "webp");

    /**
     * OCR 客户端，默认注入禁用实现。
     */
    private final OcrClient ocrClient;
    /**
     * OCR 原始文本规则解析器。
     */
    private final OrderImageTextParser orderImageTextParser;
    /**
     * OCR 候选归一化预览服务。
     */
    private final NormalizationPreviewService normalizationPreviewService;
    /**
     * 图片可读取的规范绝对目录列表。
     */
    private final List<Path> allowedDirectories;
    /**
     * 请求未提供订单归属人时使用的默认值，配置为空时允许候选 owner 为空。
     */
    private final String defaultOwner;

    /**
     * 创建订单截图解析应用服务。
     *
     * @param ocrClient                   OCR 客户端，默认使用禁用实现
     * @param orderImageTextParser        OCR 原始文本规则解析器
     * @param normalizationPreviewService OCR 候选归一化预览服务
     * @param allowedDirectoriesProperty  允许读取的目录，多个目录使用逗号或分号分隔
     * @param defaultOwner                请求未提供 owner 时使用的默认订单归属人，允许为空
     */
    @Autowired
    public ParseOrderImageApplicationService(
            OcrClient ocrClient,
            OrderImageTextParser orderImageTextParser,
            NormalizationPreviewService normalizationPreviewService,
            @Value("${family-agent.import.allowed-dirs:${FAMILY_AGENT_IMPORT_ALLOWED_DIRS:./data/inbox}}")
            String allowedDirectoriesProperty,
            @Value("${family-agent.default-owner:jtxw}") String defaultOwner) {
        this(ocrClient, orderImageTextParser, normalizationPreviewService,
                parseAllowedDirectories(allowedDirectoriesProperty), defaultOwner);
    }

    /**
     * 创建可指定安全目录的订单截图解析服务，供单元测试和显式本地配置使用。
     *
     * @param ocrClient            OCR 客户端
     * @param orderImageTextParser OCR 文本解析器
     * @param allowedDirectories   允许读取图片的目录，不允许为空
     */
    public ParseOrderImageApplicationService(OcrClient ocrClient,
                                             OrderImageTextParser orderImageTextParser,
                                             List<Path> allowedDirectories) {
        this(ocrClient, orderImageTextParser, NormalizationPreviewService.withoutRules(),
                allowedDirectories, "jtxw");
    }

    /**
     * 创建可指定安全目录和默认订单归属人的解析服务，供单元测试和显式本地配置使用。
     *
     * @param ocrClient            OCR 客户端
     * @param orderImageTextParser OCR 文本解析器
     * @param allowedDirectories   允许读取图片的目录，不允许为空
     * @param defaultOwner         请求未提供 owner 时使用的默认值，允许为空
     */
    public ParseOrderImageApplicationService(OcrClient ocrClient,
                                             OrderImageTextParser orderImageTextParser,
                                             List<Path> allowedDirectories,
                                             String defaultOwner) {
        this(ocrClient, orderImageTextParser, NormalizationPreviewService.withoutRules(),
                allowedDirectories, defaultOwner);
    }

    /**
     * 创建可指定归一化预览、安全目录和默认归属人的解析服务，供单元测试使用。
     *
     * @param ocrClient                   OCR 客户端
     * @param orderImageTextParser        OCR 文本解析器
     * @param normalizationPreviewService OCR 候选归一化预览服务
     * @param allowedDirectories          允许读取图片的目录，不允许为空
     * @param defaultOwner                请求未提供 owner 时使用的默认值，允许为空
     */
    public ParseOrderImageApplicationService(OcrClient ocrClient,
                                             OrderImageTextParser orderImageTextParser,
                                             NormalizationPreviewService normalizationPreviewService,
                                             List<Path> allowedDirectories,
                                             String defaultOwner) {
        this.ocrClient = ocrClient;
        this.orderImageTextParser = orderImageTextParser;
        this.normalizationPreviewService = normalizationPreviewService;
        if (allowedDirectories == null || allowedDirectories.isEmpty()) {
            throw new IllegalArgumentException("图片允许目录不能为空");
        }
        this.allowedDirectories = allowedDirectories.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        this.defaultOwner = hasText(defaultOwner) ? defaultOwner.trim() : null;
    }

    /**
     * 安全读取本地订单图片并解析候选样本。
     *
     * <p>该用例只返回候选样本，不依赖 PurchaseRecordRepository，也不会调用 record_purchase。
     * 即使 dryRun=false，仍不会产生任何数据库副作用。</p>
     *
     * @param command 订单截图解析命令，imagePath 不允许为空
     * @return OCR 原文、结构化候选样本和只读边界警告
     * @throws IllegalArgumentException 图片路径不安全、文件不存在或格式不支持时抛出
     * @throws IllegalStateException    OCR 未启用时抛出
     */
    public ParseOrderImageResult parse(ParseOrderImageCommand command) {
        if (command == null || command.imagePath() == null || command.imagePath().isBlank()) {
            throw new IllegalArgumentException("imagePath 不能为空");
        }
        Path imagePath = validateImagePath(command.imagePath());
        OcrResult ocrResult = ocrClient.recognize(imagePath);
        String rawText = ocrResult == null || ocrResult.rawText() == null ? "" : ocrResult.rawText();
        String effectiveOwner = hasText(command.owner()) ? command.owner().trim() : defaultOwner;
        List<ParsedPurchaseCandidate> parsedCandidates = orderImageTextParser.parse(
                rawText, effectiveOwner, command.platform(), command.purchaseDate());
        List<ParsedPurchaseCandidate> candidates = normalizationPreviewService.enrich(parsedCandidates);

        List<String> warnings = new ArrayList<>();
        warnings.add(READ_ONLY_WARNING);
        if (Boolean.FALSE.equals(command.dryRun())) {
            warnings.add(FALSE_DRY_RUN_WARNING);
        }
        if (ocrResult != null && ocrResult.warnings() != null) {
            warnings.addAll(ocrResult.warnings());
        }
        String parseMode = command.parseMode() == null || command.parseMode().isBlank()
                ? DEFAULT_PARSE_MODE : command.parseMode().trim();
        return new ParseOrderImageResult(true, command.imagePath(), parseMode, candidates.size(),
                candidates, List.copyOf(warnings), rawText);
    }

    /**
     * 校验路径范围、文件类型和图片后缀，并解析真实路径防止符号链接越界。
     *
     * @param requestedPath 调用方提供的图片路径
     * @return 已校验的真实图片路径
     */
    private Path validateImagePath(String requestedPath) {
        Path normalizedPath = Path.of(requestedPath).toAbsolutePath().normalize();
        if (!isWithinAllowedDirectory(normalizedPath)) {
            throw new IllegalArgumentException("图片路径不在允许目录内：" + requestedPath);
        }
        if (!Files.exists(normalizedPath)) {
            throw new IllegalArgumentException("图片文件不存在：" + requestedPath);
        }
        if (!Files.isRegularFile(normalizedPath)) {
            throw new IllegalArgumentException("图片路径不是普通文件：" + requestedPath);
        }
        String suffix = fileSuffix(normalizedPath);
        if (!SUPPORTED_IMAGE_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException("不支持的图片格式：" + suffix);
        }
        try {
            Path realPath = normalizedPath.toRealPath();
            if (!isWithinAllowedDirectory(realPath)) {
                throw new IllegalArgumentException("图片路径不在允许目录内：" + requestedPath);
            }
            return realPath;
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取图片文件：" + requestedPath, exception);
        }
    }

    /**
     * 判断图片路径是否位于任一配置允许目录内。
     *
     * @param imagePath 规范绝对路径或真实路径
     * @return 位于允许目录内时返回 true
     */
    private boolean isWithinAllowedDirectory(Path imagePath) {
        for (Path allowedDirectory : allowedDirectories) {
            Path effectiveAllowedDirectory = resolveRealPathIfExists(allowedDirectory);
            if (imagePath.startsWith(effectiveAllowedDirectory)) {
                return true;
            }
        }
        return false;
    }

    private Path resolveRealPathIfExists(Path path) {
        try {
            return Files.exists(path) ? path.toRealPath() : path;
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取图片允许目录：" + path, exception);
        }
    }

    private String fileSuffix(Path imagePath) {
        String fileName = imagePath.getFileName().toString();
        int separatorIndex = fileName.lastIndexOf('.');
        return separatorIndex < 0 ? "" : fileName.substring(separatorIndex + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 判断配置值或请求文本是否包含非空白字符。
     *
     * @param text 待判断文本，允许为空
     * @return 文本非空且包含非空白字符时返回 true
     */
    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    /**
     * 解析逗号或分号分隔的安全目录配置。
     *
     * @param propertyValue 安全目录配置值，允许为空
     * @return 至少包含 data/inbox 默认目录的路径列表
     */
    private static List<Path> parseAllowedDirectories(String propertyValue) {
        if (propertyValue == null || propertyValue.isBlank()) {
            return List.of(Path.of("./data/inbox"));
        }
        return Arrays.stream(propertyValue.split("[,;]"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .toList();
    }
}
