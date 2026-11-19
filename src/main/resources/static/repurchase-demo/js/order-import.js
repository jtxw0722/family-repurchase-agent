// 订单文件导入。
import { ONLINE_IMPORT_PAGE_SIZE } from "./config.js";
import { $, compact, formValues, formatQuantity, htmlEscape, isEmptyValue, metric, optionalText, postJson, renderError, uniqueValues } from "./shared.js";
import { state } from "./state.js";

export function buildImportPayload() {
    var value = formValues('import-form');
    return compact({ filePath: value.filePath, owner: optionalText(value.owner) });
}

export function renderImport(data) {
    var html = '<div class="result-head"><div><h3>' + htmlEscape(data.batchId ? '导入完成' : '导入结果') + '</h3><p>' + htmlEscape(data.message || '请查看本次导入统计。') + '</p></div></div>';
    html += '<div class="metrics">';
    html += metric('批次编号', data.batchId, '');
    html += metric('总数量', formatQuantity(data.totalCount), '');
    html += metric('成功导入', formatQuantity(data.importedCount), '');
    html += metric('需要确认', formatQuantity(data.reviewCount), '');
    html += metric('重复样本', formatQuantity(data.duplicateCount), '');
    html += metric('提示', data.warnings, '');
    html += '</div>';
    $('import-result').className = '';
    $('import-result').innerHTML = html;
}

export function onlineFieldInvalid(row, key) {
    if (key === 'amount') return isEmptyValue(row.amount) || !Number.isFinite(Number(row.amount)) || Number(row.amount) < 0;
    if (key === 'quantity') return isEmptyValue(row.quantity) || !Number.isFinite(Number(row.quantity)) || Number(row.quantity) <= 0;
    if (key === 'purchaseDate') return !/^\d{4}-\d{2}-\d{2}(?:[ T]\d{2}:\d{2}:\d{2})?$/.test(String(row.purchaseDate || ''));
    if (key === 'platform') return ['taobao', 'jd', 'pdd', 'douyin', 'offline', 'manual'].indexOf(row.platform) < 0;
    if (['productName', 'normalizedName', 'unit', 'owner'].indexOf(key) >= 0) return !String(row[key] || '').trim();
    return false;
}

export function onlineRowInvalid(row) {
    return ['productName', 'normalizedName', 'amount', 'quantity', 'unit', 'platform', 'purchaseDate', 'owner']
        .some(function (key) { return onlineFieldInvalid(row, key); });
}

export function updateOnlineImportCount() {
    var selected = state.onlineImportRows.filter(function (row) { return row.selected; });
    if ($('online-import-count')) $('online-import-count').textContent = '共 ' + state.onlineImportRows.length + ' 条，已勾选 ' + selected.length + ' 条';
    if ($('online-import-save')) $('online-import-save').disabled = state.onlineImportBusy || !selected.length || selected.some(onlineRowInvalid);
}

