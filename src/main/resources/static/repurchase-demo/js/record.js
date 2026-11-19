// 手动记录样本与试算保存。
import { compactUnitPrice, compareCandidateRules } from "./compare.js";
import { endpoints } from "./config.js";
import { findRuleByCode, findRuleByNormalizedName } from "./normalization.js";
import { bindRecordImageTransferEvents, closeRecordImagePreview, openRecordImagePreview, parseOrderImage, setRecordField, updateParsedOrderNote, updateRecordImageFileInfo } from "./record-image.js";
import { $, compact, formValues, formatAmount, formatDecision, formatNormalizedName, formatPlatform, formatQuantity, formatUnitPrice, htmlEscape, metric, optionalNumber, optionalText, postJson, renderError, renderTable, setLoading, uniqueValues, unitOptionsByFamily } from "./shared.js";
import { state } from "./state.js";

export function recordUnitRequiresContent(unit) {
    var meteredUnits = unitOptionsByFamily('volume').concat(unitOptionsByFamily('weight'));
    return meteredUnits.some(function (item) { return item.toLowerCase() === String(unit || '').trim().toLowerCase(); });
}

export function calculateRecordTotalQuantity(values) {
    var skuQuantity = optionalNumber(values.skuQuantity);
    var purchaseQuantity = optionalNumber(values.purchaseQuantity);

    if (purchaseQuantity === undefined) return undefined;
    if (skuQuantity === undefined) return recordUnitRequiresContent(values.unit) ? undefined : purchaseQuantity;

    return skuQuantity * purchaseQuantity;
}

export function updateRecordTotalQuantity() {
    var totalInput = $('record-total-quantity');
    if (!totalInput) return;
    var value = formValues('record-form');
    var contentInput = $('record-sku-quantity');
    if (contentInput) contentInput.required = recordUnitRequiresContent(value.unit);
    totalInput.value = formatQuantity(calculateRecordTotalQuantity(value));
    if ($('record-total-unit')) $('record-total-unit').textContent = value.unit || '';
}

export function buildRecordPayload(dryRun) {
    var value = formValues('record-form');
    return {
        dryRun: dryRun,
        records: [compact({
            productName: value.productName,
            normalizedName: optionalText(value.normalizedName),
            price: optionalNumber(value.price),
            quantity: calculateRecordTotalQuantity(value),
            unit: value.unit,
            platform: optionalText(value.platform),
            purchaseDate: optionalText(value.purchaseDate),
            owner: optionalText(value.owner),
            shopName: optionalText(value.shopName),
            sku: optionalText(value.sku),
            note: optionalText(value.note),
            sourceText: optionalText(value.sourceText),
            confirmOutOfRange: value.confirmOutOfRange
        })]
    };
}

export function renderRecord(data) {
    var records = data.records || [];
    var first = records[0] || {};
    var title = data.dryRun ? (first.reviewRequired ? '试算完成，请核对' : '试算完成')
        : Number(data.savedCount) > 0 ? (first.reviewRequired ? '已保存，需复核' : '已录入') : '处理完成';
    var html = '<div class="result-head"><div><h3>' + htmlEscape(title) + '</h3><p>' + htmlEscape(first.productName || formValues('record-form').productName || '—') + '</p></div></div>';
    html += '<div class="record-summary-metrics">' + metric('标准商品', formatNormalizedName(first.normalizedName, first), '')
        + metric('单价', compactUnitPrice(first.unitPrice), '元/' + (first.unit || '—')) + '</div>';
    if (data.dryRun) html += '<p class="compare-sample-count">本次仅试算，尚未录入。</p>';
    var reasons = uniqueValues(records.reduce(function (all, item) { return all.concat(item.reviewReasons || []); }, []));
    if (reasons.length) html += '<div class="compare-warning" role="note">' + reasons.map(function (reason) { return '<p>' + htmlEscape(reason) + '</p>'; }).join('') + '</div>';
    html += '<details class="compare-details"><summary>处理明细</summary><dl class="compare-calculation">';
    [['处理结果', formatDecision(first.decision)], ['购买价格', formatAmount(first.price) + ' 元'], ['计量总量', formatQuantity(first.quantity) + ' ' + (first.unit || '')], ['记录编号', first.recordId], ['已保存', data.savedCount], ['需复核', data.reviewCount]].forEach(function (item) {
        html += '<div><dt>' + item[0] + '</dt><dd>' + htmlEscape(item[1]) + '</dd></div>';
    });
    html += '</dl>';
    if (records.length > 1) html += renderTable([
        { key: 'recordId', label: '记录' }, { key: 'productName', label: '商品名称', wrap: true },
        { key: 'normalizedName', label: '标准商品' }, { key: 'quantity', label: '计量总量' },
        { key: 'unit', label: '单位' }, { key: 'unitPrice', label: '单价', formatter: formatUnitPrice },
        { key: 'decision', label: '结果', formatter: formatDecision }
    ], records);
    html += '</details>';
    $('record-result').className = '';
    $('record-result').innerHTML = html;
}

