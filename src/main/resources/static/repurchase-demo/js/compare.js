// 价格比价。
import { findRuleByCode, normalizationLibraryCategories } from "./normalization.js";
import { $, compact, decisionClass, firstValue, formValues, formatAmount, formatDecision, formatQuantity, formatSourceType, get, htmlEscape, isEmptyValue, metric, optionalNumber, optionalText, setDatalistOptions, uniqueValues, unitOptionsByFamily } from "./shared.js";
import { state } from "./state.js";

export function calculateCompareTotalQuantity(values) {
    var skuQuantity = optionalNumber(values.skuQuantity);
    var purchaseQuantity = optionalNumber(values.quantity);

    if (purchaseQuantity === undefined) return undefined;
    if (skuQuantity === undefined) return purchaseQuantity;

    return skuQuantity * purchaseQuantity;
}

export function updateCompareTotalQuantity() {
    var totalInput = $('compare-total-quantity');
    if (!totalInput) return;
    var value = formValues('compare-form');
    totalInput.value = formatQuantity(calculateCompareTotalQuantity(value));
    if ($('compare-total-unit')) $('compare-total-unit').textContent = value.unit || '';
}

export function buildComparePayload() {
    var value = formValues('compare-form');
    return compact({
        productName: value.productName,
        price: optionalNumber(value.price),
        quantity: calculateCompareTotalQuantity(value),
        unit: optionalText(value.unit)
    });
}

