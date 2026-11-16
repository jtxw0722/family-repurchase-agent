'use strict';

// 使用真实页面脚本测试异步表单交互；无需浏览器、额外 npm 依赖或外部模型。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/repurchase-demo/index.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const bootstrapStart = script.indexOf("        Array.prototype.forEach.call(document.querySelectorAll('.tab')");
assert.ok(bootstrapStart > 0, '页面事件绑定入口必须存在');
const testableScript = script.slice(0, bootstrapStart) + `
    globalThis.ui = {
        handleNormalizedRuleNameInput, handleRuleCodeInput, ensureGeneratedRuleCode,
        updateNormalizationFormMode, submit,
        setRules: function (items) { normalizationLibraryItems = items; normalizationLibraryLoaded = true; }
    };
}());`;

function harness() {
    const elements = new Map();
    const timers = new Map();
    const requests = [];
    let timerSequence = 0;
    function element(id) {
        if (!elements.has(id)) elements.set(id, {
            id, value: '', textContent: '', innerHTML: '', disabled: false,
            validationMessage: '', dataset: {}, classList: { toggle() {} },
            setCustomValidity(message) { this.validationMessage = message; },
            reportValidity() { return Boolean(element('normalization-rule-code').value)
                && !element('normalization-rule-code').validationMessage; }
        });
        return elements.get(id);
    }
    element('normalization-action').value = 'create_rule';
    element('normalization-rule-code').value = 'body_wash';
    element('normalized-name-input').value = '沐浴露';
    element('normalization-form').elements = [
        ['normalization-action', 'action'], ['normalization-rule-code', 'ruleCode'],
        ['normalized-name-input', 'normalizedName']
    ].map(([id, name]) => Object.assign(element(id), { name }));
    const context = vm.createContext({
        document: { getElementById: element, querySelectorAll: () => [] },
        setTimeout(callback) { const id = ++timerSequence; timers.set(id, callback); return id; },
        clearTimeout(id) { timers.delete(id); },
        fetch(url, options) {
            return new Promise((resolve, reject) => requests.push({ url, payload: JSON.parse(options.body),
                succeed(data) { resolve({ ok: true, text: async () => JSON.stringify(data) }); }, reject }));
        }
    });
    vm.runInContext(testableScript, context);
    context.ui.setRules([]);
    return { ui: context.ui, element, requests, timers,
        changeName(name) { element('normalized-name-input').value = name; context.ui.handleNormalizedRuleNameInput(); },
        editCode(code) { element('normalization-rule-code').value = code; context.ui.handleRuleCodeInput(); } };
}

test('连续修改名称只在停顿后翻译，并替换未经手动编辑的默认编码', async () => {
    const h = harness();
    h.changeName('猫');
    h.changeName('猫粮');
    assert.equal(h.timers.size, 1);
    const promise = [...h.timers.values()][0]();
    assert.equal(h.requests.length, 1);
    assert.equal(h.requests[0].payload.normalizedName, '猫粮');
    h.requests[0].succeed({ ruleCode: 'cat_food', exists: false });
    await promise;
    assert.equal(h.element('normalization-rule-code').value, 'cat_food');
    assert.equal(h.element('normalization-rule-code').validationMessage, '');
});

test('旧名称的慢响应不能覆盖新名称', async () => {
    const h = harness();
    h.changeName('大米');
    const oldRequest = h.ui.ensureGeneratedRuleCode();
    h.changeName('猫粮');
    const newRequest = h.ui.ensureGeneratedRuleCode();
    h.requests[1].succeed({ ruleCode: 'cat_food', exists: false });
    await newRequest;
    h.requests[0].succeed({ ruleCode: 'rice', exists: false });
    await oldRequest;
    assert.equal(h.element('normalization-rule-code').value, 'cat_food');
});

test('用户手动修改后，进行中的翻译和后续名称修改均保留手动编码', async () => {
    const h = harness();
    h.changeName('大米');
    const pending = h.ui.ensureGeneratedRuleCode();
    h.editCode('custom_rice');
    h.requests[0].succeed({ ruleCode: 'rice', exists: false });
    await pending;
    h.changeName('香米');
    await h.ui.ensureGeneratedRuleCode();
    assert.equal(h.element('normalization-rule-code').value, 'custom_rice');
    assert.equal(h.requests.length, 1);
});

test('清空手动编码可恢复自动生成', async () => {
    const h = harness();
    h.editCode('manual');
    h.changeName('大米');
    h.editCode('');
    const pending = h.ui.ensureGeneratedRuleCode();
    h.requests[0].succeed({ ruleCode: 'rice', exists: false });
    await pending;
    assert.equal(h.element('normalization-rule-code').value, 'rice');
});