export function populateRecordRuleOptions() {
    var select = $('record-normalized-rule-select');
    if (!select) return;

    var currentRuleCode = select.value;
    var currentName = $('record-normalized-name') ? $('record-normalized-name').value : '';
    var matchedByName = findRuleByNormalizedName(currentName);
    var rules = compareCandidateRules();

    select.innerHTML = '';
    var manualOption = document.createElement('option');
    manualOption.value = '';
    manualOption.textContent = '手动填写标准名称';
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
    } else if (currentRuleCode && findRuleByCode(currentRuleCode)) {
        select.value = currentRuleCode;
    } else {
        select.value = '';
    }
}

export function updateRecordNormalizedField() {
    var wrapper = $('record-normalized-name-field');
    var select = $('record-normalized-rule-select');
    if (!wrapper || !select) return;
    if (select.value) wrapper.classList.add('hidden');
    else wrapper.classList.remove('hidden');
}

export function updateRecordNameEditor(fromCandidate) {
    var editor = $('record-name-editor');
    if (!editor) return;
    var name = formValues('record-form').productName || '';
    $('record-name-summary').textContent = name;
    if (fromCandidate && name.trim()) {
        editor.classList.add('is-collapsible');
        editor.open = false;
    } else if (!name.trim()) {
        editor.classList.remove('is-collapsible');
        editor.open = true;
    }
}

export function updateRecordMoreSummary() {
    var summary = $('record-more-summary');
    if (!summary) return;
    var values = formValues('record-form');
    var parts = [values.sku, values.platform && values.platform.toLowerCase() !== 'manual' ? formatPlatform(values.platform) : '', values.shopName, values.note ? '含备注' : ''].filter(Boolean);
    summary.textContent = parts.length ? parts.join(' · ') : '原始规格、平台、店铺、备注';
}

export function recordFingerprint(payload) {
    var record = Object.assign({}, payload.records[0]);
    delete record.confirmOutOfRange;
    return JSON.stringify(record);
}

export function invalidateRecordReview() {
    state.recordPreviewFingerprint = '';
    var checkbox = $('record-confirm-out-of-range');
    if (checkbox) { checkbox.checked = false; checkbox.required = false; checkbox.disabled = true; }
    var wrapper = $('record-price-confirmation');
    if (wrapper) wrapper.classList.add('hidden');
}

export function showRecordPriceConfirmation(data) {
    var required = (data.records || []).some(function (record) {
        return (record.reviewReasons || []).some(function (reason) {
            // 对应现有后端价格范围复核文案，其余复核原因不能通过这个复选框绕过。
            return /明显低于历史最低|明显高于历史最高/.test(String(reason));
        });
    });
    var wrapper = $('record-price-confirmation');
    var checkbox = $('record-confirm-out-of-range');
    if (!wrapper || !checkbox) return required;
    if (required) wrapper.classList.remove('hidden');
    else wrapper.classList.add('hidden');
    checkbox.disabled = !required;
    checkbox.required = required;
    if (!required) checkbox.checked = false;
    return required;
}

