package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.ImportFileCommand;
import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.ImportResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import com.jtxw.familyagent.domain.policy.*;
import com.jtxw.familyagent.infrastructure.config.NormalizationProperties;
import com.jtxw.familyagent.infrastructure.importer.CsvPurchaseImporter;
import com.jtxw.familyagent.infrastructure.importer.ExcelPurchaseImporter;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.ImportBatchRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 00:27:12
 * @Description: 导入应用服务，编排 CSV 解析、记录标准化、入库和异常复核创建。
 */
@Service
public class ImportApplicationService {
    /**
     * CSV 文件后缀
     */
    private static final String CSV_FILE_SUFFIX = ".csv";
    /**
     * Excel 文件后缀
     */
    private static final String EXCEL_FILE_SUFFIX = ".xlsx";
    /**
     * 统计决策：纳入价格基准
     */
    private static final String DECISION_INCLUDE = "include";
    /**
     * 统计决策：排除出价格基准
     */
    private static final String DECISION_EXCLUDE = "exclude";
    /**
     * 去重状态：唯一记录
     */
    private static final String DEDUPE_STATUS_UNIQUE = "unique";
    /**
     * 去重状态：疑似重复记录
     */
    private static final String DEDUPE_STATUS_DUPLICATE = "duplicate";
    /**
     * 复核原因码：疑似重复订单
     */
    private static final String REVIEW_REASON_DUPLICATE_ORDER = "DUPLICATE_ORDER";
    /**
     * 复核原因码：商品名称归一化置信度较低
     */
    private static final String REVIEW_REASON_PRODUCT_NAME_NORMALIZATION = "PRODUCT_NAME_NORMALIZATION_REVIEW";
    /**
     * 复核原因码：规格数量解析置信度较低
     */
    private static final String REVIEW_REASON_QUANTITY_UNIT_PARSE = "QUANTITY_UNIT_PARSE_REVIEW";
    /**
     * 复核原因码：实付金额为 0
     */
    private static final String REVIEW_REASON_ZERO_PAYMENT = "ZERO_PAYMENT";
    /**
     * 负向别名规则标识，表示人工确认过的误判样本
     */
    /**
     * 旧归一化兜底规则标识，LLM Advisor 的唯一候选来源
     */
    private static final String NORMALIZATION_RULE_LEGACY_FALLBACK = "legacy_fallback";
    /**
     * 旧模式兜底复核模式：导入时立即创建复核项
     */
    private static final String FALLBACK_REVIEW_MODE_IMMEDIATE_REVIEW = "immediate_review";
    /**
     * 0 元可信支付关键词：赠品
     */
    private static final String ZERO_PAYMENT_GIFT_KEYWORD = "赠品";
    /**
     * 0 元可信支付关键词：试用
     */
    private static final String ZERO_PAYMENT_TRIAL_KEYWORD = "试用";

    private final DatabaseInitializer databaseInitializer;
    private final CsvPurchaseImporter csvPurchaseImporter;
    private final ExcelPurchaseImporter excelPurchaseImporter;
    private final OrderAmountAllocationPolicy orderAmountAllocationPolicy;
    private final DuplicateDetectionPolicy duplicateDetectionPolicy;
    private final PaymentAdjustmentPolicy paymentAdjustmentPolicy;
    private final OwnerNormalizer ownerNormalizer;
    private final PurchaseTimeNormalizer purchaseTimeNormalizer;
    private final LearningProductNameNormalizer productNameNormalizer;
    private final QuantityUnitParser quantityUnitParser;
    private final UnitPriceCalculator unitPriceCalculator;
    private final ImportBatchRepository importBatchRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final ReviewItemRepository reviewItemRepository;
    private final NormalizationProperties normalizationProperties;

