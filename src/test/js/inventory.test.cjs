const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {loadHtml,loadInventoryScript} = require('./helpers/frontend-source.cjs');
const html = loadHtml();
const source = loadInventoryScript();
const flush = () => new Promise(resolve => setImmediate(resolve));
// 库存预估虽使用POST，但仅查询；写入安全断言单独排除该只读接口。
const inventoryWrites = app => app.calls.filter(call => call.body && !call.url.endsWith('/initialization/estimate'));
function setup(handler, options = {}) {
    const ids = [...html.matchAll(/id="(inventory-[^"]+)"/g)].map(match => match[1]);
    const nodes = Object.fromEntries(ids.map(id => [id,{value:'',innerHTML:'',textContent:'',hidden:false,checked:false,disabled:false,
        attributes:{},setAttribute(name,value){this.attributes[name]=value;},addEventListener(name,fn){this[name]=fn;}}]));
    nodes['inventory-detail'].hidden = true;
    const tab = {addEventListener(name,fn){this[name]=fn;}};
    let key = 0;
    const calls = [];
    const row = {id:1, normalized_product_id:2,normalized_name:'猫粮',estimated_quantity:10000,base_unit:'g',effective_source:'AUTO',auto_sample_count:0,purchase_record_count:0,sample_label:'数据不足',reminder_lead_days:3,status:'暂无有效消耗速度'};
    const context = {Date:options.Date || Date, document:{getElementById(id){assert.ok(nodes[id], 'binding exists: '+id);return nodes[id];},querySelector(){return tab;}},crypto:{randomUUID:()=> 'test-key-'+(++key)},fetch:async(url,options)=>{
        const body = options && options.body ? JSON.parse(options.body) : undefined; calls.push({url,body});
        const override = handler ? await handler(url,body) : undefined;
        if (override !== undefined) return {ok:true,json:async()=>override};
        return {ok:true,json:async()=> url.endsWith('/initialization/estimate')?{products:[]}:url.endsWith('/products')?[{id:2,normalized_name:'<script>bad</script>'}]:url.endsWith('/purchases')?[]:url==='/api/inventory'&&!body?[]:row};
    }};
    vm.runInNewContext(source,context);
    return {nodes,tab,calls,row};
}
test('depletion dates default to earliest first, toggle locally and keep missing dates last across refreshes',async()=>{
    const rows=[
        {id:1,normalized_name:'晚',depletion_date:'2027-03-01'},
        {id:2,normalized_name:'未知',depletion_date:null},
        {id:3,normalized_name:'早',depletion_date:'2026-12-01'},
        {id:4,normalized_name:'同日',depletion_date:'2026-12-01'},
        {id:5,normalized_name:'待估算',depletion_date:'2026-01-01',estimate_pending:true},
        {id:6,normalized_name:'无效',depletion_date:'invalid'}
    ];
    const app=setup(url=>url==='/api/inventory'?rows:undefined);
    const names=()=>[...app.nodes['inventory-list'].innerHTML.matchAll(/<tr><td>([^<]*)<\/td>/g)].map(match=>match[1]);
    app.tab.click(); await flush();
    assert.deepEqual(names(),['早','同日','晚','未知','待估算','无效']);
    assert.match(app.nodes['inventory-list'].innerHTML,/aria-sort="ascending"/);
    assert.equal(app.nodes['inventory-sort-order'].value,'asc');
    app.nodes['inventory-quantity'].value='9.55';
    const count=app.calls.length;
    const toggle=()=>app.nodes['inventory-list'].onclick({target:{closest:selector=>selector==='[data-inventory-sort]'?{dataset:{inventorySort:'depletion'}}:null}});
    toggle();
    assert.deepEqual(names(),['晚','早','同日','未知','待估算','无效']);
    assert.equal(app.nodes['inventory-sort-order'].value,'desc');
    assert.equal(app.calls.length,count);
    assert.equal(app.nodes['inventory-quantity'].value,'9.55');
    app.tab.click(); await flush();
    assert.match(app.nodes['inventory-list'].innerHTML,/aria-sort="descending"/);
    assert.deepEqual(names(),['晚','早','同日','未知','待估算','无效']);
    toggle();
    assert.deepEqual(names(),['早','同日','晚','未知','待估算','无效']);
    assert.deepEqual(rows.map(row=>row.id),[1,2,3,4,5,6]);
    app.nodes['inventory-sort-order'].value='desc';
    app.nodes['inventory-sort-order'].onchange();
    assert.deepEqual(names(),['晚','早','同日','未知','待估算','无效']);
    assert.match(app.nodes['inventory-list'].innerHTML,/aria-sort="descending"/);
    assert.equal(app.calls.filter(call=>call.body).length,0);
});

test('inventory bindings exist and product text is escaped',async()=>{
    const app=setup(); app.tab.click(); await flush();
    assert.match(app.nodes['inventory-product'].innerHTML,/&lt;script&gt;/, app.nodes['inventory-message'].textContent);
    assert.doesNotMatch(app.nodes['inventory-product'].innerHTML,/<script>/);
    assert.match(app.nodes['inventory-list'].innerHTML,/暂无库存记录/);
    assert.equal(app.nodes['inventory-list'].attributes['data-empty'],'true');
});
test('exhausted stock shows a red replenishment tag in the product cell without a status column',async()=>{
    const rows=[0,'0.000','0E-12'].map((quantity,index)=>({id:index+1,normalized_name:'猫粮 <试用>',estimated_quantity:quantity,
        base_unit:'kg',effective_source:'AUTO',sample_label:'数据不足',status:index===0?'立即补货':index===1?'暂无有效消耗速度':'已延后提醒'}));
    const app=setup(url=>url==='/api/inventory'?rows:undefined);
    app.tab.click(); await flush();
    const rendered=app.nodes['inventory-list'].innerHTML;
    const headings=[...rendered.matchAll(/<th(?: [^>]*)?>(.*?)<\/th>/g)].map(match=>match[1].replace(/<[^>]*>/g,'').replace(/[ ↑↓]/g,''));
    assert.deepEqual(headings,['商品','预计当前库存','采用方式','剩余完整天数','耗尽日期','提醒日期','样本','操作']);
    const productCells=rendered.match(/<tr><td>[\s\S]*?<\/td>/g);
    assert.equal(productCells.length,3);
    for(const cell of productCells) {
        assert.match(cell,/猫粮 &lt;试用&gt;<span class="inventory-replenishment-tag inventory-replenishment-empty"/);
        assert.match(cell,/title="库存已耗尽，请补货" aria-label="库存已耗尽，请补货">补货<\/span>/);
    }
});

test('positive stock in the reminder period stays yellow even with zero complete days or a rounded display quantity',async()=>{
    const rows=[
        {id:1,estimated_quantity:2.7,status:'即将进入补货周期',remaining_days:5},
        {id:2,estimated_quantity:'0.1',status:'立即补货',remaining_days:0},
        {id:3,estimated_quantity:'0.000000000001',display_estimated_quantity:'0',status:'立即补货',remaining_days:0}
    ].map(row=>({normalized_name:'猫砂',base_unit:'kg',unit_compatible:true,...row}));
    const app=setup(url=>url==='/api/inventory'?rows:undefined);
    app.tab.click(); await flush();
    const rendered=app.nodes['inventory-list'].innerHTML;
    assert.equal((rendered.match(/inventory-replenishment-low/g)||[]).length,3);
    assert.doesNotMatch(rendered,/inventory-replenishment-empty/);
    assert.match(rendered,/aria-label="即将进入补货周期">补货<\/span>/);
    assert.match(rendered,/aria-label="立即补货">补货<\/span>/);
});

