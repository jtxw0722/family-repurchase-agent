'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const {read,loadHtml,moduleNames} = require('./helpers/frontend-source.cjs');

test('入口只保留壳与资源引用，独立模块挂载后ID保持唯一',()=>{
    const shell=read('index.html');
    assert.ok(shell.split('\n').length<100);
    assert.doesNotMatch(shell,/<style>|<script>/);
    assert.match(shell,/<script type="module" src="js\/app.js/);
    assert.match(shell,/id="modules-root"[^>]*aria-busy="true"/);
    assert.match(shell,/<button id="home-button"[^>]*aria-controls="assistant"/);
    assert.doesNotMatch(shell,/data-target="assistant"/);
    const assembled=loadHtml();
    const ids=[...assembled.matchAll(/\bid="([^"]+)"/g)].map(match=>match[1]);
    assert.equal(ids.length,new Set(ids).size,'拆分不能遗留重复ID');
    moduleNames.forEach(name=>assert.match(read('modules/'+name+'.html'),/<section id=/));
    assert.match(read('modules/record.html'),/id="record-image-modal"/);
    assert.equal([...assembled.matchAll(/id="assistant-form"/g)].length,1);
    assert.match(assembled,/id="assistant-launcher"[^>]*aria-controls="assistant"[^>]*hidden/);
});

test('真实ES模块可链接且HTML加载完成后才初始化，加载失败保留明确重试入口',()=>{
    const result=spawnSync(process.execPath,['--experimental-vm-modules',path.join(__dirname,'helpers/module-runtime-check.cjs')],{encoding:'utf8'});
    assert.equal(result.status,0,result.stderr+'\n'+result.stdout);
});
