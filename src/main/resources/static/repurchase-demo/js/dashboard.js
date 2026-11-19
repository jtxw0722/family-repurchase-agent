import { compactUnitPrice } from "./compare.js";
import { endpoints } from "./config.js";
import { $, formatQuantity, getJson, htmlEscape } from "./shared.js";

let priceRows = [];
let latestRefresh = 0;

function categoryOf(row) {
    return row.category?.trim() || '未分类';
}

function showFeedback(target, text) {
    const message = document.createElement('p');
    message.className = 'dashboard-feedback';
    message.setAttribute('role', 'status');
    message.textContent = text;
    target.replaceChildren(message);
}

function renderPrices(target) {
    const category = $('dashboard-price-category').value;
    const visibleRows = priceRows.filter(row => !category || categoryOf(row) === category);
    if (!visibleRows.length) {
        showFeedback(target, '暂无价格记录。');
        return;
    }
    const tableRows = visibleRows.map(row => {
        const unit = row.standardUnit ? ' · 元/' + row.standardUnit : '';
        const purchaseDate = row.latestPurchaseDate
            ? '<small>' + htmlEscape(row.latestPurchaseDate) + '</small>' : '';
        return '<tr><th scope="row"><strong>' + htmlEscape(row.normalizedName) + '</strong><small>'
            + htmlEscape(categoryOf(row) + unit) + '</small></th><td>' + compactUnitPrice(row.historicalMin)
            + '</td><td>' + compactUnitPrice(row.historicalMedian) + '</td><td>'
            + compactUnitPrice(row.latestUnitPrice) + purchaseDate + '</td></tr>';
    });
    target.innerHTML = '<table class="dashboard-price-table"><thead><tr><th scope="col">商品</th>'
        + '<th scope="col">历史最低</th><th scope="col">历史中位</th><th scope="col">最近购买</th>'
        + '</tr></thead><tbody>' + tableRows.join('') + '</tbody></table>';
}

function setPriceRows(target, rows) {
    priceRows = rows;
    const filter = $('dashboard-price-category');
    const selected = filter.value;
    const categories = [...new Set(rows.map(categoryOf))].sort((left, right) => left.localeCompare(right, 'zh-CN'));
    filter.replaceChildren(new Option('全部品类', ''), ...categories.map(category => new Option(category, category)));
    filter.value = categories.includes(selected) ? selected : '';
    filter.disabled = false;
    renderPrices(target);
}

function renderReplenishmentGroup(title, tone, rows) {
    const content = rows.map(row => {
        const remaining = row.remainingDays == null
            ? row.status : '预计还能用 ' + formatQuantity(row.remainingDays) + ' 天';
        const date = row.immediate ? row.depletionDate : row.reminderDate;
        const dateLabel = date ? (row.immediate ? '预计耗尽 ' : '提醒日 ') + date : '';
        return '<li><span><strong>' + htmlEscape(row.normalizedName) + '</strong><small>'
            + htmlEscape(remaining) + '</small></span>'
            + (dateLabel ? '<small class="dashboard-alert-date">' + htmlEscape(dateLabel) + '</small>' : '') + '</li>';
    });
    return '<div class="dashboard-alert-group dashboard-alert-' + tone + '"><h3>' + title
        + '<span>' + rows.length + '</span></h3>'
        + (rows.length ? '<ul class="dashboard-alert-list">' + content.join('') + '</ul>'
            : '<p class="dashboard-feedback">暂无商品</p>') + '</div>';
}

function renderReplenishment(target, rows) {
    target.innerHTML = renderReplenishmentGroup('马上需要补货', 'now', rows.filter(row => row.immediate))
        + renderReplenishmentGroup('需要补货', 'soon', rows.filter(row => !row.immediate));
}

async function loadDashboard(id, endpoint, render, version, title) {
    const target = $(id);
    target.setAttribute('aria-busy', 'true');
    showFeedback(target, '正在读取' + title + '…');
    try {
        const rows = await getJson(endpoint);
        // 连续返回首页或手动刷新时，较旧请求不得覆盖最新结果。
        if (version === latestRefresh) render(target, rows);
    } catch {
        if (version === latestRefresh) showFeedback(target, title + '加载失败，请刷新看板。');
    } finally {
        if (version === latestRefresh) target.setAttribute('aria-busy', 'false');
    }
}

export function loadHomeDashboards() {
    const version = ++latestRefresh;
    $('dashboard-price-category').disabled = true;
    loadDashboard('dashboard-prices', endpoints.dashboardPrices, setPriceRows, version, '历史价格');
    loadDashboard('dashboard-replenishment', endpoints.dashboardReplenishment, renderReplenishment, version, '补货提醒');
}

export function initHomeDashboard() {
    $('dashboard-refresh').addEventListener('click', loadHomeDashboards);
    $('dashboard-price-category').addEventListener('change', () => renderPrices($('dashboard-prices')));
}