    @Autowired
    public ImportApplicationService(DatabaseInitializer databaseInitializer,
                                    CsvPurchaseImporter csvPurchaseImporter,
                                    ExcelPurchaseImporter excelPurchaseImporter,
                                    OrderAmountAllocationPolicy orderAmountAllocationPolicy,
                                    DuplicateDetectionPolicy duplicateDetectionPolicy,
                                    PaymentAdjustmentPolicy paymentAdjustmentPolicy,
                                    OwnerNormalizer ownerNormalizer,
                                    PurchaseTimeNormalizer purchaseTimeNormalizer,
                                    LearningProductNameNormalizer productNameNormalizer,
                                    QuantityUnitParser quantityUnitParser,
                                    UnitPriceCalculator unitPriceCalculator,
                                    ImportBatchRepository importBatchRepository,
                                    PurchaseRecordRepository purchaseRecordRepository,
                                    ReviewItemRepository reviewItemRepository,
                                    NormalizationProperties normalizationProperties) {
        this.databaseInitializer = databaseInitializer;
        this.csvPurchaseImporter = csvPurchaseImporter;
        this.excelPurchaseImporter = excelPurchaseImporter;
        this.orderAmountAllocationPolicy = orderAmountAllocationPolicy;
        this.duplicateDetectionPolicy = duplicateDetectionPolicy;
        this.paymentAdjustmentPolicy = paymentAdjustmentPolicy;
        this.ownerNormalizer = ownerNormalizer;
        this.purchaseTimeNormalizer = purchaseTimeNormalizer;
        this.productNameNormalizer = productNameNormalizer;
        this.quantityUnitParser = quantityUnitParser;
        this.unitPriceCalculator = unitPriceCalculator;
        this.importBatchRepository = importBatchRepository;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.reviewItemRepository = reviewItemRepository;
        this.normalizationProperties = normalizationProperties;
    }

    public ImportApplicationService(DatabaseInitializer databaseInitializer,
                                    CsvPurchaseImporter csvPurchaseImporter,
                                    ExcelPurchaseImporter excelPurchaseImporter,
                                    DuplicateDetectionPolicy duplicateDetectionPolicy,
                                    PaymentAdjustmentPolicy paymentAdjustmentPolicy,
                                    OwnerNormalizer ownerNormalizer,
                                    PurchaseTimeNormalizer purchaseTimeNormalizer,
                                    LearningProductNameNormalizer productNameNormalizer,
                                    QuantityUnitParser quantityUnitParser,
                                    UnitPriceCalculator unitPriceCalculator,
                                    ImportBatchRepository importBatchRepository,
                                    PurchaseRecordRepository purchaseRecordRepository,
                                    ReviewItemRepository reviewItemRepository) {
        this(databaseInitializer, csvPurchaseImporter, excelPurchaseImporter, new OrderAmountAllocationPolicy(),
                duplicateDetectionPolicy, paymentAdjustmentPolicy, ownerNormalizer, purchaseTimeNormalizer, productNameNormalizer,
                quantityUnitParser, unitPriceCalculator, importBatchRepository, purchaseRecordRepository,
                reviewItemRepository, legacyReviewProperties());
    }

    public ImportApplicationService(DatabaseInitializer databaseInitializer,
                                    CsvPurchaseImporter csvPurchaseImporter,
                                    ExcelPurchaseImporter excelPurchaseImporter,
                                    DuplicateDetectionPolicy duplicateDetectionPolicy,
                                    PaymentAdjustmentPolicy paymentAdjustmentPolicy,
                                    OwnerNormalizer ownerNormalizer,
                                    PurchaseTimeNormalizer purchaseTimeNormalizer,
                                    LearningProductNameNormalizer productNameNormalizer,
                                    QuantityUnitParser quantityUnitParser,
                                    UnitPriceCalculator unitPriceCalculator,
                                    ImportBatchRepository importBatchRepository,
                                    PurchaseRecordRepository purchaseRecordRepository,
                                    ReviewItemRepository reviewItemRepository,
                                    NormalizationProperties normalizationProperties) {
        this(databaseInitializer, csvPurchaseImporter, excelPurchaseImporter, new OrderAmountAllocationPolicy(),
                duplicateDetectionPolicy, paymentAdjustmentPolicy, ownerNormalizer, purchaseTimeNormalizer,
                productNameNormalizer, quantityUnitParser, unitPriceCalculator, importBatchRepository,
                purchaseRecordRepository, reviewItemRepository, normalizationProperties);
    }

