'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const vm = require('node:vm');
const {loadHtml,loadScript} = require('./helpers/frontend-source.cjs');
const html = loadHtml();
const script = loadScript();
const bootstrap = script.indexOf("/* frontend-test-bootstrap */");

function harness() {
    const nodes = new Map();
    const get = id => {
        if (!nodes.has(id)) nodes.set(id, { innerHTML: '', elements: [] });
        return nodes.get(id);
    };
    get('compare-form').elements = Object.entries({productName:'猫粮', price:'106', skuQuantity:'4.5', quantity:'1', unit:'kg'})
        .map(([name, value]) => ({name, value}));
    const context = vm.createContext({document:{getElementById:get}});
    vm.runInContext(script.slice(0, bootstrap) + 'globalThis.ui={renderCompare,compactUnitPrice,updateModuleHelp};}());', context);
    return {get, ui:context.ui};
}

const warning = '存在 37 条历史记录单位不是 kg，已排除，不参与本次价格判断。';
const fixture = () => ({
    mode:'compare', current:{unitPrice:23.5556, quantity:4.5, price:106, unitPriceUnit:'kg'},
    baseline:{historicalMin:40.35,historicalMedian:60.4867,historicalAverage:60.7133,sampleSize:13,unit:'kg'},
    decision:{code:'good_price',reason:'低于历史最低价。 '+warning},warnings:[warning,warning],
    evidence:{sourceRecords:[{recordId:719,role:'historical_min',purchaseDate:'2025-10-17',owner:'JTXW',
        productName:'长商品名称',price:106,quantity:4.5,unit:'kg',unitPrice:23.5556}]}
});

test('五个模块切换时，展开的说明栏移到对应结果前且不清空原结果', () => {
    const h=harness();
    for (const name of ['compare','search','record','normalization','import']) {
        const result=h.get(name+'-result');
        result.innerHTML='原有结果';
        let moved=false;
        result.parentElement={insertBefore(panel,before){
            assert.equal(panel,h.get('module-help-panel'));
            assert.equal(before,result);
            moved=true;
        }};
        h.ui.updateModuleHelp(name);
        assert.equal(moved,true);
        assert.equal(result.innerHTML,'原有结果');
        assert.ok(h.get('module-description').textContent);
        assert.ok(h.get('module-tip-3').textContent);
    }
    assert.match(html,/<section id="module-help-panel"/);
    assert.doesNotMatch(html,/<details class="module-help"/);
    assert.match(html,/<span class="compare-name-edit">修改<\/span>/);
});

test('核心结果与折叠明细分开，重复警告只展示一次', () => {
    const h=harness();h.ui.renderCompare(fixture());
    const result=h.get('compare-result').innerHTML;
    assert.equal(result.split(warning).length-1,1);
    assert.match(result,/基于 13 条可比历史记录/);
    assert.match(result,/23\.56/);
    assert.match(result,/<details class="compare-details"><summary>计算明细与判断依据/);
    const details=result.indexOf('计算明细与判断依据');
    assert.ok(result.indexOf('历史均价')>details);
    assert.ok(result.indexOf('购买数量')>details);
});

test('手机参考卡片默认折叠，电脑表格归属人在最后，记录号只进详情', () => {
    const h=harness();h.ui.renderCompare(fixture());
    const result=h.get('compare-result').innerHTML;
    assert.match(result,/<details class="compare-details compare-reference-mobile"><summary>参考记录（1 条）/);
    assert.match(result,/<th>购买日期<\/th><th>归属人<\/th>/);
    assert.doesNotMatch(result,/<th>记录/);
    assert.match(result,/归属人：JTXW/);
    assert.match(result,/记录号：719/);
});

test('小额单价不会显示为零，当前零元价格不会被表单价格替代', () => {
    const h=harness();assert.equal(h.ui.compactUnitPrice(0.0013),'0.0013');
    const data=fixture();data.current.price=0;h.ui.renderCompare(data);
    assert.match(h.get('compare-result').innerHTML,/<dt>购买价格<\/dt><dd>0\.00 元/);
});

test('响应使用提交时的条件，完整商品名转义后可在详情查看', () => {
    const h=harness();const data=fixture();data.evidence.sourceRecords[0].productName='<img src=x onerror=alert(1)>';
    h.ui.renderCompare(data,{productName:'提交时的猫粮',price:'106',skuQuantity:'4.5',quantity:'1',unit:'kg'});
    const result=h.get('compare-result').innerHTML;
    assert.match(result,/提交时的猫粮/);
    assert.doesNotMatch(result,/<img src=x/);
    assert.match(result,/&lt;img/);
});

test('无基线时保留缺样本提示，仅查历史时不展示当前单价卡', () => {
    const h=harness();h.ui.renderCompare({mode:'baseline_only',baseline:{sampleSize:0,unit:'kg'},warnings:['样本不足，请核对单位。']});
    const result=h.get('compare-result').innerHTML;
    assert.match(result,/暂无可比历史记录/);
    assert.match(result,/样本不足，请核对单位/);
    assert.doesNotMatch(result,/compare-current/);
});
