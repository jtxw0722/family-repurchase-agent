package com.jtxw.familyagent.infrastructure.importer;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author: jtxw
 * @Date: 2026/06/11 18:32:32
 * @Description: 中文淘宝订单导出行上下文处理器，负责识别主行、续行、空白行和交易关闭订单续行。
 */
@Component
public class ChineseOrderExportRowNormalizer {
    /**
     * 中文订单模板字段：订单提交时间。
     */
    private static final String HEADER_ORDER_TIME = "订单提交时间";
    /**
     * 中文订单模板字段：订单编号。
     */
    private static final String HEADER_ORDER_ID = "订单编号";
    /**
     * 中文订单模板字段：订单号。
     */
    private static final String HEADER_ORDER_NO = "订单号";
    /**
     * 中文订单模板字段：主订单编号。
     */
    private static final String HEADER_MAIN_ORDER_ID = "主订单编号";
    /**
     * 中文订单模板字段：子订单编号。
     */
    private static final String HEADER_SUB_ORDER_ID = "子订单编号";
    /**
     * 中文订单模板字段：订单状态。
     */
    private static final String HEADER_ORDER_STATUS = "订单状态";
    /**
     * 中文订单模板字段：店铺名称。
     */
    private static final String HEADER_SHOP_NAME = "店铺名称";
    /**
     * 中文订单模板字段：商品名称。
     */
    private static final String HEADER_PRODUCT_NAME = "商品名称";
    /**
     * 中文订单模板字段：商品链接。
     */
    private static final String HEADER_PRODUCT_LINK = "商品链接";
    /**
     * 中文订单模板字段：型号款式。
     */
    private static final String HEADER_MODEL_STYLE = "型号款式";
    /**
     * 中文订单模板字段：商品数量。
     */
    private static final String HEADER_QUANTITY = "商品数量";
    /**
     * 中文订单模板字段：商品金额。
     */
    private static final String HEADER_PRODUCT_AMOUNT = "商品金额";
    /**
     * 中文订单模板字段：实付金额。
     */
    private static final String HEADER_PAID_AMOUNT = "实付金额";
    /**
     * 中文订单模板字段：运费。
     */
    private static final String HEADER_SHIPPING_FEE = "运费";
    /**
     * 交易状态：交易成功。
     */
    private static final String TRADE_STATUS_SUCCESS = "交易成功";
    /**
     * 需要从主行继承到续行的订单上下文字段。
     */
    private static final List<String> ORDER_CONTEXT_HEADERS = List.of(
            HEADER_ORDER_ID,
            HEADER_ORDER_NO,
            HEADER_MAIN_ORDER_ID,
            HEADER_SUB_ORDER_ID,
            HEADER_ORDER_TIME,
            HEADER_ORDER_STATUS,
            HEADER_SHOP_NAME,
            HEADER_PRODUCT_LINK,
            HEADER_PAID_AMOUNT,
            HEADER_SHIPPING_FEE
    );

    /**
     * 将中文淘宝订单导出原始行归一为可逐行映射的订单商品行。
     *
     * <p>主行会刷新订单上下文；续行继承上一笔交易成功订单的上下文，并保留自己的商品名称、
     * 型号款式、商品数量和商品金额。交易关闭主行及其续行会被跳过。</p>
     *
     * @param rows 从 CSV 或 Excel 读取的原始字段行
     * @return 补齐订单上下文后的有效商品行
     */
    public List<Map<String, String>> normalize(List<Map<String, String>> rows) {
        List<Map<String, String>> normalizedRows = new ArrayList<>();
        OrderContext currentContext = null;
        for (Map<String, String> row : rows) {
            if (isBlankRow(row) || !hasProductLine(row)) {
                continue;
            }
            if (isMainOrderRow(row)) {
                if (!TRADE_STATUS_SUCCESS.equals(value(row, HEADER_ORDER_STATUS))) {
                    // 交易关闭主行会清空上下文，避免其后的空状态续行被误挂到上一笔成功订单。
                    currentContext = null;
                    continue;
                }
                currentContext = OrderContext.from(row);
                normalizedRows.add(copy(row));
                continue;
            }
            if (currentContext == null) {
                continue;
            }
            normalizedRows.add(currentContext.applyTo(row));
        }
        return normalizedRows;
    }

    /**
     * 判断当前行是否为空白行。
     *
     * @param row 原始字段行
     * @return 全部字段为空时返回 true
     */
    private boolean isBlankRow(Map<String, String> row) {
        return row.values().stream().allMatch(value -> value == null || value.isBlank());
    }

    /**
     * 判断当前行是否包含商品明细字段。
     *
     * @param row 原始字段行
     * @return 商品名称、型号、数量或金额任一存在时返回 true
     */
    private boolean hasProductLine(Map<String, String> row) {
        return !value(row, HEADER_PRODUCT_NAME).isBlank()
                || !value(row, HEADER_MODEL_STYLE).isBlank()
                || !value(row, HEADER_QUANTITY).isBlank()
                || !value(row, HEADER_PRODUCT_AMOUNT).isBlank();
    }

    /**
     * 判断当前行是否为订单主行。
     *
     * @param row 原始字段行
     * @return 订单号、时间、状态、店铺、实付或运费任一存在时返回 true
     */
    private boolean isMainOrderRow(Map<String, String> row) {
        return !value(row, HEADER_ORDER_ID).isBlank()
                || !value(row, HEADER_ORDER_NO).isBlank()
                || !value(row, HEADER_MAIN_ORDER_ID).isBlank()
                || !value(row, HEADER_SUB_ORDER_ID).isBlank()
                || !value(row, HEADER_ORDER_TIME).isBlank()
                || !value(row, HEADER_ORDER_STATUS).isBlank()
                || !value(row, HEADER_SHOP_NAME).isBlank()
                || !value(row, HEADER_PAID_AMOUNT).isBlank()
                || !value(row, HEADER_SHIPPING_FEE).isBlank();
    }

    /**
     * 读取字段值并去除首尾空白。
     *
     * @param row    原始字段行
     * @param header 字段名
     * @return 字段值；缺失时返回空字符串
     */
    private String value(Map<String, String> row, String header) {
        String value = row.get(header);
        return value == null ? "" : value.trim();
    }

    /**
     * 复制字段行，避免修改调用方持有的原始 Map。
     *
     * @param row 原始字段行
     * @return 字段行副本
     */
    private Map<String, String> copy(Map<String, String> row) {
        return new LinkedHashMap<>(row);
    }

    /**
     * 中文订单导出主行上下文。
     *
     * @param values 主行订单上下文字段
     */
    private record OrderContext(Map<String, String> values) {
        /**
         * 从交易成功主行提取订单上下文。
         *
         * @param row 主行字段
         * @return 订单上下文
         */
        private static OrderContext from(Map<String, String> row) {
            Map<String, String> contextValues = new LinkedHashMap<>();
            for (String header : ORDER_CONTEXT_HEADERS) {
                contextValues.put(header, row.getOrDefault(header, ""));
            }
            return new OrderContext(contextValues);
        }

        /**
         * 将订单上下文应用到续行，同时保留续行自己的商品字段。
         *
         * @param row 续行字段
         * @return 补齐上下文后的字段行
         */
        private Map<String, String> applyTo(Map<String, String> row) {
            Map<String, String> normalized = new LinkedHashMap<>(row);
            for (Map.Entry<String, String> entry : values.entrySet()) {
                normalized.put(entry.getKey(), entry.getValue());
            }
            return normalized;
        }
    }
}
