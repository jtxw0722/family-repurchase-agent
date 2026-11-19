// 归一化规则管理。
import { applyCompareRuleBinding, populateCompareCategoryOptions, populateCompareRuleOptions } from "./compare.js";
import { RULE_CODE_DEBOUNCE_MS, endpoints } from "./config.js";
import { populateRecordRuleOptions, syncRecordRuleSelectionByName } from "./record.js";
import { $, compact, display, firstValue, formValues, formatDecision, formatEnabled, formatLibraryMedian, formatQuantity, formatUnitFamily, formatUnitPrice, get, getJson, htmlEscape, isEmptyValue, joinKeywords, metric, normalizeUnitFamily, optionalNumber, optionalText, postJson, renderTable, splitKeywords } from "./shared.js";
import { state } from "./state.js";

export function buildNormalizationPayload() {
    var value = formValues('normalization-form');
    var action = value.action;

    if (action === 'apply_rule_to_records' || action === 'recheck_rule_records') {
        var historyPayload = compact({
            action: action,
            ruleCode: value.ruleCode,
            batchId: optionalNumber(value.batchId),
            owner: optionalText(value.owner),
            dryRun: value.dryRun,
            limit: optionalNumber(value.limit)
        });
        if (action === 'apply_rule_to_records') {
            historyPayload.onlyLegacyFallback = value.onlyLegacyFallback;
            historyPayload.onlyExcluded = value.onlyExcluded;
        }
        return historyPayload;
    }

    var commonRule = compact({
        action: action,
        ruleCode: value.ruleCode,
        normalizedName: optionalText(value.normalizedName),
        category: optionalText(value.category),
        standardUnit: optionalText(value.standardUnit),
        unitFamily: optionalText(value.unitFamily),
        priority: optionalNumber(value.priority)
    });

    if (action === 'create_rule') {
        commonRule.enabled = value.enabled;
        commonRule.keywords = splitKeywords(value.keywords);
        commonRule.excludeKeywords = splitKeywords(value.excludeKeywords);
        return commonRule;
    }

    if (action === 'update_rule') {
        commonRule.enabled = value.enabled;
        commonRule.keywords = splitKeywords(value.keywords);
        commonRule.excludeKeywords = splitKeywords(value.excludeKeywords);
        return commonRule;
    }

    return commonRule;
}

export function renderKeywordTags(values, type) {
    if (!Array.isArray(values) || !values.length) return '—';
    return '<div class="keyword-tags">' + values.map(function (item) {
        return '<span class="keyword-tag ' + (type === 'exclude' ? 'exclude' : '') + '">' + htmlEscape(item) + '</span>';
    }).join('') + '</div>';
}

export function renderNormalizationLibrary(items) {
    var target = $('normalization-library-list');
    if (!target) return;
    state.normalizationLibraryItems = Array.isArray(items) ? items : [];
    populateNormalizationCategoryOptions();
    populateNormalizationRuleSelect();
    populateCompareCategoryOptions();
    populateCompareRuleOptions();
    populateRecordRuleOptions();
    applyCompareRuleBinding();
    syncRecordRuleSelectionByName(false);
    populateNormalizationLibraryCategoryFilter();
    renderNormalizationLibraryTable();
}

