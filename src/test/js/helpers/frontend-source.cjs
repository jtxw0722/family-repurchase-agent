'use strict';

const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../../main/resources/static/repurchase-demo');
const moduleNames = ['compare','purchases','record','normalization','order-import','inventory','assistant'];
const scriptNames = ['config','state','shared','compare','purchases','record-image','record','normalization','order-import','actions','navigation'];
const read = file => fs.readFileSync(path.join(root,file),'utf8');

// 原有DOM桩测试继续运行真实模块函数；仅去除模块声明，不复制业务函数。
// 状态别名兼容原测试注入接口，最终仍读写模块共用的state对象。
function loadScript() {
    const sources = scriptNames.map(name => read('js/'+name+'.js')
        .replace(/^import .*;\r?\n/gm,'').replace(/^export /gm,''));
    sources.splice(2,0,"Object.keys(state).forEach(name => Object.defineProperty(globalThis,name,{configurable:true,get(){return state[name];},set(value){state[name]=value;}}));");
    return '(function () {\n\'use strict\';\n'+sources.join('\n')
        + "\n/* frontend-test-bootstrap */\n}());";
}

function loadHtml() {
    const shell = read('index.html');
    return shell.replace(/(<main[^>]*id="modules-root"[^>]*>)[\s\S]*?(<\/main>)/,
        (_,start,end) => start+moduleNames.map(name=>read('modules/'+name+'.html')).join('\n')+end)
        + '<style>'+read('css/styles.css')+'</style>';
}

function loadInventoryScript() {
    return read('js/inventory.js').replace('export function initInventory() {','(function () {').replace(/\}\s*$/,'}());');
}

module.exports = {root,read,moduleNames,scriptNames,loadScript,loadHtml,loadInventoryScript};