test('后端发现重复时阻止保存，即使页面规则缓存尚未包含该编码', async () => {
    const h = harness();
    h.changeName('大米');
    const pending = h.ui.ensureGeneratedRuleCode();
    h.requests[0].succeed({ ruleCode: 'rice', exists: true });
    await pending;
    assert.match(h.element('normalization-rule-code').validationMessage, /编码已存在/);
    assert.equal(h.element('normalization-form').reportValidity(), false);
    h.editCode('new_rice');
    assert.equal(h.element('normalization-rule-code').validationMessage, '');
});

test('手动填写的重复编码同样提示，且不会擅自替换', () => {
    const h = harness();
    h.ui.setRules([{ ruleCode: 'rice', normalizedName: '大米' }]);
    h.editCode('rice');
    assert.match(h.element('normalization-rule-code').validationMessage, /编码已存在/);
    assert.equal(h.element('normalization-rule-code').value, 'rice');
});

test('翻译失败使用简洁提示，并允许手动补充', async () => {
    const h = harness();
    h.changeName('大米');
    const pending = h.ui.ensureGeneratedRuleCode();
    h.requests[0].reject(new Error('HTTP 401 provider stack trace'));
    await pending;
    assert.equal(h.element('normalization-rule-code').value, '');
    assert.match(h.element('rule-code-generation-hint').textContent, /请手动填写/);
    assert.doesNotMatch(h.element('rule-code-generation-hint').textContent, /401|provider/);
    h.editCode('rice');
    assert.equal(h.element('normalization-rule-code').validationMessage, '');
});

test('缺少完整离线词条时明确提示手动填写，不建议反复重试', async () => {
    const h = harness();
    h.changeName('未收录名称');
    const pending = h.ui.ensureGeneratedRuleCode();
    const message = '离线词库未收录该完整名称或译义不明确，请手动填写英文编码。';
    h.requests[0].reject(new Error(message));
    await pending;
    assert.equal(h.element('rule-code-generation-hint').textContent, message);
    assert.equal(h.element('normalization-rule-code').validationMessage, message);
    h.editCode('custom_product');
    assert.equal(h.element('normalization-rule-code').validationMessage, '');
});

test('切换更新规则时，待返回的建议不能修改已有规则编码', async () => {
    const h = harness();
    h.changeName('大米');
    const pending = h.ui.ensureGeneratedRuleCode();
    h.ui.setRules([{ ruleCode: 'cat_food', normalizedName: '猫粮', enabled: true }]);
    h.element('normalization-action').value = 'update_rule';
    h.ui.updateNormalizationFormMode();
    h.requests[0].succeed({ ruleCode: 'rice', exists: false });
    await pending;
    assert.equal(h.element('normalization-rule-code').value, 'cat_food');
    assert.equal(h.element('normalization-rule-code').readOnly, true);
    assert.equal(h.element('normalization-rule-code').validationMessage, '');
});

test('点击保存等待同一个翻译请求，不提交过期默认编码', async () => {
    const h = harness();
    h.changeName('大米');
    const first = h.ui.ensureGeneratedRuleCode();
    assert.equal(h.ui.ensureGeneratedRuleCode(), first);
    const button = { dataset: {}, textContent: '保存规则' };
    const saving = h.ui.submit('normalization', button);
    assert.equal(button.disabled, true);
    assert.equal(h.requests.length, 1);
    // 重复编码使表单校验失败；验证不会继续发送规则写入请求。
    h.requests[0].succeed({ ruleCode: 'rice', exists: true });
    await saving;
    assert.equal(button.disabled, false);
    assert.equal(h.requests.length, 1);
});

test('清空名称使旧请求失效，不能写回过期编码', async () => {
    const h = harness();
    h.changeName('大米');
    const pending = h.ui.ensureGeneratedRuleCode();
    h.changeName('');
    h.requests[0].succeed({ ruleCode: 'rice', exists: false });
    await pending;
    assert.equal(h.element('normalization-rule-code').value, '');
    assert.equal(h.timers.size, 0);
});

test('生成成功后，保存请求携带新编码和当前归一化名称', async () => {
    const h = harness();
    h.changeName('大米');
    const saving = h.ui.submit('normalization', { dataset: {}, textContent: '保存规则' });
    h.requests[0].succeed({ ruleCode: 'rice', exists: false });
    await new Promise(setImmediate);
    assert.equal(h.requests.length, 2);
    assert.equal(h.requests[1].url, '/api/tools/normalization-library');
    assert.equal(h.requests[1].payload.ruleCode, 'rice');
    assert.equal(h.requests[1].payload.normalizedName, '大米');
    // 本项只验证提交内容，模拟保存响应失败以避免继续触发无关的列表刷新。
    h.requests[1].reject(new Error('测试保存响应'));
    await saving;
});

test('未编辑的生成结果再次保存时直接复用，不重复调用模型', async () => {
    const h = harness();
    h.changeName('大米');
    const pending = h.ui.ensureGeneratedRuleCode();
    h.requests[0].succeed({ ruleCode: 'rice', exists: false });
    await pending;
    await h.ui.ensureGeneratedRuleCode();
    assert.equal(h.requests.length, 1);
});