export function compactUnitPrice(value) {
    if (isEmptyValue(value) || !Number.isFinite(Number(value))) return '—';
    var number = Number(value);
    return Math.abs(number) > 0 && Math.abs(number) < 1
        ? number.toLocaleString('zh-CN', { maximumSignificantDigits: 4 })
        : number.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export function compareReferenceRecords(records) {
    function fullName(row) { return htmlEscape(row.productName || '未提供商品名称'); }
    function price(row) { return formatAmount(isEmptyValue(row.price) ? row.totalAmount : row.price); }
    function quantity(row) { return formatQuantity(row.quantity) + ' ' + (row.unit || ''); }
    function unitPrice(row) { return compactUnitPrice(row.unitPrice) + ' 元/' + (row.unitPriceUnit || row.unit || '—'); }
    function date(row) { return row.purchaseDate || row.orderTime || '—'; }
    var html = '<div class="compare-reference-desktop"><h4 class="section-title">参考记录</h4><div class="table-wrap"><table class="compare-reference-table"><thead><tr>';
    ['类型', '商品名称', '单价', '购买价格', '计量总量', '购买日期', '归属人'].forEach(function (label) { html += '<th>' + label + '</th>'; });
    html += '</tr></thead><tbody>';
    records.forEach(function (row) {
        html += '<tr><td>' + htmlEscape(formatSourceType(row.role)) + '</td><td><details class="reference-name-details"><summary aria-label="查看完整商品名称和记录号"><span class="reference-name">' + fullName(row) + '</span></summary><p class="reference-extra">记录号：' + htmlEscape(row.recordId) + '</p></details></td>';
        [unitPrice(row), price(row), quantity(row), date(row), row.owner || '未记录'].forEach(function (value) { html += '<td>' + htmlEscape(value) + '</td>'; });
        html += '</tr>';
    });
    html += '</tbody></table></div></div><details class="compare-details compare-reference-mobile"><summary>参考记录（' + records.length + ' 条）</summary>';
    records.forEach(function (row) {
        html += '<article class="reference-record"><div class="reference-record-head"><span>' + htmlEscape(formatSourceType(row.role)) + '</span><strong>' + htmlEscape(unitPrice(row)) + '</strong></div><div class="reference-name">' + fullName(row) + '</div><p class="reference-extra">' + htmlEscape(date(row)) + ' · 购买价 ' + htmlEscape(price(row)) + ' 元 · 总量 ' + htmlEscape(quantity(row)) + '</p><details><summary>查看详情</summary><p class="reference-extra">' + fullName(row) + '</p><p class="reference-extra">记录号：' + htmlEscape(row.recordId) + '</p><p class="reference-extra">归属人：' + htmlEscape(row.owner || '未记录') + '</p></details></article>';
    });
    return html + '</details>';
}

export function renderCompare(data, submittedValues) {
    var values = submittedValues || formValues('compare-form');
    var decision = data.decision || {};
    var code = decision.code || decision.text || data.decisionCode || data.decision || 'unknown';
    var reason = decision.reason || data.reason || data.explanation || data.message || '';
    var warningItems = uniqueValues((Array.isArray(data.warnings) ? data.warnings : [data.warnings]).filter(function (item) { return typeof item === 'string' && item.trim(); }));
    // 后端原因可能附带同一条警告；只去掉完全一致的重复文案，保留原始判断与其他说明。
    warningItems.forEach(function (warning) { reason = String(reason).split(warning).join('').trim(); });
    var sourceRecords = get(data, 'evidence.sourceRecords') || data.sourceRecords || data.records || [];
    var unit = firstValue(data, ['current.unitPriceUnit', 'baseline.unitPriceUnit', 'unitPriceUnit', 'baseline.unit']) || values.unit || '—';
    var count = firstValue(data, ['baseline.sampleSize', 'baseline.sampleCount', 'sampleSize', 'sampleCount']);
    var html = '<div class="result-head"><div><h3>本次价格判断</h3><p>' + htmlEscape(values.productName || '—') + '</p></div><span class="decision-pill ' + decisionClass(code) + '">' + htmlEscape(formatDecision(code)) + '</span></div>';
    html += '<div class="compare-core">';
    if (data.mode !== 'baseline_only') html += metric('当前单价', compactUnitPrice(firstValue(data, ['current.unitPrice', 'currentUnitPrice'])), '元/' + unit).replace('class="metric"', 'class="metric compare-current"');
    html += metric('历史最低', compactUnitPrice(firstValue(data, ['baseline.historicalMin', 'historicalMin', 'minUnitPrice'])), '元/' + unit);
    html += metric('历史中位', compactUnitPrice(firstValue(data, ['baseline.historicalMedian', 'historicalMedian', 'medianUnitPrice'])), '元/' + unit);
    html += '</div><p class="compare-sample-count">' + (Number(count) > 0 ? '基于 ' + htmlEscape(formatQuantity(count)) + ' 条可比历史记录' : '暂无可比历史记录，请核对商品和单位') + '</p>';
    if (warningItems.length) html += '<div class="compare-warning" role="note">' + warningItems.map(function (warning) { return '<p>注意：' + htmlEscape(warning) + '</p>'; }).join('') + '</div>';
    html += '<details class="compare-details"><summary>计算明细与判断依据</summary><dl class="compare-calculation">';
    var calculations = [['购买价格', formatAmount(isEmptyValue(data.current && data.current.price) ? values.price : data.current.price) + ' 元'], ['购买数量', formatQuantity(values.quantity) + ' 件'], ['单件规格', formatQuantity(values.skuQuantity) + ' ' + (values.unit || '')], ['计量总量', formatQuantity(firstValue(data, ['current.quantity']) || calculateCompareTotalQuantity(values)) + ' ' + (values.unit || '')], ['历史均价', compactUnitPrice(firstValue(data, ['baseline.historicalAverage', 'historicalAverage', 'averageUnitPrice'])) + ' 元/' + unit]];
    calculations.forEach(function (item) { html += '<div><dt>' + item[0] + '</dt><dd>' + htmlEscape(item[1]) + '</dd></div>'; });
    html += '</dl>';
    if (reason) html += '<div class="reason-card"><h4>判断依据</h4>' + htmlEscape(reason) + '</div>';
    html += '</details>';
    if (sourceRecords.length) html += compareReferenceRecords(sourceRecords);
    html += '<div class="compare-result-actions"><button class="btn btn-secondary btn-small" type="button" id="compare-edit-conditions">修改条件</button></div>';
    $('compare-result').className = '';
    $('compare-result').innerHTML = html;
    var edit = $('compare-edit-conditions');
    if (edit) edit.onclick = function () { $('compare-form').scrollIntoView({ behavior: 'smooth', block: 'start' }); };
}

export function compareCandidateRules() {
    return state.normalizationLibraryItems.filter(function (item) {
        return item && item.normalizedName && item.enabled !== false;
    });
}

export function populateCompareCategoryOptions() {
    var select = $('compare-category-select');
    var current = select.value;
    var categories = normalizationLibraryCategories();
    select.innerHTML = '<option value="">全部品类</option>';
    categories.forEach(function (category) {
        var option = document.createElement('option');
        option.value = category;
        option.textContent = category;
        select.appendChild(option);
    });
    select.value = categories.indexOf(current) >= 0 ? current : '';
}

export function compareRulesForCategory() {
    var category = $('compare-category-select').value;
    return compareCandidateRules().filter(function (rule) {
        return !category || String(rule.category || '').trim() === category;
    });
}

export function populateCompareRuleOptions(matchProductName) {
    var select = $('compare-rule-select');
    if (!select) return;

    var currentRuleCode = select.value;
    var currentName = $('compare-product-name') ? $('compare-product-name').value : '';
    var rules = compareRulesForCategory();
    var matchedByName = matchProductName === false ? null : rules.find(function (rule) {
        return String(rule.normalizedName || '').trim() === String(currentName || '').trim();
    });

    select.innerHTML = '';
    var manualOption = document.createElement('option');
    manualOption.value = '';
    manualOption.textContent = '手动输入商品名称';
    select.appendChild(manualOption);

    rules.forEach(function (item) {
        var option = document.createElement('option');
        option.value = item.ruleCode || item.normalizedName || '';
        option.textContent = (item.normalizedName || '未命名') + '（' + (item.standardUnit || '未设置单位') + '）';
        option.dataset.normalizedName = item.normalizedName || '';
        select.appendChild(option);
    });

    if (matchedByName && matchedByName.ruleCode) {
        select.value = matchedByName.ruleCode;
    } else if (currentRuleCode && rules.some(function (rule) { return rule.ruleCode === currentRuleCode; })) {
        select.value = currentRuleCode;
    } else {
        select.value = '';
    }
    if (matchProductName !== false) updateCompareNameEditor(select.value ? true : false);
}

export function populateCompareUnitOptions(rule) {
    if (!rule) {
        setDatalistOptions('compare-unit-options', []);
        return;
    }
    var values = unitOptionsByFamily(rule.unitFamily);
    if (rule.standardUnit) {
        values = [rule.standardUnit].concat(values);
    }
    setDatalistOptions('compare-unit-options', uniqueValues(values));
}

export function updateCompareNameEditor(collapseForRule) {
    var editor = $('compare-name-editor');
    var summary = $('compare-name-summary');
    var input = $('compare-product-name');
    if (!editor || !summary || !input) return;
    summary.textContent = input.value.trim() || '请输入';
    if (collapseForRule === true && input.value.trim()) editor.open = false;
    if (collapseForRule === false) editor.open = true;
}

export function applyCompareRuleToCompareForm(rule, overwriteProductName) {
    var nameInput = $('compare-product-name');
    var unitInput = $('compare-unit');
    var select = $('compare-rule-select');
    var hint = $('compare-unit-hint');
    if (!nameInput || !unitInput) return;

    if (!rule) {
        populateCompareUnitOptions(null);
        if (select) select.value = '';
        if (hint) hint.textContent = '当前商品名称未匹配到规则库，请自行填写单位。';
        updateCompareNameEditor(false);
        return;
    }

    if (overwriteProductName && rule.normalizedName) {
        nameInput.value = rule.normalizedName;
    }
    $('compare-category-select').value = String(rule.category || '').trim();
    populateCompareRuleOptions(false);
    if (select && rule.ruleCode) {
        select.value = rule.ruleCode;
    }
    populateCompareUnitOptions(rule);
    updateCompareNameEditor(overwriteProductName ? true : undefined);
    if (rule.standardUnit) {
        unitInput.value = rule.standardUnit;
    }
    if (hint) {
        hint.textContent = '已匹配规则库商品：' + (rule.normalizedName || rule.ruleCode || '—') + '，可从同一单位族中选择或自行输入。';
    }
}

export function applyCompareRuleBinding() {
    var nameInput = $('compare-product-name');
    if (!nameInput) return;
    var rule = compareRulesForCategory().find(function (item) {
        return String(item.normalizedName || '').trim() === nameInput.value.trim();
    });
    applyCompareRuleToCompareForm(rule, false);
}

export function handleCompareCategoryChange() {
    // 切换品类只更新候选项；不替用户选择第一条规则，也不清空已填写的商品或价格。
    populateCompareRuleOptions(false);
    if (!$('compare-rule-select').value) {
        populateCompareUnitOptions(null);
        $('compare-unit-hint').textContent = compareRulesForCategory().length
            ? '请选择该品类下的规则，或手动输入商品名称和单位。'
            : '该品类下暂无可用规则，可以手动输入商品名称和单位。';
    }
}

export function handleCompareRuleSelectChange() {
    var select = $('compare-rule-select');
    if (!select) return;
    var rule = findRuleByCode(select.value);
    if (!rule) {
        $('compare-category-select').value = '';
        populateCompareRuleOptions(false);
        select.value = '';
        populateCompareUnitOptions(null);
        var hint = $('compare-unit-hint');
        if (hint) hint.textContent = '可以手动输入商品名称和单位。';
        updateCompareNameEditor(false);
        return;
    }
    applyCompareRuleToCompareForm(rule, true);
}

// 仅在模块HTML全部挂载后绑定一次，不在导航切换时重新初始化。
export function initCompare() {
    if ($('compare-rule-select')) {
        $('compare-rule-select').addEventListener('change', handleCompareRuleSelectChange);
    }

    $('compare-category-select').addEventListener('change', handleCompareCategoryChange);

    if ($('compare-product-name')) {
        $('compare-product-name').addEventListener('input', applyCompareRuleBinding);
        $('compare-product-name').addEventListener('change', applyCompareRuleBinding);
    }

    if ($('compare-sku-quantity')) {
        $('compare-sku-quantity').addEventListener('input', updateCompareTotalQuantity);
        $('compare-sku-quantity').addEventListener('change', updateCompareTotalQuantity);
    }

    if ($('compare-purchase-quantity')) {
        $('compare-purchase-quantity').addEventListener('input', updateCompareTotalQuantity);
        $('compare-purchase-quantity').addEventListener('change', updateCompareTotalQuantity);
    }

    if ($('compare-unit')) $('compare-unit').addEventListener('input', updateCompareTotalQuantity);
}
