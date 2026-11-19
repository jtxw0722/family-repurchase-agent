// 页面接口及展示常量，不保存用户业务数据。
export const endpoints = {
    compare: '/api/tools/compare-price',
    search: '/api/tools/purchase-records/search',
    record: '/api/inventory/record-sample',
    parseOrderImage: '/api/tools/parse-order-image',
    normalization: '/api/tools/normalization-library',
    normalizationLibrary: '/api/tools/normalization-library',
    ruleCodeSuggestion: '/api/tools/normalization-rule-code-suggestion',
    import: '/api/tools/import-file'
};

export const MODULE_HELP_TEXT = {
    compare: {
        description: '比较当前价格与历史购买价格',
        tips: [
            '选择已有商品，或输入订单中的商品名称。',
            '填写总价、单件规格、购买数量和单位，系统自动计算单价。',
            '遇到赠品、组合装或规格变化时，建议结合购买记录核对。'
        ]
    },
    search: {
        description: '查询历史购买记录',
        tips: [
            '输入商品名称、品牌或关键词，查找相关记录。',
            '可按归属人和购买日期筛选，不填归属人时查询全家庭记录。',
            '比较不同记录的价格前，请先核对规格、数量和单位。'
        ]
    },
    record: {
        description: '记录已购买商品的价格',
        tips: [
            '可以手动填写，也可以选择、拖入或粘贴订单截图进行识别。',
            '核对商品、规格、实付金额和购买数量，先试算，再确认录入。',
            '已上传的订单无需再次录入；提示“可能重复”时，请先核对历史记录。'
        ]
    },
    normalization: {
        description: '维护复购品品类，查看历史参考价格',
        tips: [
            '新增规则，或点击已有规则，维护商品名称、品类和标准单位。',
            '用关键词匹配商品，用排除词避免误匹配。',
            '可按品类筛选规则；中位数为历史参考单价，请结合单位和样本数量查看。'
        ]
    },
    import: {
        description: '导入历史订单，保存购买记录',
        tips: [
            '选择 CSV 或 XLSX 订单文件并填写归属人，先解析预览。',
            '修正高亮提示的数据，再勾选需要保存的记录；赠品和疑似重复默认不选。',
            '确认入库只保存勾选的业务数据，原文件及收货、物流信息不留存。'
        ]
    }
};

export const ONLINE_IMPORT_PAGE_SIZE = 50;

export const RULE_CODE_DEBOUNCE_MS = 600;