    /**
     * 导入订单文件并写入本地数据库。
     *
     * <p>导入流程包括：读取订单文件、商品名称归一化、单位价格计算、重复订单检测、
     * 订单明细入库，并将金额折算、实付金额为 0 或疑似重复的记录加入待复核列表。</p>
     *
     * @param file 本地订单文件路径
     * @return 导入结果
     */
    public ImportResult importCsv(Path file) {
        return importFile(new ImportFileCommand(file, null));
    }

    /**
     * 导入订单文件并写入本地数据库。
     *
     * @param file          本地订单文件路径
     * @param ownerOverride 导入时指定的订单归属人，为空时由导入器从文件字段或文件名识别
     * @return 导入结果
     */
    public ImportResult importCsv(Path file, String ownerOverride) {
        return importFile(new ImportFileCommand(file, ownerOverride));
    }

    /**
     * 导入订单文件并写入本地数据库。
     *
     * @param file          本地订单文件路径
     * @param ownerOverride 导入时指定的订单归属人，为空时由导入器从文件字段或文件名识别
     * @return 导入结果
     */
    public ImportResult importFile(Path file, String ownerOverride) {
        return importFile(new ImportFileCommand(file, ownerOverride));
    }

    /**
     * 导入订单文件并写入本地数据库。
     *
     * <p>导入流程包括：读取订单文件、商品名称归一化、单位价格计算、重复订单检测、
     * 订单明细入库，并将金额折算、实付金额为 0 或疑似重复的记录加入待复核列表。</p>
     *
     * @param command 文件导入命令
     * @return 导入结果
     */
    public ImportResult importFile(ImportFileCommand command) {
        Path file = command.file();
        String ownerOverride = command.ownerOverride();
        databaseInitializer.initialize();
        List<RawPurchaseRecord> rawRecords = orderAmountAllocationPolicy.allocate(importRawRecords(file, ownerOverride));
        long batchId = importBatchRepository.create(file.toString());

        ImportWorkflowResult workflowResult = importRecords(file, batchId, rawRecords);
        importBatchRepository.complete(batchId, rawRecords.size(), workflowResult.importedCount(), workflowResult.reviewCount());

        return buildImportResult(batchId, rawRecords.size(), workflowResult);
    }

    /**
     * 根据文件后缀解析订单文件，返回原始购买记录列表。
     *
     * @param file          订单文件路径
     * @param ownerOverride 订单归属人覆盖值
     * @return 原始购买记录列表
     * @throws IllegalArgumentException 文件类型不支持时抛出
     */
    private List<RawPurchaseRecord> importRawRecords(Path file, String ownerOverride) {
        String filename = file.getFileName().toString().toLowerCase();
        if (filename.endsWith(CSV_FILE_SUFFIX)) {
            return csvPurchaseImporter.importFile(file, ownerOverride);
        }
        if (filename.endsWith(EXCEL_FILE_SUFFIX)) {
            return excelPurchaseImporter.importFile(file, ownerOverride);
        }
        throw new IllegalArgumentException("不支持的订单文件类型，仅支持 .csv 和 .xlsx：" + file);
    }

    /**
     * 遍历原始记录列表，逐条执行标准化、入库和复核创建，汇总导入统计。
     *
     * @param file       源文件路径，用于记录 sourceFile
     * @param batchId    当前导入批次 ID
     * @param rawRecords 原始购买记录列表
     * @return 导入工作流统计结果，包含 importedCount、reviewCount、duplicateCount
     */
    private ImportWorkflowResult importRecords(Path file, long batchId, List<RawPurchaseRecord> rawRecords) {
        // 当前批次内的订单指纹，用于识别同一个文件中的重复行
        Set<String> currentBatchFingerprints = new HashSet<>();
        // 待复核统计
        int reviewCount = 0;
        // 成功入库统计
        int importedCount = 0;
        // 重复统计
        int duplicateCount = 0;
        for (RawPurchaseRecord raw : rawRecords) {
            ImportedRecordResult recordResult = importSingleRecord(file, batchId, raw, currentBatchFingerprints);
            importedCount += recordResult.importedCount();
            reviewCount += recordResult.reviewCount();
            duplicateCount += recordResult.duplicateCount();
        }
        return new ImportWorkflowResult(importedCount, reviewCount, duplicateCount);
    }