test('normal, snoozed, unknown and incompatible stock do not fabricate replenishment tags',async()=>{
    const rows=[
        {estimated_quantity:10,status:'暂时不需要'},
        {estimated_quantity:1,status:'已延后提醒'},
        {estimated_quantity:1,status:'暂无有效消耗速度'},
        {estimated_quantity:null,status:'库存未知'},
        {estimated_quantity:'',status:'立即补货'},
        {estimated_quantity:undefined,status:'立即补货'},
        {estimated_quantity:2,status:'单位不兼容，请确认商品规格',unit_compatible:false},
        {estimated_quantity:2,status:'立即补货',unit_compatible:false}
    ].map((row,index)=>({id:index+1,normalized_name:'商品'+index,base_unit:'kg',...row}));
    const app=setup(url=>url==='/api/inventory'?rows:undefined);
    app.tab.click(); await flush();
    assert.doesNotMatch(app.nodes['inventory-list'].innerHTML,/inventory-replenishment-tag|<th>补货状态<\/th>/);
    assert.match(app.nodes['inventory-list'].innerHTML,/商品7/);
});

test('refresh replaces the product tag as stock moves from low to empty and replenished',async()=>{
    let row={id:1,normalized_name:'猫粮',base_unit:'kg',estimated_quantity:1,status:'立即补货'};
    const app=setup(url=>url==='/api/inventory'?[row]:undefined);
    app.tab.click(); await flush();
    assert.match(app.nodes['inventory-list'].innerHTML,/inventory-replenishment-low/);
    row={...row,estimated_quantity:0,status:'已超过预计耗尽日期'};
    app.tab.click(); await flush();
    assert.match(app.nodes['inventory-list'].innerHTML,/inventory-replenishment-empty/);
    assert.doesNotMatch(app.nodes['inventory-list'].innerHTML,/inventory-replenishment-low/);
    row={...row,estimated_quantity:10,status:'暂时不需要'};
    app.tab.click(); await flush();
    assert.doesNotMatch(app.nodes['inventory-list'].innerHTML,/inventory-replenishment-tag/);
    assert.equal(inventoryWrites(app).length,0);
});

test('failed create retains idempotency key; successful retry sends no price fields',async()=>{
    let failed=false;
    const app=setup(async(url,body)=>{if(body&&!failed){failed=true;throw new Error('network interrupted');}});
    app.nodes['inventory-product'].value='2'; app.nodes['inventory-create-quantity'].value='10'; app.nodes['inventory-create-unit'].value='kg';
    app.nodes['inventory-create-form'].onsubmit({preventDefault(){}}); await flush();
    assert.match(app.nodes['inventory-message'].textContent,/network interrupted/);
    assert.equal(app.nodes['inventory-message'].hidden,false);
    app.nodes['inventory-create-form'].onsubmit({preventDefault(){}}); await flush();
    const writes=inventoryWrites(app);
    assert.equal(writes.length,2); assert.equal(writes[0].body.requestKey,writes[1].body.requestKey);
    assert.deepEqual(Object.keys(writes[1].body).sort(),['normalizedProductId','note','quantity','receiptDate','requestKey','specification','unit']);
    assert.equal(app.nodes['inventory-detail'].hidden,false);
});
test('per-product settings submit original quantity and days rather than a user-calculated rate',async()=>{
    const app=setup(); app.nodes['inventory-product'].value='2'; app.nodes['inventory-create-quantity'].value='10'; app.nodes['inventory-create-unit'].value='kg';
    app.nodes['inventory-create-form'].onsubmit({preventDefault(){}}); await flush();
    for(const [id,value] of Object.entries({'source':'MANUAL','reference-quantity':'4.5','reference-unit':'kg','reference-days':'14','lead':'3'})) app.nodes['inventory-'+id].value=value;
    app.nodes['inventory-settings-form'].onsubmit({preventDefault(){}}); await flush();
    assert.equal(app.nodes['inventory-message'].textContent,'');
    assert.equal(app.nodes['inventory-message'].hidden,true);
    const settings=app.calls.find(call=>call.url.endsWith('/1/settings'));
    assert.equal(settings.body.referenceQuantity,'4.5'); assert.equal(settings.body.referenceDays,'14'); assert.equal(settings.body.source,'MANUAL');
    assert.equal(settings.body.leadDays,3); assert.equal(settings.body.dailyRate,undefined);
});

test('manual receipt sends optional metadata and page has no historical purchase selector',async()=>{
    const app=setup();
    app.nodes['inventory-product'].value='2'; app.nodes['inventory-create-quantity'].value='2'; app.nodes['inventory-create-unit'].value='袋';
    app.nodes['inventory-create-specification'].value='4.5kg/袋'; app.nodes['inventory-create-date'].value='2026-11-01'; app.nodes['inventory-create-note'].value='补货';
    app.nodes['inventory-create-form'].onsubmit({preventDefault(){}}); await flush();
    const request=inventoryWrites(app)[0];
    assert.equal(request.body.specification,'4.5kg/袋'); assert.equal(request.body.receiptDate,'2026-11-01'); assert.equal(request.body.note,'补货');
    assert.equal(request.body.purchaseRecordId,undefined);
    assert.ok(!app.calls.some(call=>call.url.endsWith('/purchases')));
    assert.doesNotMatch(html,/id="inventory-purchases"/);
});

test('consumption inputs follow each product mode and toggling preserves drafts without saving',async()=>{
    const products={
        1:{id:1,normalized_name:'猫粮',base_unit:'kg',effective_source:'AUTO',manual_reference_quantity:'4.5',manual_reference_unit:'kg',manual_reference_days:'14'},
        2:{id:2,normalized_name:'猫砂',base_unit:'kg',effective_source:'MANUAL',manual_reference_quantity:'2',manual_reference_unit:'kg',manual_reference_days:'10'}
    };
    const app=setup(url=>products[url.split('/').pop()]);
    const openDetail=async id=>{
        app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:String(id)}})}});
        await flush();
    };
    await openDetail(1);
    assert.equal(app.nodes['inventory-manual'],undefined);
    assert.equal(app.nodes['inventory-manual-fields'].hidden,true);
    for(const id of ['reference-quantity','reference-unit','reference-days']) {
        assert.equal(app.nodes['inventory-'+id].disabled,true);
        assert.equal(app.nodes['inventory-'+id].required,false);
    }
    app.nodes['inventory-source'].value='MANUAL'; app.nodes['inventory-source'].onchange();
    assert.equal(app.nodes['inventory-manual-fields'].hidden,false);
    for(const id of ['reference-quantity','reference-unit','reference-days']) {
        assert.equal(app.nodes['inventory-'+id].disabled,false);
        assert.equal(app.nodes['inventory-'+id].required,true);
    }
    app.nodes['inventory-reference-quantity'].value='6.750000000001';
    app.nodes['inventory-reference-days'].value='21';
    app.nodes['inventory-source'].value='AUTO'; app.nodes['inventory-source'].onchange();
    app.nodes['inventory-source'].value='MANUAL'; app.nodes['inventory-source'].onchange();
    assert.equal(app.nodes['inventory-reference-quantity'].value,'6.750000000001');
    assert.equal(app.nodes['inventory-reference-days'].value,'21');
    await openDetail(2);
    assert.equal(app.nodes['inventory-manual-fields'].hidden,false);
    assert.equal(app.nodes['inventory-reference-quantity'].value,'2');
    await openDetail(1);
    assert.equal(app.nodes['inventory-manual-fields'].hidden,true);
    assert.equal(app.nodes['inventory-reference-quantity'].value,'4.5');
    assert.equal(inventoryWrites(app).length,0);
});