export function renderNormalizationLibraryTable() {
    var target = $('normalization-library-list');
    var category = $('normalization-library-category-filter').value;
    var visibleItems = state.normalizationLibraryItems.filter(function (item) {
        return !category || String(item.category || '').trim() === category;
    });
    if (!state.normalizationLibraryItems.length) {
        target.className = 'library-state';
        target.innerHTML = '<div><strong>还没有规则记录</strong><span>可以先新增一条规则。</span></div>';
        return;
    }
    if (!visibleItems.length) {
        target.className = 'library-state';
        target.innerHTML = '<div><strong>该品类下暂无规则</strong><span>可以选择其他品类或全部品类。</span></div>';
        return;
    }
    target.className = '';
    target.innerHTML = renderTable([
        { key: 'ruleCode', label: '规则编码' },
        { key: 'normalizedName', label: '归一化名称' },
        { key: 'medianUnitPrice', label: '中位数', formatter: formatLibraryMedian },
        { key: 'category', label: '品类' },
        { key: 'standardUnit', label: '标准单位' },
        { key: 'unitFamily', label: '单位族', formatter: formatUnitFamily },
        { key: 'sampleCount', label: '样本数量', formatter: formatQuantity },
        { key: 'keywords', label: '正向关键词', value: function (row) { return renderKeywordTags(row.keywords, 'include'); }, wrap: true },
        { key: 'excludeKeywords', label: '排除关键词', value: function (row) { return renderKeywordTags(row.excludeKeywords, 'exclude'); }, wrap: true },
        { key: 'priority', label: '优先级', formatter: formatQuantity },
        { key: 'enabled', label: '状态', formatter: formatEnabled },
        { key: 'source', label: '来源' }
    ], visibleItems).replace(/&lt;div class=&quot;keyword-tags&quot;&gt;/g, '<div class="keyword-tags">')
        .replace(/&lt;\/div&gt;/g, '</div>')
        .replace(/&lt;span class=&quot;keyword-tag &quot;&gt;/g, '<span class="keyword-tag ">')
        .replace(/&lt;span class=&quot;keyword-tag exclude&quot;&gt;/g, '<span class="keyword-tag exclude">')
        .replace(/&lt;\/span&gt;/g, '</span>');
    bindRuleRows();
    if ($('normalization-action').value !== 'create_rule') {
        markSelectedRule($('normalization-rule-code').value);
    }
}

export function populateNormalizationLibraryCategoryFilter() {
    var select = $('normalization-library-category-filter');
    var current = select.value;
    var categories = normalizationLibraryCategories();
    select.innerHTML = '<option value="">全部品类</option>';
    categories.forEach(function (category) {
        var option = document.createElement('option');
        option.value = category;
        option.textContent = category;
        select.appendChild(option);
    });
    // 刷新后保留仍然存在的品类；该品类已不存在时恢复全部，避免留下无效筛选。
    select.value = categories.indexOf(current) >= 0 ? current : '';
}

export async function loadNormalizationLibrary(force) {
    var target = $('normalization-library-list');
    if (!target) return;
    if (state.normalizationLibraryLoaded && !force) return;
    target.className = 'library-state';
    target.innerHTML = '<div><strong>正在加载</strong><span>正在读取当前规则库。</span></div>';
    try {
        var items = await getJson(endpoints.normalizationLibrary);
        state.normalizationLibraryLoaded = true;
        renderNormalizationLibrary(items);
    } catch (error) {
        target.className = 'library-state';
        target.innerHTML = '<div><strong>规则库加载失败</strong><span>' + htmlEscape(error.message || '请稍后重试。') + '</span></div>';
    }
}

export function findRuleByNormalizedName(normalizedName) {
    var text = String(normalizedName || '').trim();
    return state.normalizationLibraryItems.find(function (item) {
        return String(item.normalizedName || '').trim() === text;
    });
}

export function findRuleByCode(ruleCode) {
    return state.normalizationLibraryItems.find(function (item) { return item.ruleCode === ruleCode; });
}

export function populateNormalizationRuleSelect() {
    var select = $('normalization-rule-select');
    if (!select) return;
    var current = select.value;
    select.innerHTML = '';
    if (!state.normalizationLibraryItems.length) {
        var emptyOption = document.createElement('option');
        emptyOption.value = '';
        emptyOption.textContent = '请先加载规则库';
        select.appendChild(emptyOption);
        return;
    }
    state.normalizationLibraryItems.forEach(function (item) {
        var option = document.createElement('option');
        option.value = item.normalizedName || item.ruleCode;
        option.dataset.ruleCode = item.ruleCode || '';
        option.textContent = (item.normalizedName || '未命名') + '（' + (item.ruleCode || '无编码') + '）';
        select.appendChild(option);
    });
    if (current && Array.prototype.some.call(select.options, function (option) { return option.value === current; })) {
        select.value = current;
    }
}

