'use strict';

const vm=require('node:vm');
const assert=require('node:assert/strict');
const test=require('node:test');
const {loadHtml,loadScript} = require('./helpers/frontend-source.cjs');
const html = loadHtml();
const script = loadScript();
const bootstrap=script.indexOf("/* frontend-test-bootstrap */");

function harness(){
    const nodes=new Map(),requests=[];
    function get(id){
        if(!nodes.has(id)){
            const classes=new Set();
            nodes.set(id,{value:'',innerHTML:'',textContent:'',dataset:{},disabled:false,checked:false,required:false,
                classList:{add:c=>classes.add(c),remove:c=>classes.delete(c),contains:c=>classes.has(c)},
                scrollIntoView(){},setAttribute(){},appendChild(){}});
        }
        return nodes.get(id);
    }
    const defaults={owner:'tester',purchaseDate:'2026-11-17',productName:'示例沐浴露',normalizedName:'沐浴露',
        price:'19.99',purchaseQuantity:'1',skuQuantity:'780',unit:'ml',sku:'780ml*1瓶',platform:'pdd',
        shopName:'示例店铺',note:'赠品：试用装',sourceText:'原始规格证据'};
    const fields={};
    for(const [name,value] of Object.entries(defaults)){fields[name]={name,value,type:'text'};}
    const checkbox=get('record-confirm-out-of-range');
    Object.assign(checkbox,{name:'confirmOutOfRange',type:'checkbox',disabled:true});
    fields.confirmOutOfRange=checkbox;
    const form=get('record-form');form.elements=Object.values(fields);
    form.reportValidity=()=>!checkbox.required||checkbox.disabled||checkbox.checked;
    const save={dataset:{dryRun:'false'},textContent:'确认录入'},preview={dataset:{dryRun:'true'},textContent:'先试算'};
    const context=vm.createContext({document:{getElementById:get,
        querySelector:selector=>fields[(selector.match(/name="([^"]+)"/)||[])[1]],
        querySelectorAll:selector=>selector==='[data-submit="record"]'?[save,preview]:[]},
        window:{matchMedia:()=>({matches:false})},
        fetch(url,options){return new Promise((resolve,reject)=>requests.push({url,payload:JSON.parse(options.body),
            succeed:data=>resolve({ok:true,text:async()=>JSON.stringify(data)}),reject}));}});
    vm.runInContext(script.slice(0,bootstrap)+`globalThis.ui={submitRecord,buildRecordPayload,invalidateRecordReview,
        updateRecordNormalizedField,fillRecordFormFromCandidate,renderRecord,updateRecordMoreSummary};}());`,context);
    return {ui:context.ui,get,fields,save,preview,requests,checkbox};
}
const trial=(outlier=false)=>({dryRun:true,savedCount:0,reviewCount:outlier?1:0,records:[{productName:'示例沐浴露',
    normalizedName:'沐浴露',price:19.99,quantity:780,unit:'ml',unitPrice:0.025628,decision:outlier?'exclude':'include',
    reviewRequired:outlier,reviewReasons:outlier?['当前单价 0.001000 元/ml 明显低于历史最低 0.020000 元/ml，需要用户确认。']:[]}]});

test('空白表单不带演示数据，默认购买数量为1，更多信息与预览折叠',()=>{
    const form=html.match(/<form id="record-form"[\s\S]*?<\/form>/)[0];
    assert.doesNotMatch(form,/value="(?:780|35\.22|舒肤佳红石榴沐浴露|沐浴露)"/);
    assert.match(form,/id="record-purchase-quantity"[^>]*value="1"/);
    assert.match(form,/<details id="record-more" class="[^"]*">/);
    assert.match(html,/<details id="record-image-preview" class="image-preview hidden">/);
});

test('合计含量乘购买件数，折叠字段仍完整进入提交载荷',()=>{
    const h=harness();h.fields.purchaseQuantity.value='2';
    const record=h.ui.buildRecordPayload(true).records[0];
    assert.equal(record.quantity,1560);assert.equal(record.sku,'780ml*1瓶');
    assert.equal(record.note,'赠品：试用装');assert.equal(record.shopName,'示例店铺');
    assert.equal(record.sourceText,'原始规格证据');assert.equal(record.platform,'pdd');
    assert.equal(record.confirmOutOfRange,undefined);
});

test('重量或体积缺少每件含量时不把购买件数当成kg或ml提交',()=>{
    const h=harness();h.fields.skuQuantity.value='';
    assert.equal(h.ui.buildRecordPayload(true).records[0].quantity,undefined);
    h.fields.unit.value='kg';assert.equal(h.ui.buildRecordPayload(true).records[0].quantity,undefined);
    h.fields.unit.value='件';h.fields.purchaseQuantity.value='3';
    assert.equal(h.ui.buildRecordPayload(true).records[0].quantity,3);
});

test('直接确认先试算，发现异常时零写入，接受后才提交',async()=>{
    const h=harness();const pending=h.ui.submitRecord(h.save);
    assert.equal(h.requests[0].payload.dryRun,true);h.requests[0].succeed(trial(true));await pending;
    assert.equal(h.requests.length,1);assert.equal(h.checkbox.disabled,false);assert.equal(h.checkbox.checked,false);
    await h.ui.submitRecord(h.save);assert.equal(h.requests.length,1);
    h.checkbox.checked=true;const saving=h.ui.submitRecord(h.save);
    assert.equal(h.requests[1].payload.dryRun,false);assert.equal(h.requests[1].payload.records[0].confirmOutOfRange,true);
    h.requests[1].succeed({...trial(),dryRun:false,savedCount:1});await saving;
    assert.equal(h.checkbox.disabled,true);assert.equal(h.checkbox.checked,false);
});

test('正常确认通过预检后保存，试算和保存期间屏蔽重复点击',async()=>{
    const h=harness();const pending=h.ui.submitRecord(h.save);
    await h.ui.submitRecord(h.preview);assert.equal(h.requests.length,1);
    h.requests[0].succeed(trial());await new Promise(setImmediate);
    assert.equal(h.requests.length,2);assert.equal(h.requests[1].payload.dryRun,false);
    assert.equal(h.requests[1].payload.records[0].quantity,780);
    h.requests[1].succeed({...trial(),dryRun:false,savedCount:1});await pending;
    assert.equal(h.save.disabled,false);assert.equal(h.preview.disabled,false);
});

test('异常状态仍可重新试算，变更字段后撤销之前的接受勾选',async()=>{
    const h=harness();let pending=h.ui.submitRecord(h.preview);h.requests[0].succeed(trial(true));await pending;
    pending=h.ui.submitRecord(h.preview);assert.equal(h.requests.length,2);h.requests[1].succeed(trial(true));await pending;
    h.checkbox.checked=true;h.fields.price.value='9.99';h.ui.invalidateRecordReview();
    assert.equal(h.checkbox.checked,false);assert.equal(h.checkbox.disabled,true);
    pending=h.ui.submitRecord(h.save);assert.equal(h.requests[2].payload.dryRun,true);
    assert.equal(h.requests[2].payload.records[0].confirmOutOfRange,false);
    h.requests[2].succeed(trial(true));await pending;
});

test('等待预检时表单被修改不能继续保存，失败保留字段并解锁按钮',async()=>{
    const h=harness();const pending=h.ui.submitRecord(h.save);h.fields.price.value='9.99';
    h.requests[0].succeed(trial());await pending;
    assert.equal(h.requests.length,1);assert.equal(h.fields.price.value,'9.99');
    assert.equal(h.fields.note.value,'赠品：试用装');assert.equal(h.save.disabled,false);
    assert.match(h.get('record-result').innerHTML,/商品信息已修改/);
});

test('标准商品选中时隐藏重复输入但保留值，手动选择时显示原名称',()=>{
    const h=harness();h.get('record-normalized-rule-select').value='body_wash';h.ui.updateRecordNormalizedField();
    assert.equal(h.get('record-normalized-name-field').classList.contains('hidden'),true);
    assert.equal(h.fields.normalizedName.value,'沐浴露');
    h.get('record-normalized-rule-select').value='';h.ui.updateRecordNormalizedField();
    assert.equal(h.get('record-normalized-name-field').classList.contains('hidden'),false);
});

test('截图缺失数量不套用默认1，赠品备注和原始SKU保留',()=>{
    const h=harness();h.ui.fillRecordFormFromCandidate({productName:'截图商品',quantity:780,unit:'ml',price:19.99,
        sku:'780ml*1瓶',owner:'tester',purchaseDate:'2026-11-17',platform:'pdd',giftDetails:'试用装1份'});
    assert.equal(h.fields.purchaseQuantity.value,'');assert.equal(h.fields.skuQuantity.value,'780');
    assert.equal(h.fields.sku.value,'780ml*1瓶');assert.match(h.fields.note.value,/试用装1份/);
    assert.equal(h.get('record-name-editor').open,false);
});

test('试算结果明确未入库，复核原因始终可见，记录号收进明细',()=>{
    const h=harness();h.ui.renderRecord(trial(true));const result=h.get('record-result').innerHTML;
    assert.match(result,/本次仅试算，尚未录入/);assert.match(result,/明显低于历史最低/);
    assert.ok(result.indexOf('记录编号')>result.indexOf('<summary>处理明细'));
    assert.match(result,/0\.0256/);
    assert.doesNotMatch(result,/<strong>0\.03<\/strong>/);
});