test('automatic saves omit hidden drafts while manual saves retain the original parameter precision',async()=>{
    const row={id:1,normalized_name:'猫粮',base_unit:'kg',effective_source:'AUTO',manual_reference_quantity:'4.5',manual_reference_unit:'kg',manual_reference_days:'14',reminder_lead_days:3};
    const app=setup(url=>url.endsWith('/1')?row:undefined);
    app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:'1'}})}}); await flush();
    app.nodes['inventory-reference-quantity'].value='invalid hidden draft';
    app.nodes['inventory-reference-days'].value='';
    app.nodes['inventory-settings-form'].onsubmit({preventDefault(){}}); await flush();
    const automatic=app.calls.find(call=>call.url.endsWith('/1/settings')).body;
    assert.equal(automatic.source,'AUTO');
    assert.equal(automatic.referenceQuantity,null); assert.equal(automatic.referenceUnit,null); assert.equal(automatic.referenceDays,null);
    assert.equal(automatic.leadDays,3);
    assert.equal(app.nodes['inventory-reference-quantity'].value,'4.5');
    app.nodes['inventory-source'].value='MANUAL'; app.nodes['inventory-source'].onchange();
    app.nodes['inventory-reference-quantity'].value='5.123456789123';
    app.nodes['inventory-reference-days'].value='17.25';
    app.nodes['inventory-settings-form'].onsubmit({preventDefault(){}}); await flush();
    const manual=app.calls.filter(call=>call.url.endsWith('/1/settings'))[1].body;
    assert.equal(manual.source,'MANUAL');
    assert.equal(manual.referenceQuantity,'5.123456789123'); assert.equal(manual.referenceUnit,'kg'); assert.equal(manual.referenceDays,'17.25');
    assert.equal(manual.dailyRate,undefined);
});

test('inventory list rounds displayed quantities to two decimals while quantity confirmation keeps full precision',async()=>{
    const row={id:1,normalized_name:'猫粮',base_unit:'g',display_unit:'kg',effective_source:'AUTO',
        estimated_quantity:'14806.451612903228',display_estimated_quantity:'14.806451612903228',status:'暂时不需要'};
    const app=setup(url=>url==='/api/inventory'?[row]:url.endsWith('/1')?row:undefined);
    for(const [quantity,expected] of [['14.806451612903228','14.81'],['1.005','1.01'],['2.675','2.68'],['5','5.00'],['0','0.00'],[null,'—']]) {
        row.display_estimated_quantity=quantity;
        app.tab.click(); await flush();
        assert.ok(app.nodes['inventory-list'].innerHTML.includes('<td>'+expected+' kg</td>'));
    }
    row.display_estimated_quantity='14.806451612903228';
    app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:'1'}})}}); await flush();
    assert.equal(app.nodes['inventory-quantity'].value,'14.81');
    app.nodes['inventory-quantity-form'].onsubmit({preventDefault(){}}); await flush();
    const saved=app.calls.find(call=>call.url.endsWith('/1/set'));
    assert.equal(saved.body.quantity,'14.806451612903228');
    assert.equal(row.estimated_quantity,'14806.451612903228');
});

test('detail rates round half up to two display decimals without displaying calculation timestamps',async()=>{
    const row={id:1,normalized_name:'冻干咖啡',base_unit:'g',effective_source:'AUTO',estimated_quantity:'123.456789123456',
        manual_reference_quantity:'4.500000000001',manual_reference_unit:'g',manual_reference_days:'14',manual_daily_rate:'1.005',
        calculated_at:'2026-11-17T18:04:05.019031700Z'};
    const app=setup(url=>url.endsWith('/1')?row:undefined);
    for(const [rate,expected] of [['3.846153846154','3.85'],['1.005','1.01'],['2.675','2.68'],['0.1','0.10']]) {
        row.auto_daily_rate=rate;
        app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:'1'}})}}); await flush();
        assert.ok(app.nodes['inventory-auto'].innerHTML.includes('>'+expected+' g/天</strong>'));
        assert.doesNotMatch(app.nodes['inventory-auto'].innerHTML,/最近计算|2026-11-17|18:04:05/);
        assert.equal(app.nodes['inventory-quantity'].value,'123.46');
        assert.equal(app.nodes['inventory-reference-quantity'].value,'4.500000000001');
        assert.equal(row.auto_daily_rate,rate);
    }
    row.auto_daily_rate=null;
    app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:'1'}})}}); await flush();
    assert.match(app.nodes['inventory-auto'].innerHTML,/暂无 g\/天/);
    assert.equal(row.calculated_at,'2026-11-17T18:04:05.019031700Z');
});

test('highlighted estimate metrics escape API text without restoring the redundant manual summary',async()=>{
    const row={id:1,normalized_name:'冻干咖啡',base_unit:'g<img src=x>',effective_source:'MANUAL',auto_daily_rate:'3.846153846154',
        purchase_record_count:'4<script>bad()</script>',auto_sample_count:'3<img src=x>',sample_label:'<svg onload="bad()">',
        manual_reference_quantity:'100',manual_reference_unit:'g',manual_reference_days:'26'};
    const app=setup(url=>url.endsWith('/1')?row:undefined);
    app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:'1'}})}}); await flush();
    const estimate=app.nodes['inventory-auto'].innerHTML;
    assert.equal((estimate.match(/<strong\b/g)||[]).length,3);
    assert.match(estimate,/3\.85 g&lt;img src=x&gt;\/天/);
    assert.match(estimate,/4&lt;script&gt;bad\(\)&lt;\/script&gt;/);
    assert.match(estimate,/3&lt;img src=x&gt;/);
    assert.match(estimate,/&lt;svg onload=&quot;bad\(\)&quot;&gt;/);
    assert.doesNotMatch(estimate,/<script|<img|<svg/);
    assert.doesNotMatch(html,/id="inventory-manual"/);
    assert.equal(app.nodes['inventory-manual-fields'].hidden,false);
    assert.equal(app.nodes['inventory-reference-quantity'].value,'100');
    assert.equal(app.nodes['inventory-reference-unit'].value,'g');
    assert.equal(app.nodes['inventory-reference-days'].value,'26');
});

test('detail status tags distinguish urgency and escape product and status text',async()=>{
    const row={id:1,normalized_name:'猫粮 <试用>',base_unit:'kg',effective_source:'AUTO'};
    const app=setup(url=>url.endsWith('/1')?row:undefined);
    for(const [status,quantity,tone] of [
        ['暂时不需要',10,'success'],['即将进入补货周期',2,'warning'],['立即补货',1,'warning'],
        ['立即补货',0,'danger'],['已超过预计耗尽日期',0,'danger'],['库存为零，请补货','0.000','danger'],
        ['库存未知',null,'neutral'],['已延后提醒',2,'neutral'],['<script>test</script>',null,'neutral']
    ]) {
        row.status=status; row.estimated_quantity=quantity;
        app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:'1'}})}}); await flush();
        const title=app.nodes['inventory-detail-title'].innerHTML;
        assert.ok(title.includes('inventory-status-'+tone));
        assert.match(title,/猫粮 &lt;试用&gt;/);
        assert.match(title,/class="inventory-status-icon" aria-hidden="true"/);
        assert.doesNotMatch(title,/<script>| · /);
        assert.equal(row.status,status);
    }
    assert.match(app.nodes['inventory-detail-title'].innerHTML,/&lt;script&gt;test&lt;\/script&gt;/);
});

test('incompatible details default to collapsed, retain expansion on refresh and reset for another product',async()=>{
    const row={id:1,normalized_name:'猫粮',base_unit:'kg',effective_source:'AUTO',warnings:'',unit_warning:''};
    const other={id:2,normalized_name:'猫砂',base_unit:'kg',effective_source:'AUTO',warnings:'明细8：缺少明确规格'};
    const app=setup(url=>url.endsWith('/1')?row:url.endsWith('/2')?other:undefined);
    const openDetail=async(id=1)=>{
        app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:String(id)}})}});
        await flush();
    };
    await openDetail();
    assert.equal(app.nodes['inventory-warning'].hidden,true);
    assert.equal(app.nodes['inventory-warning'].textContent,'');
    assert.equal(app.nodes['inventory-warning-details'].hidden,true);
    row.warnings='明细7：购买数量无效'; row.unit_warning='库存单位与商品规则不兼容';
    await openDetail();
    assert.equal(app.nodes['inventory-warning'].hidden,false);
    assert.equal(app.nodes['inventory-warning'].textContent,'库存单位与商品规则不兼容；明细7：购买数量无效');
    assert.equal(app.nodes['inventory-warning-details'].hidden,false);
    assert.equal(app.nodes['inventory-warning-details'].open,false);
    app.nodes['inventory-warning-details'].open=true;
    await openDetail();
    assert.equal(app.nodes['inventory-warning-details'].open,true);
    await openDetail(2);
    assert.equal(app.nodes['inventory-warning-details'].open,false);
    assert.equal(app.nodes['inventory-warning'].textContent,other.warnings);
    row.warnings=' '; row.unit_warning=null;
    await openDetail();
    assert.equal(app.nodes['inventory-warning'].hidden,true);
    assert.equal(app.nodes['inventory-warning'].textContent,'');
    assert.equal(app.nodes['inventory-warning-details'].hidden,true);
    assert.equal(app.nodes['inventory-warning-details'].open,false);
    assert.doesNotMatch(app.nodes['inventory-warning'].textContent,/未发现被排除|退款\/取消/);
});

