// 通用表单提交编排。
import { buildComparePayload, renderCompare } from "./compare.js";
import { endpoints } from "./config.js";
import { buildNormalizationPayload, ensureGeneratedRuleCode, loadNormalizationLibrary, renderNormalization } from "./normalization.js";
import { buildImportPayload, renderImport } from "./order-import.js";
import { buildSearchPayload, renderSearch } from "./purchases.js";
import { buildRecordPayload, renderRecord, submitRecord } from "./record.js";
import { $, formValues, postJson, renderError, setLoading } from "./shared.js";
import { state } from "./state.js";

const builders = {
    compare: buildComparePayload,
    search: buildSearchPayload,
    normalization: buildNormalizationPayload,
    import: buildImportPayload
};

const renderers = {
    compare: renderCompare,
    search: renderSearch,
    record: renderRecord,
    normalization: renderNormalization,
    import: renderImport
};

export async function submit(moduleName, button) {
    if (moduleName === 'record') return submitRecord(button);
    var form = $(moduleName + '-form');
    if (moduleName === 'normalization' && $('normalization-action').value === 'create_rule') {
        var nameBeforeGeneration = $('normalized-name-input').value.trim();
        setLoading(button, true);
        try {
            // 输入后立即保存也必须等待当前名称的编码，不能提交旧值。
            await ensureGeneratedRuleCode();
        } finally {
            setLoading(button, false);
        }
        if ($('normalization-action').value !== 'create_rule'
            || $('normalized-name-input').value.trim() !== nameBeforeGeneration) return;
    }
    if (!form.reportValidity()) return;
    var compareValues = moduleName === 'compare' ? formValues('compare-form') : null;
    var payload = moduleName === 'record' ? buildRecordPayload(button.dataset.dryRun === 'true') : builders[moduleName]();
    setLoading(button, true);
    try {
        var data = await postJson(endpoints[moduleName], payload);
        if (moduleName === 'normalization') {
            state.normalizationLibraryLoaded = false;
            // 保存的影响条数指规则操作；先读取最新规则库，再展示独立的历史样本数量。
            await loadNormalizationLibrary(true);
        }
        renderers[moduleName](data || {}, compareValues);
        if (moduleName === 'compare' && window.matchMedia('(max-width: 1120px)').matches) {
            $('compare-result').scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
    } catch (error) {
        var titles = { compare: '暂时无法给出价格建议', search: '暂时没有找到记录', record: '样本处理失败', normalization: '规则保存失败', import: '订单导入失败' };
        renderError(moduleName + '-result', titles[moduleName], error);
    } finally {
        setLoading(button, false);
    }
}

// 仅在模块HTML全部挂载后绑定一次，不在导航切换时重新初始化。
export function initActions() {
    Array.prototype.forEach.call(document.querySelectorAll('[data-submit]'), function (button) {
        button.addEventListener('click', function () { submit(button.dataset.submit, button); });
    });

    Array.prototype.forEach.call(document.querySelectorAll('form'), function (form) {
        form.addEventListener('submit', function (event) { event.preventDefault(); });
    });
}