export function normalizationLibraryCategories() {
    var categories = [];
    state.normalizationLibraryItems.forEach(function (item) {
        var category = String(item.category || '').trim();
        if (category && categories.indexOf(category) < 0) categories.push(category);
    });
    return categories;
}

export function populateNormalizationCategoryOptions() {
    var select = $('normalization-category-select');
    if (!select) return;
    var current = select.value;
    var categories = normalizationLibraryCategories();
    select.innerHTML = '<option value="">手动输入品类</option>';
    categories.forEach(function (category) {
        var option = document.createElement('option');
        option.value = category;
        option.textContent = category;
        select.appendChild(option);
    });
    select.value = categories.indexOf(current) >= 0 ? current : '';
    $('normalization-category').readOnly = Boolean(select.value);
    $('normalization-category').classList.toggle('hidden', Boolean(select.value));
}

export function handleNormalizationCategoryChange() {
    var category = $('normalization-category-select').value;
    var input = $('normalization-category');
    input.readOnly = Boolean(category);
    input.classList.toggle('hidden', Boolean(category));
    if (category) input.value = category;
    else input.focus();
}

export function fillRuleForm(rule) {
    if (!rule) return;
    $('normalization-rule-code').value = rule.ruleCode || '';
    $('normalized-name-input').value = rule.normalizedName || '';
    $('normalization-rule-select').value = rule.normalizedName || '';
    $('normalization-category').value = rule.category || '';
    $('normalization-category-select').value = String(rule.category || '').trim();
    $('normalization-category').readOnly = Boolean($('normalization-category-select').value);
    $('normalization-category').classList.toggle('hidden', Boolean($('normalization-category-select').value));
    $('normalization-standard-unit').value = rule.standardUnit || '';
    $('normalization-unit-family').value = normalizeUnitFamily(rule.unitFamily);
    $('normalization-priority').value = !isEmptyValue(rule.priority) ? rule.priority : '';
    $('normalization-keywords').value = joinKeywords(rule.keywords);
    $('normalization-exclude-keywords').value = joinKeywords(rule.excludeKeywords);
    $('normalization-enabled').checked = Boolean(rule.enabled);
    markSelectedRule(rule.ruleCode);
}

export function invalidateRuleCodeGeneration() {
    state.ruleCodeGenerationRevision += 1;
    clearTimeout(state.ruleCodeGenerationTimer);
    state.ruleCodeGenerationTimer = null;
    state.ruleCodeGenerationPromise = null;
}

export function setRuleCodeHint(message) {
    $('rule-code-generation-hint').textContent = message;
}

export function validateRuleCodeInput() {
    var input = $('normalization-rule-code');
    input.setCustomValidity('');
    if ($('normalization-action').value !== 'create_rule') return;
    var code = input.value.trim().toLowerCase();
    if (code && (findRuleByCode(code) || state.generatedRuleCodeExists)) {
        input.setCustomValidity('规则编码已存在，请修改编码或选择更新规则。');
        setRuleCodeHint('规则编码已存在，请修改编码或选择更新规则。');
    } else {
        setRuleCodeHint(state.ruleCodeEditedManually
            ? '已保留手动填写的编码。'
            : '填写归一化名称后自动生成英文编码，手动修改后保留。');
    }
}

export function handleNormalizedRuleNameInput() {
    if ($('normalization-action').value !== 'create_rule' || state.ruleCodeEditedManually) return;
    invalidateRuleCodeGeneration();
    state.generatedRuleCodeName = '';
    state.generatedRuleCodeExists = false;
    $('normalization-rule-code').value = '';
    validateRuleCodeInput();
    if ($('normalized-name-input').value.trim()) {
        state.ruleCodeGenerationTimer = setTimeout(ensureGeneratedRuleCode, RULE_CODE_DEBOUNCE_MS);
    }
}

export function handleRuleCodeInput() {
    if ($('normalization-action').value !== 'create_rule') return;
    invalidateRuleCodeGeneration();
    state.ruleCodeEditedManually = Boolean($('normalization-rule-code').value.trim());
    state.generatedRuleCodeName = '';
    state.generatedRuleCodeExists = false;
    validateRuleCodeInput();
    // 清空手动编码表示恢复自动生成；仅点击或聚焦输入框不会停止自动生成。
    if (!state.ruleCodeEditedManually) handleNormalizedRuleNameInput();
}