export function renderOnlineImport() {
    var target = $('import-result');
    target.className = '';
    var pages = Math.max(1, Math.ceil(state.onlineImportRows.length / ONLINE_IMPORT_PAGE_SIZE));
    state.onlineImportPage = Math.max(0, Math.min(state.onlineImportPage, pages - 1));
    var html = '<h3>订单预览</h3><p id="online-import-count"></p><p role="status">' + htmlEscape(state.onlineImportMessage) + '</p>';
    html += '<div class="actions"><button type="button" class="btn btn-secondary btn-small" id="online-import-normal">勾选正常记录</button>' +
        '<button type="button" class="btn btn-secondary btn-small" id="online-import-none">全部不选</button>' +
        '<button type="button" class="btn btn-primary" id="online-import-save">确认勾选入库</button></div>';
    html += '<p class="helper">金额为人民币元；标准总含量已包含购买件数。红色字段请先修正；黄色为原解析核对提示，修正后可自行勾选。赠品手动保存后仍不参与价格基线。重复记录在保存前会再次检查并跳过。</p>';
    html += '<datalist id="online-normalized-names">' + uniqueValues(state.normalizationLibraryItems.map(function (rule) { return rule.normalizedName; }))
        .map(function (name) { return '<option value="' + htmlEscape(name) + '"></option>'; }).join('') + '</datalist>';
    var columns = [['productName', '商品名称'], ['normalizedName', '归一化名称'], ['sku', '规格'], ['amount', '实付金额'],
        ['quantity', '标准总含量'], ['unit', '单位'], ['platform', '平台'], ['purchaseDate', '购买时间'], ['owner', '归属人'], ['shopName', '店铺']];
    html += '<div class="table-wrap online-import-scroll"><table class="online-import-table"><thead><tr><th>勾选</th><th>原行号</th><th>状态</th>' +
        columns.map(function (column) { return '<th>' + column[1] + '</th>'; }).join('') + '<th>原解析核对提示</th></tr></thead><tbody>';
    state.onlineImportRows.slice(state.onlineImportPage * ONLINE_IMPORT_PAGE_SIZE, (state.onlineImportPage + 1) * ONLINE_IMPORT_PAGE_SIZE).forEach(function (row, offset) {
        var index = state.onlineImportPage * ONLINE_IMPORT_PAGE_SIZE + offset;
        var invalid = onlineRowInvalid(row);
        html += '<tr class="' + (invalid ? 'online-import-invalid' : row.warnings.length ? 'online-import-warning' : '') + '">' +
            '<td><input aria-label="勾选第 ' + row.rowNumber + ' 行" type="checkbox" data-online-check="' + index + '"' +
            (row.selected ? ' checked' : '') + (invalid ? ' disabled' : '') + '></td><td>' + htmlEscape(row.rowNumber) + '</td>' +
            '<td><span class="tag" data-online-status title="' + htmlEscape(row.warnings.join('；')) + '">' + (invalid ? '待修正' : row.warnings.length ? '需核对' : '正常') + '</span></td>';
        columns.forEach(function (column) {
            var key = column[0];
            if (key === 'platform') {
                html += '<td><select aria-label="第 ' + row.rowNumber + ' 行平台" data-online-index="' + index + '" data-online-field="platform" class="' +
                    (onlineFieldInvalid(row, key) ? 'online-field-invalid' : '') + '"><option value="">请选择平台</option>' +
                    [['taobao','淘宝 / 天猫'],['jd','京东'],['pdd','拼多多'],['douyin','抖音'],['offline','线下'],['manual','其他']].map(function (option) {
                        return '<option value="' + option[0] + '"' + (row.platform === option[0] ? ' selected' : '') + '>' + option[1] + '</option>';
                    }).join('') + '</select></td>';
                return;
            }
            html += '<td><input aria-label="第 ' + row.rowNumber + ' 行' + column[1] + '" data-online-index="' + index + '" data-online-field="' + key +
                '" class="' + (onlineFieldInvalid(row, key) ? 'online-field-invalid' : '') + '"' +
                (key === 'amount' || key === 'quantity' ? ' type="number" step="any" min="0"' : ' type="text" maxlength="500"') +
                (key === 'normalizedName' ? ' list="online-normalized-names"' : '') +
                ' value="' + (isEmptyValue(row[key]) ? '' : htmlEscape(row[key])) + '"></td>';
        });
        html += '<td class="wrap">' + htmlEscape(row.warnings.join('；') || '正常') + '</td></tr>';
    });
    html += '</tbody></table></div><div class="actions"><button type="button" class="btn btn-secondary btn-small" id="online-import-prev">上一页</button>' +
        '<span>第 ' + (state.onlineImportPage + 1) + ' / ' + pages + ' 页，每页 ' + ONLINE_IMPORT_PAGE_SIZE + ' 条</span>' +
        '<button type="button" class="btn btn-secondary btn-small" id="online-import-next">下一页</button></div>';
    target.innerHTML = html;
    $('online-import-prev').disabled = state.onlineImportBusy || state.onlineImportPage === 0;
    $('online-import-next').disabled = state.onlineImportBusy || state.onlineImportPage >= pages - 1;
    $('online-import-prev').onclick = function () { state.onlineImportPage--; renderOnlineImport(); };
    $('online-import-next').onclick = function () { state.onlineImportPage++; renderOnlineImport(); };
    $('online-import-normal').disabled = state.onlineImportBusy;
    $('online-import-none').disabled = state.onlineImportBusy;
    $('online-import-normal').onclick = function () {
        state.onlineImportRows.forEach(function (row) { row.selected = !row.warnings.length && !onlineRowInvalid(row); });
        renderOnlineImport();
    };
    $('online-import-none').onclick = function () { state.onlineImportRows.forEach(function (row) { row.selected = false; }); renderOnlineImport(); };
    $('online-import-save').onclick = saveOnlineImport;
    Array.prototype.forEach.call(target.querySelectorAll('[data-online-check]'), function (input) {
        input.disabled = state.onlineImportBusy || input.disabled;
        input.onchange = function () { state.onlineImportRows[Number(input.dataset.onlineCheck)].selected = input.checked; updateOnlineImportCount(); };
    });
    Array.prototype.forEach.call(target.querySelectorAll('[data-online-field]'), function (input) {
        input.disabled = state.onlineImportBusy;
        input.oninput = function () {
            var row = state.onlineImportRows[Number(input.dataset.onlineIndex)];
            var key = input.dataset.onlineField;
            row[key] = key === 'amount' || key === 'quantity' ? input.value === '' ? null : Number(input.value) : input.value;
            input.classList.toggle('online-field-invalid', onlineFieldInvalid(row, key));
            var invalid = onlineRowInvalid(row);
            if (invalid) row.selected = false;
            var rowElement = input.closest('tr');
            rowElement.className = invalid ? 'online-import-invalid' : row.warnings.length ? 'online-import-warning' : '';
            rowElement.querySelector('[data-online-status]').textContent = invalid ? '待修正' : row.warnings.length ? '需核对' : '正常';
            var check = rowElement.querySelector('[data-online-check]');
            check.disabled = invalid; check.checked = row.selected;
            updateOnlineImportCount();
        };
    });
    updateOnlineImportCount();
}

