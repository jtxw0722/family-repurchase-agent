// 公共DOM、请求和格式化工具。
import { state } from "./state.js";

export function $(id) { return document.getElementById(id); }

export function isEmptyValue(value) {
    return value === null || value === undefined || value === '';
}

export function toFiniteNumber(value) {
    var number = Number(value);
    return Number.isFinite(number) ? number : null;
}

export function trimZero(value) {
    return String(value).replace(/\.00$/, '').replace(/(\.\d*[1-9])0+$/, '$1');
}

export function formatTextNumbers(value) {
    if (isEmptyValue(value)) return '—';
    return String(value).replace(/-?\d+\.\d{5,}/g, function (matched) {
        var number = Number(matched);
        return Number.isFinite(number) ? number.toFixed(4) : matched;
    });
}

export function display(value) {
    if (isEmptyValue(value)) return '—';
    if (Array.isArray(value)) return value.length ? value.map(display).join('；') : '—';
    if (typeof value === 'object') return formatTextNumbers(JSON.stringify(value));
    return formatTextNumbers(String(value));
}

export function htmlEscape(value) {
    return display(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

export function formatDecimal(value, digits) {
    if (isEmptyValue(value)) return '—';
    var number = toFiniteNumber(value);
    if (number === null) return display(value);
    return number.toFixed(digits);
}

export function formatAmount(value) { return formatDecimal(value, 2); }

export function formatUnitPrice(value) { return formatDecimal(value, 4); }

export function formatQuantity(value) {
    if (isEmptyValue(value)) return '—';
    var number = toFiniteNumber(value);
    if (number === null) return display(value);
    if (Number.isInteger(number)) return String(number);
    return trimZero(number.toFixed(3));
}

export function optionalText(value) { return value === '' ? undefined : value; }

export function optionalNumber(value) {
    if (value === '') return undefined;
    var number = Number(value);
    return Number.isFinite(number) ? number : undefined;
}

export function compact(object) {
    Object.keys(object).forEach(function (key) {
        if (object[key] === undefined || object[key] === '') delete object[key];
    });
    return object;
}

export function splitKeywords(value) {
    if (!value) return [];
    return value.split(/[，,\n]/).map(function (item) { return item.trim(); }).filter(Boolean);
}

export function joinKeywords(list) {
    return Array.isArray(list) ? list.join(',') : '';
}

export function formValues(formId) {
    var form = $(formId);
    var values = {};
    Array.prototype.forEach.call(form.elements, function (field) {
        if (!field.name || field.disabled) return;
        values[field.name] = field.type === 'checkbox' ? field.checked : String(field.value || '').trim();
    });
    return values;
}

export async function postJson(url, payload) {
    var response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
        body: JSON.stringify(payload)
    });
    return readJsonResponse(response, '处理失败');
}

export async function getJson(url) {
    var response = await fetch(url, {
        method: 'GET',
        headers: { 'Accept': 'application/json' },
        cache: 'no-store'
    });
    return readJsonResponse(response, '加载失败');
}

export async function readJsonResponse(response, fallbackMessage) {
    var raw = await response.text();
    var data = null;
    if (raw) {
        try {
            data = JSON.parse(raw);
        } catch (error) {
            throw new Error('返回内容格式不正确，请稍后再试。');
        }
    }
    if (!response.ok || (data && (data.error || data.success === false))) {
        var message = data && (data.message || data.error || data.detail || data.title);
        throw new Error(message || fallbackMessage);
    }
    return data;
}

export function setLoading(button, loading) {
    if (!button) return;
    if (loading) {
        button.dataset.originalText = button.textContent;
        button.textContent = '处理中...';
        button.disabled = true;
    } else {
        button.textContent = button.dataset.originalText || button.textContent;
        button.disabled = false;
    }
}

export function renderError(elementId, title, error) {
    $(elementId).innerHTML = '<div class="empty-state"><div><strong>' + htmlEscape(title) + '</strong><span>' + htmlEscape(error.message || '请检查输入内容后重试。') + '</span></div></div>';
}

export function metric(label, value, note) {
    return '<div class="metric"><span>' + htmlEscape(label) + '</span><strong>' + htmlEscape(value) + '</strong>' + (note ? '<small>' + htmlEscape(note) + '</small>' : '') + '</div>';
}

export function tableCell(row, column) {
    var raw = typeof column.value === 'function' ? column.value(row) : row[column.key];
    if (typeof column.formatter === 'function') return column.formatter(raw, row);
    return display(raw);
}

