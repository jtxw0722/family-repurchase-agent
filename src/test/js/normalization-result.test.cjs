'use strict';

// 使用真实页面渲染函数校验规则操作条数与历史样本数量，不访问服务器。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/repurchase-demo/index.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const bootstrap = script.indexOf("        Array.prototype.forEach.call(document.querySelectorAll('.tab')");
assert.ok(bootstrap > 0);
const executable = script.slice(0, bootstrap) + `
globalThis.ui = {
    renderNormalization,
    library: function(items, loaded) { normalizationLibraryItems = items; normalizationLibraryLoaded = loaded; }
};
}());`;

function render(data, items, loaded = true) {
    const nodes = new Map();
    const get = id => {
        if (!nodes.has(id)) nodes.set(id, { innerHTML: '', elements: [] });
        return nodes.get(id);
    };
    const context = vm.createContext({ document: { getElementById: get } });
    vm.runInContext(executable, context);
    context.ui.library(items, loaded);
    context.ui.renderNormalization(data);
    return get('normalization-result').innerHTML;
}

const operation = { action: 'create_rule', ruleCode: 'menstrual_pad', normalizedName: '卫生巾', affectedRows: 1 };

test('新增一条规则与十个历史样本分别展示，样本按规则编码读取', () => {
    const result = render(operation, [
        { ruleCode: 'other_rule', sampleCount: 99 },
        { ruleCode: 'menstrual_pad', sampleCount: 10 }
    ]);
    assert.match(result, /<span>新增规则数<\/span><strong>1<\/strong>/);
    assert.match(result, /<span>样本数量<\/span><strong>10<\/strong>/);
    assert.doesNotMatch(result, /<span>影响数量<\/span>/);
});

test('没有历史样本时明确显示零，不将新增规则数当作样本数', () => {
    const result = render(operation, [{ ruleCode: 'menstrual_pad', sampleCount: 0 }]);
    assert.match(result, /<span>新增规则数<\/span><strong>1<\/strong>/);
    assert.match(result, /<span>样本数量<\/span><strong>0<\/strong>/);
});

test('刷新失败时不使用旧缓存或操作条数冒充最新样本数', () => {
    const result = render(operation, [{ ruleCode: 'menstrual_pad', sampleCount: 3 }], false);
    assert.match(result, /<span>新增规则数<\/span><strong>1<\/strong>/);
    assert.match(result, /<span>样本数量<\/span><strong>—<\/strong>/);
    assert.match(result, /样本数量暂未获取/);
});

test('规则未出现在刷新结果中时显示未获取，不猜测为零', () => {
    const result = render(operation, []);
    assert.match(result, /<span>样本数量<\/span><strong>—<\/strong>/);
});

test('更新规则同样使用明确标签，并保留真实历史样本数量', () => {
    const result = render({ ...operation, action: 'update_rule' }, [{ ruleCode: 'menstrual_pad', sampleCount: 10 }]);
    assert.match(result, /<span>更新规则数<\/span><strong>1<\/strong>/);
    assert.match(result, /<span>样本数量<\/span><strong>10<\/strong>/);
});
