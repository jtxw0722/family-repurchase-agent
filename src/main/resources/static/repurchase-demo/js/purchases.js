// 购买记录查询。
import { $, compact, formValues, formatAmount, formatNormalizedName, formatPlatform, formatQuantity, formatScope, formatUnitPrice, htmlEscape, metric, optionalNumber, optionalText, renderTable } from "./shared.js";

export function buildSearchPayload() {
    var value = formValues('search-form');
    return compact({
        keyword: value.keyword,
        owner: optionalText(value.owner),
        limit: optionalNumber(value.limit),
        fromDate: optionalText(value.fromDate),
        toDate: optionalText(value.toDate)
    });
}

export function renderSearch(data) {
    var records = data.records || data.items || [];
    var html = '<div class="result-head"><div><h3>找到 ' + htmlEscape(formatQuantity(data.returnedCount || records.length)) + ' 条记录</h3><p>关键词：' + htmlEscape(data.keyword || formValues('search-form').keyword || '—') + '</p></div></div>';
    html += '<div class="metrics">';
    html += metric('匹配数量', formatQuantity(data.matchedCount || records.length), '');
    html += metric('显示数量', formatQuantity(data.returnedCount || records.length), '');
    html += metric('归属范围', formatScope(data.scope || formValues('search-form').owner), '');
    html += metric('最多显示', formatQuantity(formValues('search-form').limit), '');
    html += '</div>';
    if (data.warnings && data.warnings.length) html += '<div class="reason-card"><h4>提示</h4>' + htmlEscape(data.warnings) + '</div>';
    if (!records.length) {
        html += '<div class="empty-state"><div><strong>没有找到相关记录</strong><span>可以换一个更接近订单的关键词再试。</span></div></div>';
    } else {
        html += '<h4 class="section-title">购买记录</h4>';
        html += renderTable([
            { key: 'orderTime', label: '时间' },
            { key: 'platform', label: '平台', formatter: formatPlatform },
            { key: 'owner', label: '归属人' },
            { key: 'productName', label: '商品名称', wrap: true },
            { key: 'normalizedName', label: '归一化', formatter: formatNormalizedName },
            { key: 'sku', label: '规格', wrap: true },
            { key: 'quantity', label: '数量', formatter: formatQuantity },
            { key: 'unit', label: '单位' },
            { key: 'totalAmount', label: '总价', value: function (row) { return row.totalAmount || row.price; }, formatter: formatAmount },
            { key: 'unitPrice', label: '单价', formatter: formatUnitPrice },
            { key: 'shopName', label: '店铺', value: function (row) { return row.shopName || '—'; }, wrap: true }
        ], records);
    }
    $('search-result').className = '';
    $('search-result').innerHTML = html;
}