    /**
     * 处理单条原始记录：标准化、归一化、入库并创建复核项。
     *
     * @param file                     源文件路径
     * @param batchId                  导入批次 ID
     * @param raw                      原始购买记录
     * @param currentBatchFingerprints 当前批次内已导入记录的指纹集合，用于批次内去重
     * @return 单条记录的导入统计结果
     */
    private ImportedRecordResult importSingleRecord(Path file,
                                                    long batchId,
                                                    RawPurchaseRecord raw,
                                                    Set<String> currentBatchFingerprints) {
        String normalizedOrderTime = purchaseTimeNormalizer.normalizeImportedOrderTime(raw.orderTime());
        String normalizedOwner = ownerNormalizer.normalize(raw.owner());
        // 后端导入链路内完成商品归一化，MCP Server 只负责把 import_file 请求转发到这里。
        ProductNameNormalizationResult nameResult = productNameNormalizer.normalize(raw.productName(), raw.sku());
        String normalizedName = nameResult.normalizedName();
        // 负向别名是人工确认过的误判样本：自动排除，但不再生成归一化复核噪音。
        PaymentAdjustmentPolicy.PaymentAdjustmentResult amountResult = paymentAdjustmentPolicy.adjust(raw);
        Double totalAmount = amountResult.totalAmount();

        // 规格解析使用归一化后的标准品类和目标单位；高置信结果会覆盖原始"件"等粗粒度单位。
        QuantityUnitParseResult quantityResult = quantityUnitParser.parse(
                nameResult.normalizedName(),
                nameResult.targetUnit(),
                raw.productName(),
                raw.sku(),
                totalAmount,
                raw.quantity(),
                raw.unit()
        );
        Double resolvedQuantity = quantityResult.quantity() == null ? raw.quantity() : quantityResult.quantity();
        String resolvedUnit = quantityResult.unit() == null || quantityResult.unit().isBlank()
                ? raw.unit()
                : quantityResult.unit();
        Double unitPrice = quantityResult.unitPrice();
        if (unitPrice == null && totalAmount != null && resolvedQuantity != null && resolvedQuantity > 0) {
            unitPrice = unitPriceCalculator.calculate(totalAmount, resolvedQuantity);
        }

        // 低置信规格仍保留购买记录，便于人工复核，但默认排除出正式价格基准线
        boolean normalizationReviewRequired = nameResult.needReview() || quantityResult.needReview();

        // 候选记录先按正常订单构造，用于生成去重指纹和查询历史重复
        PurchaseRecord candidate = buildDuplicateCandidate(
                batchId, normalizedOrderTime, raw, normalizedOwner, normalizedName,
                resolvedQuantity, resolvedUnit, totalAmount, amountResult, unitPrice, file);

        // 同时检查历史数据库和当前批次，避免重复导入影响价格统计和价格报告
        boolean duplicate = duplicateDetectionPolicy.isDuplicate(candidate, currentBatchFingerprints,
                purchaseRecordRepository.existsDuplicate(candidate));

        // 疑似重复订单默认排除统计，后续可通过人工复核恢复纳入
        PurchaseRecord record = buildPurchaseRecord(
                batchId, normalizedOrderTime, raw, normalizedOwner, normalizedName,
                resolvedQuantity, resolvedUnit, totalAmount, amountResult, unitPrice,
                duplicate, normalizationReviewRequired, nameResult, file);

        long recordId = purchaseRecordRepository.save(record);

        ReviewCreationResult reviewResult = createReviewItems(recordId, duplicate, nameResult,
                quantityResult, amountResult, raw, totalAmount);

        return new ImportedRecordResult(1, reviewResult.reviewCount(), reviewResult.duplicateCount());
    }