export function renderTable(columns, rows) {
    if (!rows || !rows.length) return '';
    var html = '<div class="table-wrap"><table><thead><tr>';
    columns.forEach(function (column) { html += '<th>' + htmlEscape(column.label) + '</th>'; });
    html += '</tr></thead><tbody>';
    rows.forEach(function (row) {
        html += '<tr' + (row.ruleCode ? ' class="rule-row" data-rule-code="' + htmlEscape(row.ruleCode) + '"' : '') + '>';
        columns.forEach(function (column) {
            html += '<td' + (column.wrap ? ' class="wrap"' : '') + '>' + htmlEscape(tableCell(row, column)) + '</td>';
        });
        html += '</tr>';
    });
    html += '</tbody></table></div>';
    return html;
}

export function formatSourceType(value) {
    var map = { historical_min: '历史最低', median_sample: '历史中位', latest: '最近购买', current: '本次购买', baseline: '基准样本' };
    return map[String(value || '').toLowerCase()] || display(value);
}

export function formatPlatform(value) {
    var map = {
        manual: '手动录入', taobao: '淘宝', tmall: '淘宝', jd: '京东', jingdong: '京东', pdd: '拼多多',
        douyin: '抖音', kuaishou: '快手', xiaohongshu: '小红书', vip: '唯品会', suning: '苏宁', offline: '线下购买'
    };
    return map[String(value || '').trim().toLowerCase()] || display(value);
}

export function formatScope(value) {
    var text = String(value || '').trim();
    if (!text || text.toUpperCase() === 'FAMILY') return '全家庭';
    return text;
}

export function formatUnitFamily(value) {
    var map = { volume: '体积', weight: '重量', mass: '重量', count: '计数', draw_count: '抽数', unknown: '未设置' };
    return map[String(value || '').trim().toLowerCase()] || display(value);
}

export function normalizeUnitFamily(value) {
    var key = String(value || '').trim().toLowerCase();
    if (key === 'mass') return 'weight';
    if (key === 'weight') return 'weight';
    if (key === 'volume') return 'volume';
    if (key === 'count') return 'count';
    if (key === 'draw_count') return 'draw_count';
    return key || 'volume';
}

export function unitOptionsByFamily(unitFamily) {
    var family = normalizeUnitFamily(unitFamily);
    var options = {
        volume: ['L', 'ml'],
        weight: ['kg', 'g'],
        count: ['件', '个', '片', '条', '包', '抽', '盒', '瓶', '袋'],
        draw_count: ['抽', '包', '提', '箱']
    };
    return options[family] || [];
}

export function setDatalistOptions(datalistId, values) {
    var datalist = $(datalistId);
    if (!datalist) return;
    datalist.innerHTML = '';
    values.forEach(function (value) {
        if (isEmptyValue(value)) return;
        var option = document.createElement('option');
        option.value = String(value);
        datalist.appendChild(option);
    });
}

export function uniqueValues(values) {
    var seen = {};
    var result = [];
    values.forEach(function (value) {
        var text = String(value || '').trim();
        if (!text || seen[text]) return;
        seen[text] = true;
        result.push(text);
    });
    return result;
}

export function formatEnabled(value) { return value ? '启用' : '停用'; }

export function formatKeywordList(values) {
    if (!Array.isArray(values) || !values.length) return '—';
    return values.join('、');
}

export function formatNormalizedName(value, row) {
    if (isEmptyValue(value)) return '/';
    var normalized = String(value).trim();
    var productName = row && !isEmptyValue(row.productName) ? String(row.productName).trim() : '';
    if (productName && normalized === productName) return '/';
    return normalized;
}

export function formatDecision(value) {
    var map = { good_price: '价格不错', fair_price: '价格正常', high_price: '价格偏高', insufficient_samples: '样本不足', no_baseline: '暂无基准线', include: '可记录', exclude: '不记录', review: '需要确认' };
    return map[String(value || '').toLowerCase()] || display(value);
}

export function decisionClass(code) {
    var text = String(code || '').toLowerCase();
    if (text.indexOf('good') >= 0 || text === 'include') return 'decision-good';
    if (text.indexOf('high') >= 0 || text === 'exclude') return 'decision-bad';
    if (text.indexOf('insufficient') >= 0 || text.indexOf('review') >= 0 || text.indexOf('watch') >= 0 || text.indexOf('fair') >= 0) return 'decision-watch';
    return 'decision-neutral';
}

export function get(object, path) {
    if (!object) return undefined;
    return path.split('.').reduce(function (current, key) {
        return current && current[key] !== undefined ? current[key] : undefined;
    }, object);
}

export function firstValue(object, paths) {
    for (var i = 0; i < paths.length; i += 1) {
        var value = get(object, paths[i]);
        if (!isEmptyValue(value)) return value;
    }
    return undefined;
}
