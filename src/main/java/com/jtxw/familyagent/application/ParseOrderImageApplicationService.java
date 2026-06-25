package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.ParseOrderImageCommand;
import com.jtxw.familyagent.domain.model.ParseOrderImageResult;
import com.jtxw.familyagent.domain.model.ParsedPurchaseCandidate;
import com.jtxw.familyagent.infrastructure.ocr.OcrClient;
import com.jtxw.familyagent.infrastructure.ocr.OcrResult;
import com.jtxw.familyagent.infrastructure.ocr.OrderImageBase64Decoder;
import com.jtxw.familyagent.infrastructure.ocr.OrderImageInput;
import com.jtxw.familyagent.infrastructure.ocr.OrderImageRecognitionService;
import com.jtxw.familyagent.infrastructure.ocr.OrderImageTextParser;
import com.jtxw.familyagent.infrastructure.ocr.ParseOrderImageModelProperties;
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
    private static final String READ_ONLY_WARNING = "parse_order_image 仅返回候选样本，不会写入 purchase_records，也不会自动调用 record_purchase；请确认后调用 record_purchase 正式入库。";
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
     * 订单截图识别编排服务，负责视觉模型优先和本地 OCR 兜底。
     */
    private final OrderImageRecognitionService recognitionService;
    /**
     * Base64 图片解析组件，负责 data URL 或纯 Base64 的 MIME 与大小校验。
     */
    private final OrderImageBase64Decoder orderImageBase64Decoder;
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
     * @param recognitionService          订单截图识别编排服务
     * @param orderImageTextParser        OCR 原始文本规则解析器
     * @param normalizationPreviewService OCR 候选归一化预览服务
     * @param allowedDirectoriesProperty  允许读取的目录，多个目录使用逗号或分号分隔
     * @param defaultOwner                请求未提供 owner 时使用的默认订单归属人，允许为空
     */
    @Autowired
    public ParseOrderImageApplicationService(
            OrderImageRecognitionService recognitionService,
            OrderImageBase64Decoder orderImageBase64Decoder,
            OrderImageTextParser orderImageTextParser,
            NormalizationPreviewService normalizationPreviewService,
            @Value("${family-agent.import.allowed-dirs:${FAMILY_AGENT_IMPORT_ALLOWED_DIRS:./data/inbox}}")
            String allowedDirectoriesProperty,
            @Value("${family-agent.default-owner:jtxw}") String defaultOwner) {
        this(recognitionService, orderImageBase64Decoder, orderImageTextParser, normalizationPreviewService,
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
        this(OrderImageRecognitionService.localOnly(ocrClient), defaultBase64Decoder(), orderImageTextParser,
                NormalizationPreviewService.withoutRules(),
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
        this(OrderImageRecognitionService.localOnly(ocrClient), defaultBase64Decoder(), orderImageTextParser,
                NormalizationPreviewService.withoutRules(),
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
        this(OrderImageRecognitionService.localOnly(ocrClient), defaultBase64Decoder(), orderImageTextParser,
                normalizationPreviewService, allowedDirectories, defaultOwner);
    }

    /**
     * 创建使用指定识别编排、归一化预览和安全目录的解析服务，供 Spring 注入和单元测试使用。
     *
     * @param recognitionService          订单截图识别编排服务
     * @param orderImageTextParser        OCR 文本规则解析器
     * @param normalizationPreviewService OCR 候选归一化预览服务
     * @param allowedDirectories          允许读取图片的目录，不允许为空
     * @param defaultOwner                请求未提供 owner 时使用的默认订单归属人，允许为空
     */
    public ParseOrderImageApplicationService(OrderImageRecognitionService recognitionService,
                                             OrderImageTextParser orderImageTextParser,
                                             NormalizationPreviewService normalizationPreviewService,
                                             List<Path> allowedDirectories,
                                             String defaultOwner) {
        this(recognitionService, defaultBase64Decoder(), orderImageTextParser, normalizationPreviewService,
                allowedDirectories, defaultOwner);
    }

    /**
     * 创建使用指定识别编排、Base64 解析器、归一化预览和安全目录的解析服务。
     *
     * @param recognitionService          订单截图识别编排服务
     * @param orderImageBase64Decoder     Base64 图片解析组件
     * @param orderImageTextParser        OCR 文本规则解析器
     * @param normalizationPreviewService OCR 候选归一化预览服务
     * @param allowedDirectories          允许读取图片的目录，不允许为空
     * @param defaultOwner                请求未提供 owner 时使用的默认订单归属人，允许为空
     */
    public ParseOrderImageApplicationService(OrderImageRecognitionService recognitionService,
                                             OrderImageBase64Decoder orderImageBase64Decoder,
                                             OrderImageTextParser orderImageTextParser,
                                             NormalizationPreviewService normalizationPreviewService,
                                             List<Path> allowedDirectories,
                                             String defaultOwner) {
        this.recognitionService = recognitionService;
        this.orderImageBase64Decoder = orderImageBase64Decoder;
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
     * @param command 订单截图解析命令，imageBase64 和 imagePath 至少一个不为空
     * @return OCR 原文、结构化候选样本和只读边界警告
     * @throws IllegalArgumentException 图片路径不安全、文件不存在或格式不支持时抛出
     * @throws IllegalStateException    OCR 未启用时抛出
     */
    public ParseOrderImageResult parse(ParseOrderImageCommand command) {
        if (command == null || (!hasText(command.imageBase64()) && !hasText(command.imagePath()))) {
            throw new IllegalArgumentException("imageBase64 和 imagePath 至少需要提供一个");
        }
        OcrResult ocrResult;
        String resultImagePath;
        if (hasText(command.imageBase64())) {
            OrderImageInput imageInput = orderImageBase64Decoder.decode(
                    command.imageBase64(), command.imageFileName(), command.imageMimeType());
            ocrResult = recognitionService.recognize(imageInput);
            resultImagePath = imageInput.displayName();
        } else {
            Path imagePath = validateImagePath(command.imagePath());
            ocrResult = recognitionService.recognize(imagePath);
            resultImagePath = command.imagePath();
        }
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
        return new ParseOrderImageResult(true, resultImagePath, parseMode, candidates.size(),
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

    /**
     * 解析路径的真实物理路径，文件不存在时返回原始路径，用于防止符号链接越界。
     *
     * @param path 待解析的路径
     * @return 文件存在时返回真实路径，否则返回原始路径
     */
    private Path resolveRealPathIfExists(Path path) {
        try {
            return Files.exists(path) ? path.toRealPath() : path;
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取图片允许目录：" + path, exception);
        }
    }

    /**
     * 提取文件路径的小写后缀名，不含点号，无后缀时返回空字符串。
     *
     * @param imagePath 文件路径
     * @return 小写后缀名，例如 "png"、"jpg"；无后缀时返回空字符串
     */
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

    /**
     * 创建使用默认模型大小限制的 Base64 解析器，供历史测试构造器保持兼容。
     *
     * @return 默认 Base64 图片解析组件
     */
    private static OrderImageBase64Decoder defaultBase64Decoder() {
        return new OrderImageBase64Decoder(new ParseOrderImageModelProperties());
    }
}