    /**
     * 构造用于重复检测的候选购买记录。
     *
     * <p>候选记录按正常订单构造，decision 为 include，dedupe 状态为 unique，
     * 用于生成去重指纹和查询历史重复。</p>
     *
     * @param batchId             导入批次 ID
     * @param normalizedOrderTime 标准化后的订单时间
     * @param raw                 原始购买记录
     * @param normalizedOwner     标准化后的订单归属人
     * @param normalizedName      归一化后的商品名称
     * @param resolvedQuantity    解析后的数量
     * @param resolvedUnit        解析后的单位
     * @param totalAmount         实付总金额
     * @param amountResult        金额调整结果
     * @param unitPrice           单价
     * @param file                源文件路径
     * @return 用于重复检测的候选购买记录
     */
    private PurchaseRecord buildDuplicateCandidate(long batchId,
                                                   String normalizedOrderTime,
                                                   RawPurchaseRecord raw,
                                                   String normalizedOwner,
                                                   String normalizedName,
                                                   Double resolvedQuantity,
                                                   String resolvedUnit,
                                                   Double totalAmount,
                                                   PaymentAdjustmentPolicy.PaymentAdjustmentResult amountResult,
                                                   Double unitPrice,
                                                   Path file) {
        return new PurchaseRecord(
                null, batchId, normalizedOrderTime, raw.platform(), normalizedOwner, raw.productName(), normalizedName,
                raw.sku(), raw.category(), raw.subCategory(), resolvedQuantity, resolvedUnit, totalAmount,
                raw.productAmount(), raw.paidAmount(), raw.shippingFee(), amountResult.amountSource(),
                unitPrice, raw.currency(), DECISION_INCLUDE, false, DEDUPE_STATUS_UNIQUE, file.toString(), ClockUtils.nowText()
        );
    }

    /**
     * 构造最终入库的购买记录。
     *
     * <p>根据 duplicate、normalizationReviewRequired、amountReviewRequired 和规格复核决定 decision；
     * 根据 duplicate 决定 dedupe 状态。疑似重复、低置信归一化、金额不可信和规格待确认的记录默认排除出价格基准。</p>
     *
     * @param batchId                     导入批次 ID
     * @param normalizedOrderTime         标准化后的订单时间
     * @param raw                         原始购买记录
     * @param normalizedOwner             标准化后的订单归属人
     * @param normalizedName              归一化后的商品名称
     * @param resolvedQuantity            解析后的数量
     * @param resolvedUnit                解析后的单位
     * @param totalAmount                 实付总金额
     * @param amountResult                金额调整结果
     * @param unitPrice                   单价
     * @param duplicate                   是否疑似重复
     * @param normalizationReviewRequired 是否需要归一化复核
     * @param nameResult                  商品名称归一化结果
     * @param file                        源文件路径
     * @return 最终入库的购买记录
     */
    private PurchaseRecord buildPurchaseRecord(long batchId,
                                               String normalizedOrderTime,
                                               RawPurchaseRecord raw,
                                               String normalizedOwner,
                                               String normalizedName,
                                               Double resolvedQuantity,
                                               String resolvedUnit,
                                               Double totalAmount,
                                               PaymentAdjustmentPolicy.PaymentAdjustmentResult amountResult,
                                               Double unitPrice,
                                               boolean duplicate,
                                               boolean normalizationReviewRequired,
                                               ProductNameNormalizationResult nameResult,
                                               Path file) {
        String decision = duplicate || normalizationReviewRequired || amountResult.reviewRequired()
                || raw.specReviewRequired()
                ? DECISION_EXCLUDE : DECISION_INCLUDE;
        String dedupeStatus = duplicate ? DEDUPE_STATUS_DUPLICATE : DEDUPE_STATUS_UNIQUE;
        return new PurchaseRecord(
                null, batchId, normalizedOrderTime, raw.platform(), normalizedOwner, raw.productName(), normalizedName,
                raw.sku(), raw.category(), raw.subCategory(), resolvedQuantity, resolvedUnit, totalAmount,
                raw.productAmount(), raw.paidAmount(), raw.shippingFee(), amountResult.amountSource(),
                unitPrice, raw.currency(), decision, duplicate, dedupeStatus, file.toString(), null, null, null,
                nameResult.matchedRule(), ClockUtils.nowText()
        );
    }