export function ensureGeneratedRuleCode() {
    clearTimeout(state.ruleCodeGenerationTimer);
    state.ruleCodeGenerationTimer = null;
    var name = $('normalized-name-input').value.trim();
    if ($('normalization-action').value !== 'create_rule' || state.ruleCodeEditedManually || !name) {
        validateRuleCodeInput();
        return Promise.resolve();
    }
    if (state.generatedRuleCodeName === name && $('normalization-rule-code').value) {
        validateRuleCodeInput();
        return Promise.resolve();
    }
    if (state.ruleCodeGenerationPromise) return state.ruleCodeGenerationPromise;
    var revision = ++state.ruleCodeGenerationRevision;
    var input = $('normalization-rule-code');
    input.setCustomValidity('正在生成英文编码，请稍候。');
    setRuleCodeHint('正在生成英文编码…');
    function isCurrentRequest() {
        return revision === state.ruleCodeGenerationRevision && !state.ruleCodeEditedManually
            && $('normalization-action').value === 'create_rule'
            && $('normalized-name-input').value.trim() === name;
    }
    state.ruleCodeGenerationPromise = postJson(endpoints.ruleCodeSuggestion, { normalizedName: name })
        .then(function (suggestion) {
            if (!isCurrentRequest()) return;
            if (!suggestion || typeof suggestion.ruleCode !== 'string'
                || !/^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$/.test(suggestion.ruleCode)
                || suggestion.ruleCode.length > 96) throw new Error('无效的编码建议');
            input.value = suggestion.ruleCode;
            state.generatedRuleCodeName = name;
            state.generatedRuleCodeExists = suggestion.exists === true;
            validateRuleCodeInput();
            if (!input.validationMessage) setRuleCodeHint('已自动生成英文编码，可手动修改。');
        })
        .catch(function (error) {
            if (!isCurrentRequest()) return;
            // 失败时清空自动编码，避免误提交名称修改之前的旧编码。
            input.value = '';
            var missingEntryMessage = '离线词库未收录该完整名称或译义不明确，请手动填写英文编码。';
            // 只透传约定的缺词提示，不展示网络异常、内部堆栈或其他接口原文。
            var message = error && error.message === missingEntryMessage ? missingEntryMessage
                : '暂时无法生成英文编码，请手动填写或稍后重试。';
            input.setCustomValidity(message);
            setRuleCodeHint(message);
        })
        .finally(function () {
            if (revision === state.ruleCodeGenerationRevision) state.ruleCodeGenerationPromise = null;
        });
    return state.ruleCodeGenerationPromise;
}

export function markSelectedRule(ruleCode) {
    Array.prototype.forEach.call(document.querySelectorAll('.rule-row'), function (row) {
        row.classList.toggle('selected', row.dataset.ruleCode === ruleCode);
    });
}

export function bindRuleRows() {
    Array.prototype.forEach.call(document.querySelectorAll('.rule-row'), function (row) {
        row.addEventListener('click', function () {
            var rule = findRuleByCode(row.dataset.ruleCode);
            if (!rule) return;
            $('normalization-action').value = 'update_rule';
            updateNormalizationFormMode();
            fillRuleForm(rule);
        });
    });
}

export function setNormalizationRuleFieldsVisible(visible) {
    ['normalization-category-field', 'normalization-standard-unit-field', 'normalization-unit-family-field',
        'normalization-priority-field', 'normalization-enabled-field', 'normalization-keywords-field',
        'normalization-exclude-keywords-field'].forEach(function (id) {
        var element = $(id);
        if (element) element.classList.toggle('hidden', !visible);
    });
}

export function updateNormalizationSubmitText(action) {
    var button = $('normalization-submit-button');
    if (!button) return;
    var textMap = {
        create_rule: '保存规则',
        update_rule: '保存规则',
        apply_rule_to_records: '补齐历史样本',
        recheck_rule_records: '重算历史样本'
    };
    button.textContent = textMap[action] || '保存规则';
}

