'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const {read} = require('./helpers/frontend-source.cjs');

function moduleUrl(source) {
    return 'data:text/javascript;base64,' + Buffer.from(source).toString('base64');
}

const markdownUrl = moduleUrl(read('vendor/markdown-it-15.0.3.min.js'));
const assistant = import(moduleUrl(read('js/assistant.js')
    .replace('../vendor/markdown-it-15.0.3.min.js', markdownUrl)));

test('助手回复渲染标题、分隔线、加粗列表和表格，HTML 与不安全链接无法变成可执行内容', async () => {
    const {renderAssistantMarkdown} = await assistant;
    const html = renderAssistantMarkdown([
        '### 查看历史价格', '', '- **当前是低价**', '', '---', '',
        '| 规格 | 单价 |', '| --- | --- |', '| 2.7kg | 4.63 元 |', '',
        '<img src=x onerror=alert(1)>', '[恶意链接](javascript:alert(1))'
    ].join('\n'));
    assert.match(html, /<h3>查看历史价格<\/h3>/);
    assert.match(html, /<li><strong>当前是低价<\/strong><\/li>/);
    assert.match(html, /<hr>/);
    assert.match(html, /<table>[\s\S]*<td>4\.63 元<\/td>/);
    assert.match(html, /&lt;img/);
    assert.doesNotMatch(html, /<img|href="javascript:/i);
});

test('流式解码在未完成时交付正文，支持跨网络片段的中文和 JSON 行', async () => {
    const {readAssistantStream} = await assistant;
    let streamController;
    const received = [];
    let firstDelta;
    const firstChunk = new Promise(resolve => { firstDelta = resolve; });
    const response = new Response(new ReadableStream({
        start(controller) { streamController = controller; }
    }));
    const reading = readAssistantStream(response, text => {
        received.push(text);
        firstDelta();
    });
    const line = new TextEncoder().encode('{"delta":"猫砂"}\n');
    streamController.enqueue(line.slice(0, 11));
    streamController.enqueue(line.slice(11));
    await firstChunk;
    assert.deepEqual(received, ['猫砂']);
    streamController.enqueue(new TextEncoder().encode('{"done":true}\n'));
    streamController.close();
    await reading;
});

test('流式错误和缺少完成事件的回复均拒绝作为完整答复', async () => {
    const {readAssistantStream} = await assistant;
    const received = [];
    await assert.rejects(readAssistantStream(new Response('{"delta":"部分"}\n{"error":"服务中断"}\n'),
        text => received.push(text)), /服务中断/);
    assert.deepEqual(received, ['部分']);
    await assert.rejects(readAssistantStream(new Response('{"delta":"部分"}\n'), () => {}), /提前结束/);
});