test('opening product settings aligns manual receipt selection while preserving user drafts and ordinary refreshes',async()=>{
    const products=[{id:2,normalized_name:'猫粮',standard_unit:'kg'},{id:3,normalized_name:'猫砂',standard_unit:'kg'},
        {id:4,normalized_name:'洗衣液',standard_unit:'L'}];
    const rows={
        11:{id:11,normalized_product_id:3,normalized_name:'猫砂',base_unit:'g',display_unit:'kg',effective_source:'AUTO'},
        12:{id:12,normalized_product_id:4,normalized_name:'洗衣液',base_unit:'ml',display_unit:'L',effective_source:'AUTO'},
        13:{id:13,normalized_product_id:99,normalized_name:'已停用商品',base_unit:'g',effective_source:'AUTO'}
    };
    const app=setup(url=>url.endsWith('/products')?products:rows[url.split('/').pop()]);
    app.tab.click(); await flush();
    app.nodes['inventory-product'].value='2'; app.nodes['inventory-create-unit'].value='袋';
    app.nodes['inventory-create-quantity'].value='8'; app.nodes['inventory-create-note'].value='补充家中库存';
    app.tab.click(); await flush();
    assert.equal(app.nodes['inventory-product'].value,'2');
    assert.equal(app.nodes['inventory-create-unit'].value,'袋');
    const openDetail=async id=>{
        app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:String(id)}})}});
        await flush();
    };
    await openDetail(11);
    assert.equal(app.nodes['inventory-product'].value,'3');
    assert.equal(app.nodes['inventory-create-unit'].value,'kg');
    assert.equal(app.nodes['inventory-create-quantity'].value,'8');
    assert.equal(app.nodes['inventory-create-note'].value,'补充家中库存');
    app.nodes['inventory-create-unit'].value='袋'; app.nodes['inventory-create-unit'].onchange();
    await openDetail(11);
    assert.equal(app.nodes['inventory-create-unit'].value,'袋');
    await openDetail(12);
    assert.equal(app.nodes['inventory-product'].value,'4');
    assert.equal(app.nodes['inventory-create-unit'].value,'L');
    app.nodes['inventory-product'].value='2'; app.nodes['inventory-create-unit'].value='g';
    app.nodes['inventory-lead'].value='3';
    app.nodes['inventory-settings-form'].onsubmit({preventDefault(){}}); await flush();
    assert.equal(app.nodes['inventory-product'].value,'2');
    assert.equal(app.nodes['inventory-create-unit'].value,'g');
    await openDetail(13);
    assert.equal(app.nodes['inventory-product'].value,'2');
    assert.equal(app.nodes['inventory-create-unit'].value,'g');
    assert.equal(app.calls.filter(call=>call.url==='/api/inventory'&&call.body).length,0);
});

// 固定UTC时间落在上海次日，验证默认区间不依赖宿主时区。
const fixedDate = iso => class extends Date {
    constructor(...args) { super(...(args.length ? args : [iso])); }
};
const initializationOptions = {Date:fixedDate('2026-11-17T18:00:00Z')};
const event = {preventDefault(){}};
function initializationPreview() {
    const item = (id,name,category,selectable=true,reason='') => ({purchaseRecordId:id,purchaseDate:'2026-11-01',category,
        normalizedName:name,productName:name,quantity:'4.5',unit:'kg',specification:null,inventoryQuantity:'4500',baseUnit:'g',selectable,reason,linked:reason.includes('已同步')});
    return {startDate:'2025-11-18',endDate:'2026-11-18',records:[
        item(1,'猫粮','宠物用品'),item(2,'猫砂','宠物用品'),item(3,'洗衣液','日用品'),
        item(4,'洗衣液','日用品',false,'已同步库存'),item(5,'猫粮','宠物用品',false,'单位不兼容')
    ]};
}
function toggleInitialization(app,id,checked) {
    const checkbox={checked,dataset:{inventoryInitId:String(id)},closest(){return this;}};
    app.nodes['inventory-init-rows'].onchange({target:checkbox});
    return checkbox;
}
async function initialize(app) {
    app.nodes['inventory-initialize'].onclick();
    await flush();
}

test('inventory navigation is renamed and follows order import',()=>{
    const navigation=html.match(/<nav class="tabs"[\s\S]*?<\/nav>/)[0];
    const targets=[...navigation.matchAll(/data-target="([^"]+)"/g)].map(match=>match[1]);
    assert.deepEqual(targets,['compare','search','record','normalization','import','inventory']);
    assert.match(navigation,/data-target="inventory"[^>]*>🏠 库存复购<\/button>/);
    assert.match(html,/<h2>库存复购<\/h2>/);
});

test('initialization defaults to one Shanghai calendar year including leap-day clamping',()=>{
    const app=setup(undefined,initializationOptions);
    assert.equal(app.nodes['inventory-init-start'].value,'2025-11-18');
    assert.equal(app.nodes['inventory-init-end'].value,'2026-11-18');
    const leap=setup(undefined,{Date:fixedDate('2024-02-28T18:00:00Z')});
    assert.equal(leap.nodes['inventory-init-start'].value,'2023-02-28');
    assert.equal(leap.nodes['inventory-init-end'].value,'2024-02-29');
});

test('initialization button queries only; no record is selected or written by default',async()=>{
    const app=setup(url=>url.includes('/initialization/preview')?initializationPreview():undefined,initializationOptions);
    await initialize(app);
    assert.equal(app.nodes['inventory-init-panel'].hidden,false);
    assert.equal(app.nodes['inventory-initialize'].attributes['aria-expanded'],'true');
    assert.ok(app.calls[0].url.endsWith('startDate=2025-11-18&endDate=2026-11-18'));
    assert.equal(inventoryWrites(app).length,0);
    assert.match(app.nodes['inventory-init-summary'].textContent,/已勾选 0/);
    assert.equal(app.nodes['inventory-init-confirm'].disabled,true);
    assert.doesNotMatch(app.nodes['inventory-init-rows'].innerHTML,/ checked/);
    assert.match(app.nodes['inventory-init-rows'].innerHTML,/disabled/);
});

test('only checked IDs are sent; category and normalized-name filters preserve visible selection counts',async()=>{
    const app=setup((url,body)=>{
        if(url.includes('/initialization/preview')) return initializationPreview();
        if(url.endsWith('/initialization/confirm')) return {synchronizedCount:body.purchaseRecordIds.length,alreadySyncedCount:0};
    },initializationOptions);
    await initialize(app);
    toggleInitialization(app,1,true);
    app.nodes['inventory-init-category'].value='日用品'; app.nodes['inventory-init-category'].onchange();
    app.nodes['inventory-init-name'].value='洗衣液';
    app.nodes['inventory-init-filter-form'].onsubmit(event);
    assert.match(app.nodes['inventory-init-rows'].innerHTML,/洗衣液/);
    assert.doesNotMatch(app.nodes['inventory-init-rows'].innerHTML,/猫粮|猫砂/);
    assert.match(app.nodes['inventory-init-summary'].textContent,/筛选外 1/);
    app.nodes['inventory-init-select-visible'].onclick();
    assert.match(app.nodes['inventory-init-confirm'].textContent,/（2）/);
    app.nodes['inventory-init-confirm'].onclick(); await flush();
    const request=inventoryWrites(app)[0];
    assert.deepEqual(request.body.purchaseRecordIds,[1,3]);
    assert.equal(request.body.startDate,'2025-11-18'); assert.equal(request.body.endDate,'2026-11-18');
    assert.equal(request.body.quantity,undefined);
    assert.match(app.nodes['inventory-message'].textContent,/已同步 2 条/);
    assert.equal(app.nodes['inventory-init-confirm'].disabled,true);
});