export async function submitRecord(button) {
    if (state.recordSubmissionBusy) return;
    var form = $('record-form');
    var dryRun = button.dataset.dryRun === 'true';
    var checkbox = $('record-confirm-out-of-range');
    var confirmationDisabled = checkbox && checkbox.disabled;
    // 再次试算不要求先接受价格异常；正式录入仍使用原生必选校验。
    if (dryRun && checkbox) checkbox.disabled = true;
    var valid = form.reportValidity();
    if (dryRun && checkbox) checkbox.disabled = confirmationDisabled;
    if (!valid) return;
    var payload = buildRecordPayload(dryRun);
    var fingerprint = recordFingerprint(payload);
    // 即使字段被程序修改而未触发 input，也不得复用旧订单的价格确认。
    if (state.recordPreviewFingerprint !== fingerprint) {
        invalidateRecordReview();
        payload.records[0].confirmOutOfRange = false;
    }
    state.recordSubmissionBusy = true;
    var buttons = Array.prototype.slice.call(document.querySelectorAll('[data-submit="record"]'));
    buttons.forEach(function (item) { item.disabled = true; });
    setLoading(button, true);
    try {
        if (dryRun || state.recordPreviewFingerprint !== fingerprint) {
            var previewPayload = Object.assign({}, payload, { dryRun: true });
            previewPayload.records = payload.records.map(function (record) { return Object.assign({}, record, { confirmOutOfRange: false }); });
            var preview = await postJson(endpoints.record, previewPayload);
            if (recordFingerprint(buildRecordPayload(true)) !== fingerprint) {
                invalidateRecordReview();
                throw new Error('商品信息已修改，请重新试算或确认录入。');
            }
            state.recordPreviewFingerprint = fingerprint;
            var needsPriceConfirmation = showRecordPriceConfirmation(preview);
            if (dryRun || needsPriceConfirmation) {
                renderRecord(preview);
                if (needsPriceConfirmation) $('record-price-confirmation').scrollIntoView({ behavior: 'smooth', block: 'center' });
                else if (window.matchMedia('(max-width: 1120px)').matches) $('record-result').scrollIntoView({ behavior: 'smooth', block: 'start' });
                return;
            }
        }
        var result = await postJson(endpoints.record + '?receiveInventory=' + !!$('record-receive-inventory').checked, buildRecordPayload(false));
        renderRecord(result);
        invalidateRecordReview();
        if (window.matchMedia('(max-width: 1120px)').matches) $('record-result').scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (error) {
        renderError('record-result', '样本处理失败', error);
    } finally {
        state.recordSubmissionBusy = false;
        setLoading(button, false);
        buttons.forEach(function (item) { item.disabled = false; });
    }
}

export function applyRecordRuleToForm(rule, overwriteNormalizedName, overwriteUnit) {
    var select = $('record-normalized-rule-select');
    var normalizedInput = $('record-normalized-name');
    if (!select || !normalizedInput) return;

    if (!rule) {
        select.value = '';
        updateRecordNormalizedField();
        return;
    }

    if (rule.ruleCode) {
        select.value = rule.ruleCode;
    }
    if (overwriteNormalizedName && rule.normalizedName) {
        normalizedInput.value = rule.normalizedName;
    }
    if (overwriteUnit && rule.standardUnit) {
        setRecordField('unit', rule.standardUnit);
    }
    updateRecordNormalizedField();
    updateRecordTotalQuantity();
}

export function handleRecordRuleSelectChange() {
    var select = $('record-normalized-rule-select');
    if (!select) return;
    var rule = findRuleByCode(select.value);
    if (!rule) { updateRecordNormalizedField(); return; }
    applyRecordRuleToForm(rule, true, true);
    updateParsedOrderNote();
}

export function syncRecordRuleSelectionByName(overwriteUnit) {
    var input = $('record-normalized-name');
    var select = $('record-normalized-rule-select');
    if (!input || !select) return;

    var rule = findRuleByNormalizedName(input.value);
    if (!rule) {
        select.value = '';
        updateRecordNormalizedField();
        return;
    }
    applyRecordRuleToForm(rule, false, Boolean(overwriteUnit));
}

// 仅在模块HTML全部挂载后绑定一次，不在导航切换时重新初始化。
export function initRecord() {
    document.querySelector('#record-form [name="purchaseDate"]').addEventListener('change', function () {
        var shanghaiToday = new Intl.DateTimeFormat('en-CA', {timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
        $('record-receive-inventory').checked = this.value === shanghaiToday;
    });

    if ($('record-image-file')) {
        $('record-image-file').addEventListener('change', updateRecordImageFileInfo);
    }

    bindRecordImageTransferEvents();

    if ($('open-record-image-preview')) {
        $('open-record-image-preview').addEventListener('click', openRecordImagePreview);
    }

    if ($('record-image-preview-img')) {
        $('record-image-preview-img').addEventListener('click', openRecordImagePreview);
    }

    if ($('close-record-image-preview')) {
        $('close-record-image-preview').addEventListener('click', closeRecordImagePreview);
    }

    if ($('record-image-modal')) {
        $('record-image-modal').addEventListener('click', function (event) {
            if (event.target === this) closeRecordImagePreview();
        });
    }

    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') closeRecordImagePreview();
    });

    if ($('parse-order-image-button')) {
        $('parse-order-image-button').addEventListener('click', function () {
            parseOrderImage(this);
        });
    }

    if ($('record-normalized-rule-select')) {
        $('record-normalized-rule-select').addEventListener('change', handleRecordRuleSelectChange);
    }

    if ($('record-normalized-name')) {
        $('record-normalized-name').addEventListener('input', function () {
            syncRecordRuleSelectionByName(false);
            updateParsedOrderNote();
        });
        $('record-normalized-name').addEventListener('change', function () {
            syncRecordRuleSelectionByName(false);
            updateParsedOrderNote();
        });
    }

    if ($('record-form')) {
        ['input', 'change'].forEach(function (eventName) {
            $('record-form').addEventListener(eventName, function (event) {
                if (event.target.name === 'confirmOutOfRange') return;
                invalidateRecordReview();
                updateRecordTotalQuantity();
                updateRecordNameEditor(false);
                updateRecordMoreSummary();
            });
        });
    }

    if ($('record-name-editor')) $('record-name-editor').addEventListener('toggle', function () {
        if (this.open) {
            var input = this.querySelector('[name="productName"]');
            if (input) input.focus();
        }
    });

    if ($('record-sku-quantity')) {
        $('record-sku-quantity').addEventListener('input', updateRecordTotalQuantity);
        $('record-sku-quantity').addEventListener('change', updateRecordTotalQuantity);
    }

    if ($('record-purchase-quantity')) {
        $('record-purchase-quantity').addEventListener('input', updateRecordTotalQuantity);
        $('record-purchase-quantity').addEventListener('change', updateRecordTotalQuantity);
    }
}
