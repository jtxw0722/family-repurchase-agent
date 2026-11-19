'use strict';

// 运行真实页面脚本，覆盖上传元数据隔离、白名单确认、失败重试和预览生命周期；原生 DOM 编辑另有浏览器验证。
const assert = require('node:assert/strict');
const test = require('node:test');
const vm = require('node:vm');
const {loadHtml,loadScript} = require('./helpers/frontend-source.cjs');
const html = loadHtml();
const script = loadScript();
const bootstrap = script.indexOf("/* frontend-test-bootstrap */");
assert.ok(bootstrap > 0);
const executable = script.slice(0, bootstrap) + `
globalThis.ui = {previewOnlineImport,saveOnlineImport,clearOnlineImport,onlineRowInvalid,renderOnlineImport,
 setRows: function(rows){onlineImportRows=rows;}, rows:function(){return onlineImportRows;}};
}());`;

function harness() {
    const nodes = new Map(), requests = [];
    function get(id) {
        if (!nodes.has(id)) nodes.set(id, {value:'',files:[],innerHTML:'',textContent:'',className:'',disabled:false,
            querySelectorAll:()=>[],classList:{toggle(){}}});
        return nodes.get(id);
    }
    const context = vm.createContext({document:{getElementById:get},
        FileReader:class {
            readAsDataURL() { this.result='data:text/csv;base64,U0VDUkVUX0NPTlRFTlQ='; queueMicrotask(()=>this.onload()); }
        },
        fetch(url,options) { return new Promise((resolve,reject)=>requests.push({url,payload:JSON.parse(options.body),
            success(data){resolve({ok:true,text:async()=>JSON.stringify(data)});},reject})); }
    });
    vm.runInContext(executable,context);
    get('online-order-owner').value='tester';
    return {ui:context.ui,get,requests};
}

const row = overrides => ({rowNumber:2,productName:'猫砂',normalizedName:'猫砂',sku:'5kg',amount:40,
    quantity:5,unit:'kg',platform:'taobao',purchaseDate:'2026-11-01 12:00:00',owner:'tester',shopName:'商店',
    selected:true,warnings:[],...overrides});

test('可编辑空字段保持空值，不把展示用破折号当作真实输入',()=>{
    const h=harness();h.ui.setRows([row({normalizedName:'',shopName:'',amount:null,quantity:null})]);
    h.ui.renderOnlineImport();
    assert.doesNotMatch(h.get('import-result').innerHTML,/value="—"/);
    assert.match(h.get('import-result').innerHTML,/data-online-field="normalizedName"[^>]*value=""/);
});

test('内存上传不携带文件名，预览后释放文件选择，且不会自动入库',async()=>{
    const h=harness();
    h.get('online-order-file').files=[{name:'SECRET_NAME.CSV',size:100}];
    const pending=h.ui.previewOnlineImport();
    await new Promise(setImmediate);
    assert.equal(h.requests.length,1);
    assert.deepEqual(h.requests[0].payload,{contentBase64:'U0VDUkVUX0NPTlRFTlQ=',format:'csv',owner:'tester'});
    assert.ok(!JSON.stringify(h.requests[0].payload).includes('SECRET_NAME'));
    h.requests[0].success({rows:[row(),row({rowNumber:3,selected:false,warnings:['赠品']})],skippedRows:2});
    await pending;
    assert.equal(h.ui.rows().length,2);
    assert.equal(h.ui.rows()[1].selected,false);
    assert.equal(h.get('online-order-file').value,'');
    assert.equal(h.requests.length,1);
});

test('确认只发送勾选后的编辑字段，不携带原文或其他额外属性',async()=>{
    const h=harness();
    h.ui.setRows([row({amount:42,sourceText:'PRIVATE',fileName:'SECRET',contentBase64:'RAW'}),row({rowNumber:3,selected:false})]);
    const pending=h.ui.saveOnlineImport();
    assert.equal(h.requests.length,1);
    assert.equal(h.requests[0].payload.length,1);
    assert.equal(h.requests[0].payload[0].amount,42);
    assert.doesNotMatch(JSON.stringify(h.requests[0].payload),/PRIVATE|SECRET|RAW|contentBase64|fileName|sourceText/);
    h.requests[0].success({savedRows:[2],duplicateRows:[]});
    await pending;
    assert.equal(h.ui.rows().length,1);
    assert.equal(h.ui.rows()[0].rowNumber,3);
});

test('确认时发现的新重复项取消勾选，保留核对提示',async()=>{
    const h=harness();h.ui.setRows([row()]);
    const pending=h.ui.saveOnlineImport();
    h.requests[0].success({savedRows:[],duplicateRows:[2]});await pending;
    assert.equal(h.ui.rows()[0].selected,false);
    assert.match(h.ui.rows()[0].warnings[0],/未重复入库/);
});

test('保存失败保留人工修正和勾选，释放按钮后可重试',async()=>{
    const h=harness();h.ui.setRows([row({amount:42})]);
    const pending=h.ui.saveOnlineImport();
    h.requests[0].reject(new Error('synthetic failure'));await pending;
    assert.equal(h.ui.rows()[0].amount,42);
    assert.equal(h.ui.rows()[0].selected,true);
    assert.equal(h.get('online-order-preview').disabled,false);
});

test('无勾选或必要字段不合法时不发送确认请求',async()=>{
    const h=harness();
    for (const rows of [[],[row({selected:false})],[row({amount:-1})],[row({quantity:0})],[row({purchaseDate:''})],[row({normalizedName:''})]]) {
        h.ui.setRows(rows);await h.ui.saveOnlineImport();
    }
    assert.equal(h.requests.length,0);
});

test('不支持、空或超大文件不读取上传，清空后不保留预览',async()=>{
    const h=harness();
    for (const file of [{name:'x.pdf',size:5},{name:'x.csv',size:0},{name:'x.csv',size:10*1024*1024+1}]) {
        h.get('online-order-file').files=[file];await h.ui.previewOnlineImport();
    }
    assert.equal(h.requests.length,0);
    h.ui.setRows([row()]);h.ui.clearOnlineImport();
    assert.equal(h.ui.rows().length,0);
    assert.match(h.get('import-result').innerHTML,/已清空/);
});
