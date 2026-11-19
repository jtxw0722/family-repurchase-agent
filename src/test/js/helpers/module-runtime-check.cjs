'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {pathToFileURL,fileURLToPath} = require('node:url');
const {root,moduleNames} = require('./frontend-source.cjs');

// 使用原生ES模块链接器验证真实依赖、导出和循环依赖，不把生产模块拼接成传统脚本。
async function checkActualGraph() {
    const context = vm.createContext({URL,console});
    const cache = new Map();
    function load(file) {
        file = path.resolve(file);
        if (!cache.has(file)) cache.set(file,new vm.SourceTextModule(fs.readFileSync(file,'utf8'),{
            context,identifier:pathToFileURL(file).href,
            initializeImportMeta(meta,module){meta.url=module.identifier;}
        }));
        return cache.get(file);
    }
    const entry = load(path.join(root,'js/app.js'));
    await entry.link((specifier,parent)=>load(fileURLToPath(new URL(specifier,parent.identifier))));
    await entry.evaluate();
    assert.deepEqual(Array.from(entry.namespace.modules),moduleNames);
    assert.equal(typeof cache.get(path.join(root,'js/inventory.js')).namespace.initInventory,'function');
    assert.equal(typeof cache.get(path.join(root,'js/assistant.js')).namespace.initAssistant,'function');
    assert.equal(typeof cache.get(path.join(root,'js/record-image.js')).namespace.selectRecordImageFile,'function');
}

// 只替换功能初始化边界，执行真实app入口，以覆盖异步HTML挂载先于全部事件绑定的约束。
async function checkBootstrap(failure) {
    const source = fs.readFileSync(path.join(root,'js/app.js'),'utf8');
    const dependencies = new Map();
    for (const match of source.matchAll(/import \{ ([^}]+) \} from "([^"]+)";/g)) {
        const exports = dependencies.get(match[2]) || new Set();
        match[1].split(',').forEach(name=>exports.add(name.trim()));
        dependencies.set(match[2],exports);
    }
    const initialized = [], requests = [], buttons = [{disabled:true},{disabled:true}];
    const target = {innerHTML:'正在加载页面…',children:[],
        removeAttribute(name){assert.equal(name,'aria-busy');this.ready=true;},
        replaceChildren(){this.children=[];},append(...items){this.children.push(...items);}};
    let modalMounted = false;
    const modal = {};
    const document = {
        getElementById(id){return id==='modules-root'?target:modal;},
        body:{appendChild(node){assert.equal(node,modal);modalMounted=true;}},
        querySelectorAll(){return buttons;},
        createElement(){return {setAttribute(){}};}
    };
    const context = vm.createContext({URL,document,window:failure?{location:{reload(){}}}:undefined,
        fetch(url){return new Promise(resolve=>requests.push({url:String(url),resolve}));}});
    const app = new vm.SourceTextModule(source,{context,identifier:pathToFileURL(path.join(root,'js/app.js')).href,
        initializeImportMeta(meta,module){meta.url=module.identifier;}});
    const linked = new Map();
    await app.link(specifier=>{
        if (!linked.has(specifier)) {
            const names=Array.from(dependencies.get(specifier));
            linked.set(specifier,new vm.SyntheticModule(names,function(){
                names.forEach(name=>this.setExport(name,()=>{
                    assert.ok(modalMounted,'预览遮罩必须在事件绑定前挂载');
                    assert.ok(target.innerHTML.includes('模块-inventory'));
                    initialized.push(name);
                }));
            },{context}));
        }
        return linked.get(specifier);
    });
    await app.evaluate();
    const pending=failure?null:app.namespace.bootstrap();
    assert.equal(requests.length,moduleNames.length);
    assert.equal(initialized.length,0);
    requests.forEach((request,index)=>{
        assert.equal(new URL(request.url).pathname.split('/').pop(),moduleNames[index]+'.html');
        request.resolve({ok:!failure || index!==2,text:async()=>'<section>模块-'+moduleNames[index]+'</section>'});
    });
    if (failure) {
        await new Promise(setImmediate);
        assert.equal(initialized.length,0);
        assert.equal(target.children[0].textContent,'页面加载失败，请重试。模块加载失败：record');
        assert.equal(target.children[1].textContent,'重新加载');
        assert.ok(buttons.every(button=>button.disabled));
    } else {
        await pending;
        assert.equal(initialized.filter(name=>name.startsWith('init')).length,8);
        assert.ok(buttons.every(button=>!button.disabled));
        assert.ok(target.ready);
    }
}

(async()=>{
    await checkActualGraph();
    await checkBootstrap(false);
    await checkBootstrap(true);
    process.stdout.write('ES模块链接、初始化时序及失败重试入口：通过\n');
})().catch(error=>{console.error(error);process.exitCode=1;});