test('normalized-name options deduplicate and follow category changes with exact filtering',async()=>{
    const preview=initializationPreview();
    preview.records.push({...preview.records[0],purchaseRecordId:6,normalizedName:'猫粮冻干',productName:'猫粮冻干'});
    const app=setup(url=>url.includes('/initialization/preview')?preview:undefined,initializationOptions);
    await initialize(app);
    assert.equal((app.nodes['inventory-init-name'].innerHTML.match(/value="猫粮"/g)||[]).length,1);
    assert.equal((app.nodes['inventory-init-name'].innerHTML.match(/value="洗衣液"/g)||[]).length,1);
    toggleInitialization(app,1,true);
    app.nodes['inventory-init-category'].value='宠物用品'; app.nodes['inventory-init-category'].onchange();
    assert.doesNotMatch(app.nodes['inventory-init-name'].innerHTML,/洗衣液/);
    app.nodes['inventory-init-name'].value='猫粮';
    app.nodes['inventory-init-filter-form'].onsubmit(event);
    assert.match(app.nodes['inventory-init-rows'].innerHTML,/猫粮/);
    assert.doesNotMatch(app.nodes['inventory-init-rows'].innerHTML,/猫粮冻干|猫砂|洗衣液/);
    app.nodes['inventory-init-category'].value='日用品'; app.nodes['inventory-init-category'].onchange();
    assert.equal(app.nodes['inventory-init-name'].value,'');
    assert.doesNotMatch(app.nodes['inventory-init-name'].innerHTML,/猫粮|猫砂/);
    app.nodes['inventory-init-filter-form'].onsubmit(event);
    assert.match(app.nodes['inventory-init-summary'].textContent,/已勾选 1 条（筛选外 1 条）/);
    app.nodes['inventory-init-name'].value='洗衣液';
    app.nodes['inventory-init-category'].value=''; app.nodes['inventory-init-category'].onchange();
    assert.equal(app.nodes['inventory-init-name'].value,'洗衣液');
    assert.match(app.nodes['inventory-init-name'].innerHTML,/猫粮冻干/);
    assert.equal(inventoryWrites(app).length,0);
});

test('name options escape labels and are rebuilt or cleared for refreshed date ranges',async()=>{
    let empty=false;
    const selectedName='洗衣液 "<b>';
    const app=setup(url=>{
        if(!url.includes('/initialization/preview')) return;
        const range=new URL('http://localhost'+url).searchParams;
        const preview=initializationPreview();
        return {...preview,startDate:range.get('startDate'),endDate:range.get('endDate'),
            records:empty?[]:[...preview.records,{...preview.records[2],purchaseRecordId:6,normalizedName:selectedName}]};
    },initializationOptions);
    await initialize(app);
    assert.match(app.nodes['inventory-init-name'].innerHTML,/value="洗衣液 &quot;&lt;b&gt;"/);
    assert.doesNotMatch(app.nodes['inventory-init-name'].innerHTML,/<b>/);
    app.nodes['inventory-init-name'].value=selectedName;
    app.nodes['inventory-init-query-form'].onsubmit(event); await flush();
    assert.equal(app.nodes['inventory-init-name'].value,selectedName);
    empty=true;
    app.nodes['inventory-init-end'].value='2026-11-17'; app.nodes['inventory-init-end'].oninput();
    assert.equal(app.nodes['inventory-init-name'].value,'');
    assert.equal(app.nodes['inventory-init-name'].innerHTML,'<option value="">全部归一化名称</option>');
    app.nodes['inventory-init-query-form'].onsubmit(event); await flush();
    assert.equal(app.nodes['inventory-init-name'].innerHTML,'<option value="">全部归一化名称</option>');
    assert.match(app.nodes['inventory-init-rows'].innerHTML,/该周期没有购买记录/);
    assert.equal(app.nodes['inventory-init-confirm'].disabled,true);
});

test('reset retries keep their request key and refresh linked purchases without synchronizing them',async()=>{
    let reset=false,attempts=0;
    const row={id:1,normalized_name:'猫粮',base_unit:'g',effective_source:'AUTO',estimated_quantity:10000};
    const app=setup((url,body)=>{
        if(url.includes('/initialization/preview')) {
            const preview=initializationPreview();
            Object.assign(preview.records[0],{linked:!reset,selectable:reset,reason:reset?'':'已同步库存'});
            return preview;
        }
        if(url.endsWith('/1/reset')) {
            reset=true; row.estimated_quantity=0; attempts++;
            if(attempts===1) throw new Error('重置响应中断');
            return row;
        }
        if(url.endsWith('/1')) return row;
    },initializationOptions);
    await initialize(app);
    app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:'1'}})}}); await flush();
    assert.equal(toggleInitialization(app,1,true).checked,false);
    app.nodes['inventory-reset'].onclick(); await flush();
    assert.match(app.nodes['inventory-message'].textContent,/重置响应中断/);
    app.nodes['inventory-reset'].onclick(); await flush();
    const resets=app.calls.filter(call=>call.url.endsWith('/1/reset'));
    assert.equal(resets.length,2);
    assert.equal(resets[0].body.requestKey,resets[1].body.requestKey);
    assert.deepEqual(Object.keys(resets[1].body),['requestKey']);
    assert.match(app.nodes['inventory-message'].textContent,/库存已重置为0/);
    assert.equal(toggleInitialization(app,1,true).checked,true);
    assert.equal(toggleInitialization(app,4,true).checked,false);
    assert.equal(app.calls.filter(call=>call.url.endsWith('/initialization/confirm')).length,0);
    assert.equal(app.calls.filter(call=>call.url.includes('/initialization/preview')).length,2);
});

test('resetting another product replaces the old category and name filter within the same period',async()=>{
    let reset=false;
    const row={id:1,normalized_name:'猫粮',base_unit:'g',effective_source:'AUTO',estimated_quantity:4500};
    const app=setup(url=>{
        if(url.includes('/initialization/preview')) {
            const preview=initializationPreview();
            Object.assign(preview.records[0],{linked:!reset,selectable:reset,reason:reset?'':'已同步库存'});
            return preview;
        }
        if(url.endsWith('/1/reset')) {reset=true; row.estimated_quantity=0; return row;}
        if(url.endsWith('/1')) return row;
    },initializationOptions);
    await initialize(app);
    app.nodes['inventory-init-category'].value='日用品'; app.nodes['inventory-init-category'].onchange();
    app.nodes['inventory-init-name'].value='洗衣液'; app.nodes['inventory-init-filter-form'].onsubmit(event);
    assert.doesNotMatch(app.nodes['inventory-init-rows'].innerHTML,/猫粮/);
    app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:'1'}})}}); await flush();
    app.nodes['inventory-reset'].onclick(); await flush();
    assert.equal(app.nodes['inventory-init-category'].value,'');
    assert.equal(app.nodes['inventory-init-name'].value,'猫粮');
    assert.match(app.nodes['inventory-init-rows'].innerHTML,/猫粮/);
    assert.doesNotMatch(app.nodes['inventory-init-rows'].innerHTML,/洗衣液|猫砂/);
    assert.equal(toggleInitialization(app,1,true).checked,true);
    const previews=app.calls.filter(call=>call.url.includes('/initialization/preview'));
    assert.equal(previews.length,2); assert.equal(previews[0].url,previews[1].url);
    assert.match(app.nodes['inventory-message'].textContent,/猫粮库存已重置为0/);
});