export function updateHistoryOperationText(action) {
    var title = $('history-operation-title');
    var description = $('history-operation-description');
    var onlyLegacyField = $('apply-only-legacy-field');
    var onlyExcludedField = $('apply-only-excluded-field');
    var isRecheck = action === 'recheck_rule_records';
    if (title) {
        title.textContent = isRecheck ? '重算范围' : '补齐范围';
    }
    if (description) {
        description.textContent = isRecheck
            ? '先解绑命中当前规则排除词的历史样本，再按当前规则库重新匹配可归属到本规则的记录。'
            : '把未命中明确规则或当前已排除的历史记录，尝试补齐为当前规则下的可统计样本。';
    }
    if (onlyLegacyField) {
        onlyLegacyField.classList.toggle('hidden', isRecheck);
    }
    if (onlyExcludedField) {
        onlyExcludedField.classList.toggle('hidden', isRecheck);
    }
}

export function updateNormalizationFormMode() {
    var action = $('normalization-action').value;
    invalidateRuleCodeGeneration();
    if (action !== state.normalizationFormAction) {
        state.ruleCodeEditedManually = false;
        state.generatedRuleCodeName = '';
        state.generatedRuleCodeExists = false;
    }
    state.normalizationFormAction = action;
    validateRuleCodeInput();
    var isUpdate = action === 'update_rule';
    var isHistoryOperation = action === 'apply_rule_to_records' || action === 'recheck_rule_records';
    var useExistingRule = isUpdate || isHistoryOperation;
    $('normalized-name-input-field').classList.toggle('hidden', useExistingRule);
    $('normalized-name-select-field').classList.toggle('hidden', !useExistingRule);
    $('normalized-name-input').disabled = useExistingRule;
    $('normalization-rule-select').disabled = !useExistingRule;
    $('normalization-rule-code').readOnly = useExistingRule;
    $('rule-code-readonly-hint').classList.toggle('hidden', !useExistingRule);
    $('rule-code-generation-hint').classList.toggle('hidden', useExistingRule);
    setNormalizationRuleFieldsVisible(!isHistoryOperation);
    $('apply-rule-fields').classList.toggle('hidden', !isHistoryOperation);
    updateHistoryOperationText(action);
    updateNormalizationSubmitText(action);
    if (useExistingRule) {
        if (!state.normalizationLibraryLoaded) {
            loadNormalizationLibrary(true).then(function () {
                if ($('normalization-action').value !== action) return;
                var rule = findRuleByNormalizedName($('normalization-rule-select').value) || state.normalizationLibraryItems[0];
                fillRuleForm(rule);
            });
        } else {
            var rule = findRuleByNormalizedName($('normalization-rule-select').value) || state.normalizationLibraryItems[0];
            fillRuleForm(rule);
        }
    } else {
        markSelectedRule(null);
        $('normalization-category-select').value = '';
        $('normalization-category').readOnly = false;
        $('normalization-category').classList.toggle('hidden', false);
    }
}

export function formatApplyStatus(value) {
    var map = {
        applicable: '可补齐',
        updated: '已更新',
        review_required: '需要复核',
        skipped: '已跳过',
        would_reset: '将解绑',
        reset: '已解绑',
        would_normalize: '将归一化',
        normalized: '已归一化'
    };
    return map[String(value || '').toLowerCase()] || display(value);
}