export function setOnlineImportBusy(busy) {
    state.onlineImportBusy = busy;
    ['online-order-file', 'online-order-owner', 'online-order-preview', 'online-order-clear'].forEach(function (id) { $(id).disabled = busy; });
}

export function clearOnlineImport() {
    state.onlineImportRevision++;
    state.onlineImportRows = []; state.onlineImportPage = 0; state.onlineImportMessage = '';
    $('online-order-file').value = '';
    $('import-result').className = 'empty-state';
    $('import-result').innerHTML = '<div><strong>等待上传订单</strong><span>未确认的预览已清空。</span></div>';
}

export async function previewOnlineImport() {
    if (state.onlineImportBusy) return;
    var file = $('online-order-file').files[0];
    var owner = $('online-order-owner').value.trim();
    var format = file && file.name.split('.').pop().toLowerCase();
    if (!file || !owner || ['csv', 'xlsx'].indexOf(format) < 0 || !file.size || file.size > 10 * 1024 * 1024) {
        renderError('import-result', '请选择订单文件', new Error('支持不超过 10 MB 的 CSV/XLSX，请填写归属人。')); return;
    }
    var revision = ++state.onlineImportRevision;
    state.onlineImportRows = []; state.onlineImportPage = 0;
    setOnlineImportBusy(true);
    $('import-result').className = 'empty-state';
    $('import-result').textContent = '正在解析文件，尚未保存购买记录……';
    try {
        var content = await new Promise(function (resolve, reject) {
            var reader = new FileReader();
            reader.onload = function () { resolve(String(reader.result).split(',')[1]); };
            reader.onerror = function () { reject(new Error('文件读取失败，请重新选择。')); };
            reader.readAsDataURL(file);
        });
        // 不传文件名、路径或 MIME 元数据；原内容不会进入后续确认请求。
        var data = await postJson('/api/tools/online-order-import/preview', {contentBase64: content, format: format, owner: owner});
        content = null;
        if (revision !== state.onlineImportRevision) return;
        state.onlineImportRows = (data.rows || []).map(function (row) { row.warnings = Array.isArray(row.warnings) ? row.warnings : []; return row; });
        state.onlineImportMessage = '预览尚未入库。已跳过 ' + (data.skippedRows || 0) + ' 条非成功交易或空白续行。';
    } catch (error) {
        state.onlineImportMessage = error.message || '文件解析失败，请检查文件格式。';
    } finally {
        $('online-order-file').value = '';
        setOnlineImportBusy(false);
        if (revision === state.onlineImportRevision) renderOnlineImport();
    }
}

export async function saveOnlineImport() {
    if (state.onlineImportBusy) return;
    var selected = state.onlineImportRows.filter(function (row) { return row.selected; });
    if (!selected.length || selected.some(onlineRowInvalid)) return;
    setOnlineImportBusy(true); renderOnlineImport();
    try {
        // 只提交勾选的白名单字段，不发送原始文件内容、原文或文件元数据。
        var data = await postJson('/api/tools/online-order-import/commit', selected.map(function (row) {
            return { rowNumber: row.rowNumber, productName: row.productName, normalizedName: row.normalizedName, sku: row.sku,
                amount: row.amount, quantity: row.quantity, unit: row.unit, platform: row.platform,
                purchaseDate: row.purchaseDate, owner: row.owner, shopName: row.shopName, selected: true, warnings: [] };
        }));
        var saved = data.savedRows || [], duplicates = data.duplicateRows || [];
        state.onlineImportRows = state.onlineImportRows.filter(function (row) { return saved.indexOf(row.rowNumber) < 0; });
        state.onlineImportRows.forEach(function (row) {
            if (duplicates.indexOf(row.rowNumber) >= 0) { row.selected = false; row.warnings = ['保存前查重发现重复，本次未重复入库']; }
        });
        state.onlineImportMessage = '已保存 ' + saved.length + ' 条，查重跳过 ' + duplicates.length + ' 条。剩余未保存的记录仍可修改。';
    } catch (error) { state.onlineImportMessage = error.message || '保存结果未确认，请核对购买记录后重试，系统会再次查重。'; }
    finally { setOnlineImportBusy(false); renderOnlineImport(); }
}

// 仅在模块HTML全部挂载后绑定一次，不在导航切换时重新初始化。
export function initOrderImport() {
    $('online-order-preview').addEventListener('click', previewOnlineImport);

    $('online-order-clear').addEventListener('click', function () { if (!state.onlineImportBusy) clearOnlineImport(); });

    $('online-order-file').addEventListener('change', function () {
        if (state.onlineImportBusy) return;
        state.onlineImportRevision++; state.onlineImportRows = []; state.onlineImportPage = 0;
        $('import-result').className = 'empty-state';
        $('import-result').textContent = '已选择新文件，请点击“解析并预览”；未提交的旧预览已清空。';
    });
}