test('opening synchronization after a reset keeps the reset product even when its period has no purchases',async()=>{
    const row={id:1,normalized_name:'纸巾',base_unit:'抽',effective_source:'AUTO',estimated_quantity:0};
    const app=setup(url=>{
        if(url.includes('/initialization/preview')) return initializationPreview();
        if(url.endsWith('/1/reset')||url.endsWith('/1')) return row;
    },initializationOptions);
    app.nodes['inventory-init-panel'].hidden=true;
    app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:'1'}})}}); await flush();
    app.nodes['inventory-reset'].onclick(); await flush();
    assert.equal(app.calls.filter(call=>call.url.includes('/initialization/preview')).length,0);
    await initialize(app);
    assert.equal(app.nodes['inventory-init-name'].value,'纸巾');
    assert.match(app.nodes['inventory-init-name'].innerHTML,/value="纸巾"/);
    assert.match(app.nodes['inventory-init-summary'].textContent,/显示 0 \/ 5 条/);
    assert.match(app.nodes['inventory-init-rows'].innerHTML,/没有符合筛选条件/);
    assert.doesNotMatch(app.nodes['inventory-init-rows'].innerHTML,/洗衣液|猫粮/);
    assert.equal(app.nodes['inventory-init-confirm'].disabled,true);
});

test('a reset starts a new explicit synchronization request after an earlier response was lost',async()=>{
    let synchronized=false,confirmationAttempts=0;
    const row={id:1,normalized_name:'猫粮',base_unit:'g',effective_source:'AUTO',estimated_quantity:4500};
    const app=setup((url,body)=>{
        if(url.includes('/initialization/preview')) {
            const preview=initializationPreview();
            Object.assign(preview.records[0],{linked:synchronized,selectable:!synchronized,reason:synchronized?'已同步库存':''});
            return preview;
        }
        if(url.endsWith('/initialization/confirm')) {
            synchronized=true; confirmationAttempts++; row.estimated_quantity=4500;
            if(confirmationAttempts===1) throw new Error('同步响应丢失');
            return {synchronizedCount:1,alreadySyncedCount:0};
        }
        if(url.endsWith('/1/reset')) {synchronized=false; row.estimated_quantity=0; return row;}
        if(url.endsWith('/1')) return row;
    },initializationOptions);
    await initialize(app); toggleInitialization(app,1,true);
    app.nodes['inventory-init-confirm'].onclick(); await flush();
    app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:'1'}})}}); await flush();
    app.nodes['inventory-reset'].onclick(); await flush();
    assert.match(app.nodes['inventory-init-summary'].textContent,/已勾选 0 条/);
    toggleInitialization(app,1,true);
    app.nodes['inventory-init-confirm'].onclick(); await flush();
    const confirmations=app.calls.filter(call=>call.url.endsWith('/initialization/confirm'));
    assert.equal(confirmations.length,2);
    assert.deepEqual(confirmations[0].body.purchaseRecordIds,confirmations[1].body.purchaseRecordIds);
    assert.notEqual(confirmations[0].body.requestKey,confirmations[1].body.requestKey);
    assert.match(app.nodes['inventory-message'].textContent,/已同步 1 条/);
    assert.equal(app.nodes['inventory-quantity'].value,'4500.00');
});

test('a preview refresh failure reports a successful reset and keeps the product for retry',async()=>{
    let reset=false,failed=false;
    const row={id:1,normalized_name:'猫粮',base_unit:'g',effective_source:'AUTO',estimated_quantity:4500};
    const app=setup(url=>{
        if(url.includes('/initialization/preview')) {
            if(reset&&!failed) {failed=true;throw new Error('预览断网');}
            return initializationPreview();
        }
        if(url.endsWith('/1/reset')) {reset=true; row.estimated_quantity=0; return row;}
        if(url.endsWith('/1')) return row;
    },initializationOptions);
    await initialize(app);
    app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:'1'}})}}); await flush();
    app.nodes['inventory-reset'].onclick(); await flush();
    assert.match(app.nodes['inventory-message'].textContent,/库存已重置为0.*预览断网/);
    assert.match(app.nodes['inventory-init-summary'].textContent,/列表刷新失败/);
    assert.equal(app.nodes['inventory-init-confirm'].disabled,true);
    assert.equal(app.nodes['inventory-init-rows'].innerHTML,'');
    app.nodes['inventory-init-query-form'].onsubmit(event); await flush();
    assert.equal(app.nodes['inventory-init-name'].value,'猫粮');
    assert.match(app.nodes['inventory-init-rows'].innerHTML,/猫粮/);
    assert.doesNotMatch(app.nodes['inventory-init-rows'].innerHTML,/洗衣液/);
});

test('disabled and unchecked records cannot reach synchronization; clear removes all choices',async()=>{
    const app=setup(url=>url.includes('/initialization/preview')?initializationPreview():undefined,initializationOptions);
    await initialize(app);
    assert.equal(toggleInitialization(app,4,true).checked,false);
    toggleInitialization(app,1,true); toggleInitialization(app,1,false);
    assert.equal(app.nodes['inventory-init-confirm'].disabled,true);
    app.nodes['inventory-init-select-visible'].onclick();
    assert.match(app.nodes['inventory-init-confirm'].textContent,/（3）/);
    app.nodes['inventory-init-clear'].onclick();
    app.nodes['inventory-init-confirm'].onclick(); await flush();
    assert.equal(inventoryWrites(app).length,0);
    assert.match(app.nodes['inventory-message'].textContent,/至少勾选/);
});

test('changing dates invalidates old preview and requires a new query before confirmation',async()=>{
    const app=setup(url=>url.includes('/initialization/preview')?initializationPreview():undefined,initializationOptions);
    await initialize(app); toggleInitialization(app,1,true);
    app.nodes['inventory-init-end'].value='2026-11-17'; app.nodes['inventory-init-end'].oninput();
    assert.equal(app.nodes['inventory-init-confirm'].disabled,true);
    assert.equal(app.nodes['inventory-init-rows'].innerHTML,'');
    app.nodes['inventory-init-confirm'].onclick(); await flush();
    assert.equal(inventoryWrites(app).length,0);
    assert.match(app.nodes['inventory-message'].textContent,/重新查询/);
});

test('a stale in-flight preview cannot replace a changed date range',async()=>{
    let resolvePreview;
    const app=setup(url=>url.includes('/initialization/preview')?new Promise(resolve=>{resolvePreview=resolve;}):undefined,initializationOptions);
    app.nodes['inventory-initialize'].onclick();
    app.nodes['inventory-init-end'].value='2026-11-17'; app.nodes['inventory-init-end'].oninput();
    resolvePreview(initializationPreview()); await flush();
    assert.equal(app.nodes['inventory-init-rows'].innerHTML,'');
    assert.equal(app.nodes['inventory-init-confirm'].disabled,true);
    assert.match(app.nodes['inventory-message'].textContent,/重新查询/);
});

test('network retry preserves initialization request key and explicit selection',async()=>{
    let failed=false;
    const app=setup((url,body)=>{
        if(url.includes('/initialization/preview')) return initializationPreview();
        if(url.endsWith('/initialization/confirm') && !failed){failed=true;throw new Error('网络中断');}
        if(url.endsWith('/initialization/confirm')) return {synchronizedCount:0,alreadySyncedCount:1};
    },initializationOptions);
    await initialize(app); toggleInitialization(app,1,true);
    app.nodes['inventory-init-confirm'].onclick(); await flush();
    assert.match(app.nodes['inventory-message'].textContent,/网络中断/);
    assert.equal(app.nodes['inventory-init-confirm'].disabled,false);
    app.nodes['inventory-init-confirm'].onclick(); await flush();
    const writes=inventoryWrites(app);
    assert.equal(writes.length,2); assert.deepEqual(writes[0].body,writes[1].body);
    assert.match(app.nodes['inventory-message'].textContent,/已同步跳过 1 条/);
});