export function renderApplyRuleResult(data, values) {
    var items = data.items || data.records || data.results || [];
    var dryRun = data.dryRun !== undefined ? data.dryRun : values.dryRun;
    var title = dryRun ? '历史样本补齐预览完成' : '历史样本补齐完成';
    var html = '<div class="result-head"><div><h3>' + htmlEscape(title) + '</h3><p>' + htmlEscape(data.normalizedName || values.normalizedName || values.ruleCode || '—') + '</p></div></div>';
    html += '<div class="metrics">';
    html += metric('规则编码', data.ruleCode || values.ruleCode, '');
    html += metric('扫描数量', formatQuantity(firstValue(data, ['candidateCount', 'scannedCount', 'totalCount'])), '');
    html += metric('返回数量', formatQuantity(firstValue(data, ['itemCount', 'resultCount', 'matchedCount']) || items.length), '');
    html += metric('可补齐', formatQuantity(data.applicableCount), '');
    html += metric('需要复核', formatQuantity(data.reviewRequiredCount), '');
    html += metric('已更新', formatQuantity(data.updatedCount), '');
    html += metric('已跳过', formatQuantity(data.skippedCount), '');
    html += metric('执行方式', dryRun ? '仅预览' : '已写入', '');
    html += '</div>';
    if (data.warnings && data.warnings.length) {
        html += '<div class="reason-card"><h4>提示</h4>' + htmlEscape(data.warnings) + '</div>';
    }
    if (!items.length) {
        html += '<div class="empty-state"><div><strong>没有匹配到可补齐记录</strong><span>可以放宽筛选条件，或确认规则关键词是否能命中历史购买记录。</span></div></div>';
    } else {
        html += '<h4 class="section-title">补齐记录</h4>';
        html += renderTable([
            { key: 'recordId', label: '记录' },
            { key: 'status', label: '状态', formatter: formatApplyStatus },
            { key: 'productName', label: '商品名称', wrap: true },
            { key: 'sku', label: '规格', wrap: true },
            { key: 'beforeName', label: '原归一化', value: function (row) { return get(row, 'before.normalizedName'); } },
            { key: 'beforeDecision', label: '原决策', value: function (row) { return get(row, 'before.decision'); }, formatter: formatDecision },
            { key: 'beforeQuantity', label: '原数量', value: function (row) { return get(row, 'before.quantity'); }, formatter: formatQuantity },
            { key: 'beforeUnit', label: '原单位', value: function (row) { return get(row, 'before.unit'); } },
            { key: 'afterName', label: '新归一化', value: function (row) { return get(row, 'after.normalizedName'); } },
            { key: 'afterDecision', label: '新决策', value: function (row) { return get(row, 'after.decision'); }, formatter: formatDecision },
            { key: 'afterQuantity', label: '新数量', value: function (row) { return get(row, 'after.quantity'); }, formatter: formatQuantity },
            { key: 'afterUnit', label: '新单位', value: function (row) { return get(row, 'after.unit'); } },
            { key: 'afterUnitPrice', label: '新单价', value: function (row) { return get(row, 'after.unitPrice'); }, formatter: formatUnitPrice },
            { key: 'warnings', label: '说明', wrap: true }
        ], items);
    }
    $('normalization-result').className = '';
    $('normalization-result').innerHTML = html;
}