    /**
     * 为单条记录创建复核项，按原顺序依次检查：重复订单、商品名称归一化、规格数量解析、
     * 金额折算、规格审查、实付金额为 0。
     *
     * <p>amount review、raw spec review 和 zero payment 之间保持 else-if 关系，
     * 同一条记录最多只创建其中一项。</p>
     *
     * @param recordId       入库后的记录 ID
     * @param duplicate      是否疑似重复
     * @param nameResult     商品名称归一化结果
     * @param quantityResult 规格数量解析结果
     * @param amountResult   金额调整结果
     * @param raw            原始购买记录
     * @param totalAmount    实付总金额
     * @return 本条记录新增的复核统计结果
     */
    private ReviewCreationResult createReviewItems(long recordId,
                                                   boolean duplicate,
                                                   ProductNameNormalizationResult nameResult,
                                                   QuantityUnitParseResult quantityResult,
                                                   PaymentAdjustmentPolicy.PaymentAdjustmentResult amountResult,
                                                   RawPurchaseRecord raw,
                                                   Double totalAmount) {
        int reviewCount = 0;
        int duplicateCount = 0;

        if (duplicate) {
            // 重复记录需要人工确认，避免误伤真实的二次购买
            reviewItemRepository.create(recordId, REVIEW_REASON_DUPLICATE_ORDER,
                    "疑似重复订单，已默认排除统计；如确认不是重复购买，可人工复核为 include。");
            reviewCount++;
            duplicateCount++;
        }
        if (shouldCreateProductNameReview(nameResult)) {
            reviewItemRepository.create(recordId, REVIEW_REASON_PRODUCT_NAME_NORMALIZATION,
                    "商品归一化置信度较低，matchedRule=" + nameResult.matchedRule()
                            + "，confidence=" + nameResult.confidence());
            reviewCount++;
        }
        if (quantityResult.needReview()) {
            // 规格数量不明确时创建复核项，并因 decision=exclude 不进入 purchase_records 的正式统计查询。
            reviewItemRepository.create(recordId, REVIEW_REASON_QUANTITY_UNIT_PARSE,
                    "规格数量解析置信度较低，evidence=" + quantityResult.parseEvidence()
                            + "，confidence=" + quantityResult.confidence());
            reviewCount++;
        }
        if (amountResult.reviewRequired()) {
            // 金额折算会影响单价和价格统计，必须保留人工确认入口
            reviewItemRepository.create(recordId, amountResult.reviewReasonCode(), amountResult.reviewReasonMessage());
            reviewCount++;
        } else if (shouldCreateRawSpecReview(raw, quantityResult)) {
            reviewItemRepository.create(recordId, raw.specReviewReasonCode(), raw.specReviewReasonMessage());
            reviewCount++;
        } else if (totalAmount != null && totalAmount == 0D) {
            if (!isTrustedZeroPayment(raw)) {
                // 无明确赠品或试用标识的 0 元记录仍需人工确认，避免售后补发、组合支付等场景误入库
                reviewItemRepository.create(recordId, REVIEW_REASON_ZERO_PAYMENT, "实付金额为 0，需确认是否赠品、试用、售后补发或购物金抵扣。");
                reviewCount++;
            }
        }

        return new ReviewCreationResult(reviewCount, duplicateCount);
    }

