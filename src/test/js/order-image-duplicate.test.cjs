'use strict';

// 执行真实页面渲染函数，验证提示含义及多商品切换，禁止用“填入”推断历史保存状态。
const assert = require('node:assert/strict');
const test = require('node:test');
const vm = require('node:vm');

const {loadHtml,loadScript} = require('./helpers/frontend-source.cjs');
const html = loadHtml();
const script = loadScript();
const bootstrap = script.indexOf("/* frontend-test-bootstrap */");
assert.ok(bootstrap > 0);
const executable = script.slice(0, bootstrap) + '\nglobalThis.render = renderParseOrderImageResult;\n}());';

function render(candidates, index = 0) {
    const output = { innerHTML: '', className: '' };
    const context = vm.createContext({ document: { getElementById: () => output, querySelectorAll: () => [] } });
    vm.runInContext(executable, context);
    context.render({ recognitionSource: 'local', candidates }, index);
    return output.innerHTML;
}

test('明确匹配已保存订单时，状态和显著提示均为已上传', () => {
    const output = render([{ productName: '大米', duplicate: { status: 'saved', matchedRecordId: 7 } }]);
    assert.match(output, /<span>保存状态<\/span><strong>已上传<\/strong>/);
    assert.match(output, /duplicate-notice" role="status"><h4>该订单记录已上传/);
    assert.doesNotMatch(output, /<span>保存状态<\/span><strong>待确认/);
    assert.match(output, /无需再次保存/);
    assert.match(output, /本次使用本地模型解析/);
});

test('明细匹配而无订单号证据时显示可能重复，不能声称已上传', () => {
    const output = render([{ duplicate: { status: 'possible' } }]);
    assert.match(output, /<span>保存状态<\/span><strong>可能重复/);
    assert.match(output, /发现可能重复的记录/);
    assert.doesNotMatch(output, /该订单记录已上传/);
});

test('未命中历史记录时，填入表单仍需确认保存', () => {
    const output = render([{ duplicate: { status: 'none' } }]);
    assert.match(output, /<span>保存状态<\/span><strong>待确认/);
    assert.match(output, /确认后保存/);
    assert.doesNotMatch(output, /duplicate-notice|该订单记录已上传/);
});

test('查重失败及旧接口没有查重结果时，不暗示已经核对', () => {
    for (const candidate of [{ duplicate: { status: 'unavailable' } }, {}]) {
        const output = render([candidate]);
        assert.match(output, /尚未完成历史记录核对/);
        assert.doesNotMatch(output, /该订单记录已上传/);
    }
});

test('切换多商品候选时，保存状态只属于当前商品', () => {
    const candidates = [{ productName: '已保存商品', duplicate: { status: 'saved' } },
        { productName: '新商品', duplicate: { status: 'none' } }];
    const first = render(candidates, 0);
    const second = render(candidates, 1);
    assert.match(first, /<span>保存状态<\/span><strong>已上传/);
    assert.match(second, /<span>保存状态<\/span><strong>待确认/);
    assert.doesNotMatch(second, /该订单记录已上传/);
    assert.match(second, /<td>已上传<\/td>/);
});