export function renderRecheckRuleResult(data, values) {
    var items = data.items || [];
    var dryRun = data.dryRun !== undefined ? data.dryRun : values.dryRun;
    var title = dryRun ? '历史样本重算预览完成' : '历史样本重算完成';
    var html = '<div class="result-head"><div><h3>' + htmlEscape(title) + '</h3><p>' + htmlEscape(data.normalizedName || values.normalizedName || values.ruleCode || '—') + '</p></div></div>';
    html += '<div class="metrics">';
    html += metric('规则编码', data.ruleCode || values.ruleCode, '');
    html += metric('扫描数量', formatQuantity(data.candidateCount), '');
    html += metric('需处理', formatQuantity(data.matchedCount), '');
    html += metric(dryRun ? '将解绑' : '已解绑', formatQuantity(firstValue(data, ['resetCount', 'excludedCount'])), '');
    html += metric(dryRun ? '将归一化' : '已归一化', formatQuantity(data.normalizedCount), '');
    html += metric('需要复核', formatQuantity(data.reviewRequiredCount), '');
    html += metric('已更新', formatQuantity(data.updatedCount), '');
    html += metric('已跳过', formatQuantity(data.skippedCount), '');
    html += metric('执行方式', dryRun ? '仅预览' : '已写入', '');
    html += '</div>';
    if (data.warnings && data.warnings.length) {
        html += '<div class="reason-card"><h4>提示</h4>' + htmlEscape(data.warnings) + '</div>';
    }
    if (!items.length) {
        html += '<div class="empty-state"><div><strong>没有需要重算的记录</strong><span>当前筛选范围内没有需要解绑、重新归一化或人工复核的历史样本。</span></div></div>';
    } else {
        html += '<h4 class="section-title">重算记录</h4>';
        html += renderTable([
            { key: 'recordId', label: '记录' },
            { key: 'status', label: '状态', formatter: formatApplyStatus },
            { key: 'matchedExcludeKeyword', label: '命中排除词' },
            { key: 'productName', label: '商品名称', wrap: true },
            { key: 'sku', label: '规格', wrap: true },
            { key: 'beforeName', label: '原归一化', value: function (row) { return get(row, 'before.normalizedName'); } },
            { key: 'beforeDecision', label: '原决策', value: function (row) { return get(row, 'before.decision'); }, formatter: formatDecision },
            { key: 'afterName', label: '新归一化', value: function (row) { return get(row, 'after.normalizedName'); } },
            { key: 'afterDecision', label: '新决策', value: function (row) { return get(row, 'after.decision'); }, formatter: formatDecision },
            { key: 'warnings', label: '说明', wrap: true }
        ], items);
    }
    $('normalization-result').className = '';
    $('normalization-result').innerHTML = html;
}

export function renderNormalization(data) {
    var values = formValues('normalization-form');
    var action = String(data.action || values.action || '').toLowerCase();
    if (action === 'apply_rule_to_records') {
        renderApplyRuleResult(data, values);
        return;
    }
    if (action === 'recheck_rule_records') {
        renderRecheckRuleResult(data, values);
        return;
    }
    var html = '<div class="result-head"><div><h3>规则已处理</h3><p>' + htmlEscape(data.message || '可以继续维护下一条规则。') + '</p></div></div>';
    html += '<div class="metrics">';
    html += metric('操作类型', formatNormalizationAction(action), '');
    html += metric('规则编码', data.ruleCode || values.ruleCode, '');
    html += metric('归一化名称', data.normalizedName || values.normalizedName, '');
    var affectedLabel = action === 'create_rule' ? '新增规则数'
        : action === 'update_rule' ? '更新规则数' : '处理条数';
    html += metric(affectedLabel, formatQuantity(data.affectedRows), '');
    if (action === 'create_rule' || action === 'update_rule') {
        var rule = state.normalizationLibraryLoaded ? findRuleByCode(data.ruleCode || values.ruleCode) : null;
        html += metric('样本数量', rule ? formatQuantity(rule.sampleCount) : '—',
            rule ? '当前有效历史样本，与上方规则库列表一致。' : '样本数量暂未获取，可刷新规则库查看。');
    }
    html += metric('提示', data.warnings || data.message, '');
    html += '</div>';
    $('normalization-result').className = '';
    $('normalization-result').innerHTML = html;
}

export function formatNormalizationAction(value) {
    var map = {
        create_rule: '新增规则',
        update_rule: '更新规则',
        apply_rule_to_records: '补齐历史样本',
        recheck_rule_records: '重算历史样本'
    };
    return map[String(value || '').toLowerCase()] || display(value);
}

// 仅在模块HTML全部挂载后绑定一次，不在导航切换时重新初始化。
export function initNormalization() {
    $('refresh-normalization-library').addEventListener('click', function () {
        state.normalizationLibraryLoaded = false;
        loadNormalizationLibrary(true);
    });

    $('normalization-library-category-filter').addEventListener('change', renderNormalizationLibraryTable);

    $('normalization-action').addEventListener('change', updateNormalizationFormMode);

    $('normalization-category-select').addEventListener('change', handleNormalizationCategoryChange);

    $('normalized-name-input').addEventListener('input', handleNormalizedRuleNameInput);

    $('normalization-rule-code').addEventListener('input', handleRuleCodeInput);

    $('normalization-rule-select').addEventListener('change', function () {
        fillRuleForm(findRuleByNormalizedName(this.value));
    });
}