    /**
     * 判断是否需要额外创建导入阶段规格复核项。
     *
     * <p>普通包装数量无法解析时，应用层数量解析已经会创建 QUANTITY_UNIT_PARSE_REVIEW；
     * 这里避免重复创建同类复核项。真正的次卡/分次发货复核仍由 raw spec review 创建。</p>
     *
     * @param raw            原始购买记录
     * @param quantityResult 应用层数量解析结果
     * @return 是否需要创建导入阶段规格复核项
     */
    private boolean shouldCreateRawSpecReview(RawPurchaseRecord raw, QuantityUnitParseResult quantityResult) {
        if (!raw.specReviewRequired()) {
            return false;
        }
        return !(quantityResult.needReview()
                && REVIEW_REASON_QUANTITY_UNIT_PARSE.equals(raw.specReviewReasonCode()));
    }

    /**
     * 根据导入工作流结果构造最终的导入结果。
     *
     * @param batchId        导入批次 ID
     * @param rawCount       原始记录总数
     * @param workflowResult 工作流统计结果
     * @return 导入结果
     */
    private ImportResult buildImportResult(long batchId, int rawCount, ImportWorkflowResult workflowResult) {
        return new ImportResult(batchId, rawCount, workflowResult.importedCount(), workflowResult.reviewCount(),
                "导入完成：共 " + rawCount + " 条，成功 " + workflowResult.importedCount() + " 条，疑似重复 "
                        + workflowResult.duplicateCount() + " 条，待复核 " + workflowResult.reviewCount() + " 条。");
    }

    /**
     * 判断商品名称归一化结果是否需要创建复核项。
     *
     * <p>legacy_fallback 是 LLM Advisor 的唯一候选来源，新模式下默认跳过复核，
     * 仅在配置了 immediate_review 时才创建。</p>
     *
     * @param nameResult 商品名称归一化结果
     * @return 如果需要创建复核项则返回 true
     */
    private boolean shouldCreateProductNameReview(ProductNameNormalizationResult nameResult) {
        if (!nameResult.needReview()) {
            return false;
        }
        // legacy_fallback 是 LLM Advisor 的唯一候选来源，新模式下先静默 exclude，避免导入时制造大量逐条复核噪音。
        if (NORMALIZATION_RULE_LEGACY_FALLBACK.equals(nameResult.matchedRule())) {
            return normalizationProperties.immediateFallbackReview();
        }
        return true;
    }

    /**
     * 判断原始记录是否属于可信的 0 元支付（赠品或试用）。
     *
     * @param raw 原始购买记录
     * @return 如果商品名称、SKU、品类中包含赠品或试用关键词则返回 true
     */
    private boolean isTrustedZeroPayment(RawPurchaseRecord raw) {
        String text = String.join(" ",
                safeText(raw.productName()),
                safeText(raw.sku()),
                safeText(raw.category()),
                safeText(raw.subCategory()));
        return text.contains(ZERO_PAYMENT_GIFT_KEYWORD) || text.contains(ZERO_PAYMENT_TRIAL_KEYWORD);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private static NormalizationProperties legacyReviewProperties() {
        NormalizationProperties properties = new NormalizationProperties();
        properties.setFallbackReviewMode(FALLBACK_REVIEW_MODE_IMMEDIATE_REVIEW);
        return properties;
    }

    /**
     * 单条记录的导入统计结果。
     *
     * @param importedCount  入库记录数，正常为 1
     * @param reviewCount    本条记录新增的复核项数
     * @param duplicateCount 本条记录新增的重复计数
     */
    private record ImportedRecordResult(int importedCount, int reviewCount, int duplicateCount) {
    }

    /**
     * 批量导入工作流的汇总统计结果。
     *
     * @param importedCount  成功入库总数
     * @param reviewCount    待复核总数
     * @param duplicateCount 疑似重复总数
     */
    private record ImportWorkflowResult(int importedCount, int reviewCount, int duplicateCount) {
    }

    /**
     * 单条记录复核项创建的统计结果。
     *
     * @param reviewCount    本条记录新增的复核项数
     * @param duplicateCount 本条记录新增的重复计数
     */
    private record ReviewCreationResult(int reviewCount, int duplicateCount) {
    }
}