test('linked records stay disabled after a date query and never enter select-visible synchronization',async()=>{
    let submitted;
    const app=setup((url,body)=>{
        if(url.includes('/initialization/preview')) {
            const range=new URL('http://localhost'+url).searchParams;
            const preview=initializationPreview();
            preview.startDate=range.get('startDate'); preview.endDate=range.get('endDate');
            // linked是已入库事实；即便其他标志不一致，也不能允许重复选择。
            preview.records.find(row=>row.purchaseRecordId===4).selectable=true;
            return preview;
        }
        if(url.endsWith('/initialization/confirm')) {submitted=body;return {synchronizedCount:3,alreadySyncedCount:0};}
    },initializationOptions);
    await initialize(app);
    app.nodes['inventory-init-start'].value='2026-01-01'; app.nodes['inventory-init-start'].oninput();
    app.nodes['inventory-init-query-form'].onsubmit(event); await flush();
    const linkedRow=app.nodes['inventory-init-rows'].innerHTML.match(/<tr class="inventory-sync-linked">[\s\S]*?<\/tr>/)[0];
    assert.match(linkedRow,/data-inventory-init-id="4" disabled/);
    assert.match(linkedRow,/该记录已经同步至库存中/);
    assert.equal(toggleInitialization(app,4,true).checked,false);
    app.nodes['inventory-init-select-visible'].onclick();
    app.nodes['inventory-init-confirm'].onclick(); await flush();
    assert.deepEqual(submitted.purchaseRecordIds,[1,2,3]);
    assert.equal(submitted.startDate,'2026-01-01');
});

test('a newly synchronized record is grayed out and cannot be checked again after refresh',async()=>{
    let synchronized=false;
    const app=setup(url=>{
        if(url.includes('/initialization/preview')) {
            const preview=initializationPreview();
            if(synchronized) Object.assign(preview.records[0],{linked:true,selectable:false,reason:'已同步库存'});
            return preview;
        }
        if(url.endsWith('/initialization/confirm')) {synchronized=true;return {synchronizedCount:1,alreadySyncedCount:0};}
    },initializationOptions);
    await initialize(app); toggleInitialization(app,1,true);
    app.nodes['inventory-init-confirm'].onclick(); await flush();
    const linkedRows=app.nodes['inventory-init-rows'].innerHTML.match(/<tr class="inventory-sync-linked">[\s\S]*?<\/tr>/g);
    assert.equal(linkedRows.length,2);
    assert.ok(linkedRows.some(row=>row.includes('data-inventory-init-id="1" disabled')&&row.includes('该记录已经同步至库存中')));
    assert.equal(toggleInitialization(app,1,true).checked,false);
    assert.equal(app.nodes['inventory-init-confirm'].disabled,true);
});

test('manual package specification is required only for packaging-to-physical conversion',async()=>{
    const products=[{id:2,normalized_name:'猫粮',standard_unit:'kg'}, {id:3,normalized_name:'纸巾',standard_unit:'抽'}, {id:4,normalized_name:'洗衣液',standard_unit:'L'}];
    const app=setup(url=>url.endsWith('/products')?products:undefined);
    app.tab.click(); await flush();
    app.nodes['inventory-product'].value='2'; app.nodes['inventory-product'].onchange();
    assert.equal(app.nodes['inventory-create-unit'].value,'kg');
    app.nodes['inventory-create-unit'].value='袋'; app.nodes['inventory-create-unit'].onchange();
    assert.equal(app.nodes['inventory-create-specification'].required,true);
    assert.equal(app.nodes['inventory-create-specification-state'].textContent,'必填');
    app.nodes['inventory-create-unit'].value='kg'; app.nodes['inventory-create-unit'].onchange();
    assert.equal(app.nodes['inventory-create-specification'].required,false);
    app.nodes['inventory-product'].value='3'; app.nodes['inventory-create-unit'].value='抽'; app.nodes['inventory-product'].onchange();
    assert.equal(app.nodes['inventory-create-specification'].required,false);
    app.nodes['inventory-product'].value='4'; app.nodes['inventory-product'].onchange();
    app.nodes['inventory-create-unit'].value='瓶'; app.nodes['inventory-create-unit'].onchange();
    assert.equal(app.nodes['inventory-create-specification'].required,true);
    app.nodes['inventory-create-unit'].value='ml'; app.nodes['inventory-create-unit'].onchange();
    assert.equal(app.nodes['inventory-create-specification'].required,false);
});

test('stock, details and edits use the paired standard quantity and rate instead of relabeling raw grams',async()=>{
    const row={id:1,normalized_product_id:2,normalized_name:'猫粮',base_unit:'g',estimated_quantity:1500,auto_daily_rate:'100',manual_daily_rate:'120',
        display_unit:'kg',display_estimated_quantity:'1.5',display_auto_daily_rate:'0.1',display_manual_daily_rate:'0.12',unit_compatible:true,effective_source:'MANUAL'};
    const app=setup((url,body)=>{
        if(url.endsWith('/products')) return [{id:2,normalized_name:'猫粮',standard_unit:'kg',unit_family:'weight'}];
        if(url==='/api/inventory'&&!body) return [row];
        if(url.endsWith('/1')) return row;
    });
    app.tab.click(); await flush();
    assert.match(app.nodes['inventory-list'].innerHTML,/1\.50 kg/);
    assert.doesNotMatch(app.nodes['inventory-list'].innerHTML,/1500 g/);
    app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:'1'}})}}); await flush();
    assert.equal(app.nodes['inventory-quantity'].value,'1.50');
    assert.equal(app.nodes['inventory-unit'].value,'kg');
    assert.match(app.nodes['inventory-auto'].innerHTML,/0\.10 kg\/天/);
    app.nodes['inventory-quantity'].value='2'; app.nodes['inventory-quantity-form'].onsubmit(event); await flush();
    const changed=app.calls.find(call=>call.url.endsWith('/1/set'));
    assert.equal(changed.body.quantity,'2'); assert.equal(changed.body.unit,'kg');
});

test('configured count units appear in inputs and become the selected product default',async()=>{
    const app=setup(url=>url.endsWith('/products')?[
        {id:2,normalized_name:'干电池',standard_unit:'粒',unit_family:'count'},
        {id:3,normalized_name:'粘毛滚筒',standard_unit:'cm*撕',unit_family:'count'},
        {id:4,normalized_name:'安睡裤',standard_unit:'条',unit_family:'count'}]:undefined);
    app.tab.click(); await flush();
    assert.equal(app.nodes['inventory-create-unit'].value,'粒');
    assert.match(app.nodes['inventory-create-unit'].innerHTML,/<option value="cm\*撕">cm\*撕<\/option>/);
    app.nodes['inventory-product'].value='3'; app.nodes['inventory-product'].onchange();
    assert.equal(app.nodes['inventory-create-unit'].value,'cm*撕');
    assert.equal(app.nodes['inventory-create-specification'].required,false);
    assert.match(app.nodes['inventory-reference-unit'].innerHTML,/<option value="条">条<\/option>/);
});

test('synchronization preview displays the standard-unit quantity supplied by the backend',async()=>{
    const app=setup(url=>{
        if(url.includes('/initialization/preview')) {
            const preview=initializationPreview();
            Object.assign(preview.records[0],{displayUnit:'kg',displayInventoryQuantity:'4.5'});
            return preview;
        }
    },initializationOptions);
    await initialize(app);
    const renderedRow=app.nodes['inventory-init-rows'].innerHTML.match(/<tr><td><input[^>]*data-inventory-init-id="1"[\s\S]*?<\/tr>/)[0];
    const cells=renderedRow.match(/<td(?: [^>]*)?>[\s\S]*?<\/td>/g);
    assert.equal(cells[6],'<td>4.5 kg</td>');
});

test('an incompatible legacy unit shows the warning without exposing an unconverted daily rate',async()=>{
    const row={id:1,normalized_name:'猫粮',base_unit:'袋',estimated_quantity:2,auto_daily_rate:'1',display_unit:'袋',
        display_estimated_quantity:'2',display_auto_daily_rate:null,unit_compatible:false,unit_warning:'库存单位与商品规则不兼容'};
    const app=setup(url=>url.endsWith('/1')?row:undefined);
    app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:'1'}})}}); await flush();
    assert.match(app.nodes['inventory-warning'].textContent,/不兼容/);
    assert.match(app.nodes['inventory-auto'].innerHTML,/暂无 袋\/天/);
});

test('selected purchases request a read-only per-product estimate including selections outside filters',async()=>{
    const app=setup((url,body)=>{
        if(url.includes('/initialization/preview')) return initializationPreview();
        if(url.endsWith('/initialization/estimate')) return {products:[
            {normalizedProductId:2,normalizedName:'猫粮',unit:'kg',purchaseQuantity:'9',estimatedQuantity:'3.2',pending:false,reason:''},
            {normalizedProductId:3,normalizedName:'猫砂 <script>',unit:'kg',purchaseQuantity:'4.5',estimatedQuantity:null,pending:true,reason:'样本不足 <b>'}
        ]};
    },initializationOptions);
    await initialize(app);
    assert.equal(app.nodes['inventory-init-estimate'].hidden,true);
    assert.match(app.nodes['inventory-init-rows'].innerHTML,/<th>购买入库量<\/th>/);
    assert.doesNotMatch(app.nodes['inventory-init-rows'].innerHTML,/将增加库存/);
    toggleInitialization(app,2,true); await flush();
    app.nodes['inventory-init-category'].value='日用品'; app.nodes['inventory-init-category'].onchange();
    app.nodes['inventory-init-filter-form'].onsubmit(event);
    app.nodes['inventory-init-select-visible'].onclick(); await flush();
    const estimates=app.calls.filter(call=>call.url.endsWith('/initialization/estimate'));
    assert.equal(estimates.length,2);
    assert.deepEqual(estimates[1].body,{startDate:'2025-11-18',endDate:'2026-11-18',purchaseRecordIds:[2,3]});
    assert.equal(estimates[1].body.requestKey,undefined);
    assert.equal(inventoryWrites(app).length,0);
    const rendered=app.nodes['inventory-init-estimate-rows'].innerHTML;
    assert.match(rendered,/9 kg/); assert.match(rendered,/3\.20 kg/);
    assert.match(rendered,/消耗待设置，暂无法估算当前库存/);
    assert.match(rendered,/猫砂 &lt;script&gt;/); assert.match(rendered,/样本不足 &lt;b&gt;/);
    assert.doesNotMatch(rendered,/<script>|<b>/);
    assert.match(app.nodes['inventory-init-summary'].textContent,/筛选外 1/);
    app.nodes['inventory-init-filter-form'].onsubmit(event); await flush();
    assert.equal(app.calls.filter(call=>call.url.endsWith('/initialization/estimate')).length,2);
});

test('older estimation responses cannot replace a newer selection or reappear after clearing all choices',async()=>{
    const responses=[];
    const app=setup(url=>{
        if(url.includes('/initialization/preview')) return initializationPreview();
        if(url.endsWith('/initialization/estimate')) return new Promise(resolve=>responses.push(resolve));
    },initializationOptions);
    const result=quantity=>({products:[{normalizedProductId:2,normalizedName:'猫粮',unit:'kg',purchaseQuantity:10,estimatedQuantity:quantity,pending:false}]});
    await initialize(app);
    toggleInitialization(app,1,true); toggleInitialization(app,2,true);
    responses[1](result('6.6')); await flush();
    assert.match(app.nodes['inventory-init-estimate-rows'].innerHTML,/6\.60 kg/);
    responses[0](result('999')); await flush();
    assert.doesNotMatch(app.nodes['inventory-init-estimate-rows'].innerHTML,/999/);
    toggleInitialization(app,1,false);
    app.nodes['inventory-init-clear'].onclick();
    responses[2](result('888')); await flush();
    assert.equal(app.nodes['inventory-init-estimate'].hidden,true);
    assert.equal(app.nodes['inventory-init-estimate-rows'].innerHTML,'');
    assert.equal(app.nodes['inventory-init-confirm'].disabled,true);
    assert.equal(inventoryWrites(app).length,0);
});

test('failed estimation clears obsolete quantities and can be retried without writing inventory',async()=>{
    let attempts=0;
    const app=setup(url=>{
        if(url.includes('/initialization/preview')) return initializationPreview();
        if(url.endsWith('/initialization/estimate')) {
            attempts++;
            if(attempts===2) throw new Error('估算断网');
            return {products:[{normalizedProductId:2,normalizedName:'猫粮',unit:'kg',purchaseQuantity:'10',estimatedQuantity:attempts===1?'3.2':'6.6',pending:false}]};
        }
    },initializationOptions);
    await initialize(app);
    toggleInitialization(app,1,true); await flush();
    assert.match(app.nodes['inventory-init-estimate-rows'].innerHTML,/3\.20 kg/);
    toggleInitialization(app,2,true); await flush();
    assert.equal(app.nodes['inventory-init-estimate-rows'].innerHTML,'');
    assert.match(app.nodes['inventory-init-estimate-message'].textContent,/估算失败.*估算断网/);
    assert.equal(app.nodes['inventory-init-estimate-retry'].hidden,false);
    app.nodes['inventory-init-estimate-retry'].onclick(); await flush();
    assert.equal(attempts,3);
    assert.match(app.nodes['inventory-init-estimate-rows'].innerHTML,/6\.60 kg/);
    assert.equal(app.nodes['inventory-init-estimate-retry'].hidden,true);
    assert.equal(inventoryWrites(app).length,0);
});

test('changing the date range invalidates an in-flight estimate and refreshing stock recalculates retained choices',async()=>{
    let resolveEstimate;
    const app=setup(url=>{
        if(url.includes('/initialization/preview')) return initializationPreview();
        if(url.endsWith('/initialization/estimate')) return new Promise(resolve=>{resolveEstimate=resolve;});
    },initializationOptions);
    await initialize(app);
    toggleInitialization(app,1,true);
    resolveEstimate({products:[]}); await flush();
    app.tab.click(); await flush();
    assert.equal(app.calls.filter(call=>call.url.endsWith('/initialization/estimate')).length,2);
    app.nodes['inventory-init-start'].value='2026-01-01'; app.nodes['inventory-init-start'].oninput();
    resolveEstimate({products:[{normalizedName:'过时库存',unit:'kg',estimatedQuantity:999}]}); await flush();
    assert.equal(app.nodes['inventory-init-estimate'].hidden,true);
    assert.equal(app.nodes['inventory-init-estimate-rows'].innerHTML,'');
    assert.equal(app.nodes['inventory-init-confirm'].disabled,true);
});

test('pending history is distinct from zero stock and legacy quantities require explicit reconstruction or confirmation',async()=>{
    const pending={id:1,normalized_name:'猫砂',base_unit:'g',display_unit:'kg',estimated_quantity:0,display_estimated_quantity:0,
        estimate_pending:true,history_rebuild_required:true,effective_source:'AUTO',status:'立即补货',remaining_days:0,depletion_date:'2026-11-01',reminder_date:'2026-10-29'};
    const confirmed={id:2,normalized_name:'猫粮',base_unit:'kg',estimated_quantity:2,effective_source:'MANUAL',status:'暂时不需要'};
    const app=setup(url=>url==='/api/inventory'?[pending]:url.endsWith('/1')?pending:url.endsWith('/2')?confirmed:undefined);
    app.tab.click(); await flush();
    const rendered=app.nodes['inventory-list'].innerHTML;
    assert.match(rendered,/消耗待设置/);
    assert.doesNotMatch(rendered,/0 kg|inventory-replenishment-empty|2026-11-01|2026-10-29/);
    app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:'1'}})}}); await flush();
    assert.equal(app.nodes['inventory-quantity'].value,'');
    assert.match(app.nodes['inventory-detail-title'].innerHTML,/消耗待设置/);
    assert.equal(app.nodes['inventory-timeline-note'].hidden,false);
    assert.match(app.nodes['inventory-timeline-note'].textContent,/旧版本库存已保留/);
    assert.match(app.nodes['inventory-timeline-note'].textContent,/重置库存后重新同步购买记录/);
    assert.match(app.nodes['inventory-timeline-note'].textContent,/消耗待设置，暂无法估算当前库存/);
    app.nodes['inventory-list'].onclick({target:{closest:()=>({dataset:{inventoryId:'2'}})}}); await flush();
    assert.equal(app.nodes['inventory-timeline-note'].hidden,true);
    assert.equal(app.nodes['inventory-timeline-note'].textContent,'');
    assert.equal(app.nodes['inventory-quantity'].value,'2.00');
});
