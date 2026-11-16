'use strict';

// 直接运行页面的图片输入逻辑，模拟浏览器文件、拖拽、粘贴及异步响应，无额外依赖或网络调用。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/repurchase-demo/index.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
new vm.Script(script);
const bootstrapStart = script.indexOf("        Array.prototype.forEach.call(document.querySelectorAll('.tab')");
assert.ok(bootstrapStart > 0);
const testScript = script.slice(0, bootstrapStart) + `
    globalThis.ui = {
        updateRecordImageFileInfo, receiveRecordImageFiles, selectRecordImageFile, parseOrderImage,
        bindRecordImageTransferEvents,
        state: function () { return { file: selectedOrderImageFile, dataUrl: lastSelectedImageDataUrl,
            candidates: lastParsedPurchaseCandidates }; }
    };
}());`;

function harness() {
    const elements = new Map();
    const documentEvents = new Map();
    const reads = [];
    const requests = [];
    function element(id) {
        if (!elements.has(id)) {
            const classes = new Set();
            const events = new Map();
            elements.set(id, {
                id, value: '', textContent: '', innerHTML: '', className: '', files: [], dataset: {}, elements: [], events,
                classList: {
                    add(name) { classes.add(name); }, remove(name) { classes.delete(name); },
                    contains(name) { return classes.has(name); }
                },
                setAttribute(name, value) { this[name] = value; },
                removeAttribute(name) { delete this[name]; },
                addEventListener(name, callback) { events.set(name, callback); }
            });
        }
        return elements.get(id);
    }
    element('record').classList.add('active');
    element('record-form').elements = [{ name: 'owner', value: 'test-owner' }];
    const context = vm.createContext({
        document: { getElementById: element, querySelectorAll: () => [],
            addEventListener(name, callback) { documentEvents.set(name, callback); } },
        FileReader: class {
            readAsDataURL(file) {
                const reader = this;
                const read = {
                    file,
                    succeed() { reader.result = 'data:' + (file.type || 'image/png') + ';base64,' + file.token; reader.onload(); },
                    fail() { reader.onerror(); }
                };
                reads.push(read);
                if (!file.deferred) queueMicrotask(() => file.fail ? read.fail() : read.succeed());
            }
        },
        fetch(url, options) {
            return new Promise((resolve, reject) => requests.push({ url, payload: JSON.parse(options.body),
                succeed(data) { resolve({ ok: true, text: async () => JSON.stringify(data) }); }, reject }));
        }
    });
    vm.runInContext(testScript, context);
    context.ui.bindRecordImageTransferEvents();
    return {
        ui: context.ui, element, reads, requests,
        drop(event) { return element('record-image-drop-zone').events.get('drop')(event); },
        paste(event) { return documentEvents.get('paste')(event); }
    };
}

function image(name = 'order.png', extra = {}) {
    return { name, size: 2048, type: 'image/png', token: 'aW1hZ2U=', ...extra };
}

function transferEvent(files = [], extra = {}) {
    return {
        defaultPrevented: false, stopped: false,
        dataTransfer: { files }, clipboardData: { files },
        preventDefault() { this.defaultPrevented = true; },
        stopPropagation() { this.stopped = true; }, ...extra
    };
}

test('拖入图片显示预览，阻止浏览器默认打开图片，且不会自动请求解析', async () => {
    const h = harness();
    const file = image();
    const event = transferEvent([file]);
    await h.drop(event);
    assert.equal(event.defaultPrevented, true);
    assert.equal(event.stopped, true);
    assert.equal(h.ui.state().file, file);
    assert.match(h.element('record-image-preview-img').src, /^data:image\/png;base64,/);
    assert.equal(h.element('record-image-file-name').textContent, 'order.png');
    assert.equal(h.requests.length, 0);
});

test('Ctrl+V 支持 clipboard items 中的图片，并复用同一预览流程', async () => {
    const h = harness();
    const file = image('image.png');
    const event = transferEvent([], { clipboardData: { items: [
        { kind: 'string', getAsFile() { throw new Error('不应读取文字项'); } },
        { kind: 'file', getAsFile() { return file; } }
    ] } });
    await h.paste(event);
    assert.equal(event.defaultPrevented, true);
    assert.equal(h.ui.state().file, file);
    assert.equal(h.requests.length, 0);
});

test('普通文字粘贴、其他页粘贴及已处理事件均保持原样', () => {
    const h = harness();
    const text = transferEvent([], { clipboardData: { items: [{ kind: 'string' }] } });
    h.paste(text);
    assert.equal(text.defaultPrevented, false);
    h.element('record').classList.remove('active');
    const inactive = transferEvent([image()]);
    h.paste(inactive);
    assert.equal(inactive.defaultPrevented, false);
    h.element('record').classList.add('active');
    h.paste(transferEvent([image()], { defaultPrevented: true }));
    assert.equal(h.reads.length, 0);
});

test('原有文件选择继续可用，并能替换拖入的图片', async () => {
    const h = harness();
    await h.drop(transferEvent([image('old.png')]));
    const file = image('picked.jpg', { type: 'image/jpeg' });
    h.element('record-image-file').files = [file];
    await h.ui.updateRecordImageFileInfo();
    assert.equal(h.ui.state().file, file);
    assert.match(h.ui.state().dataUrl, /^data:image\/jpeg/);
});

test('不支持的文件给出格式提示，不会读取或上传', async () => {
    const h = harness();
    await h.drop(transferEvent([image('animated.gif', { type: 'image/gif' })]));
    await h.ui.parseOrderImage({ dataset: {}, textContent: '解析' });
    assert.match(h.element('record-image-file-info').textContent, /格式不支持/);
    assert.equal(h.reads.length, 0);
    assert.equal(h.requests.length, 0);
});

test('拖入多个文件时提示单张上传，保留当前截图', async () => {
    const h = harness();
    const old = image('current.png');
    await h.drop(transferEvent([old]));
    await h.drop(transferEvent([image('one.png'), image('two.png')]));
    assert.match(h.element('record-image-file-info').textContent, /一次只能/);
    assert.equal(h.ui.state().file, old);
});

test('仅有图片链接而没有图片文件时提示粘贴方式，不发起外链请求', async () => {
    const h = harness();
    const event = transferEvent([], { dataTransfer: { files: [], types: ['text/uri-list'] } });
    await h.drop(event);
    assert.equal(event.defaultPrevented, true);
    assert.match(h.element('record-image-file-info').textContent, /复制图片后粘贴/);
    assert.equal(h.requests.length, 0);
});

test('拖拽高亮支持嵌套进入与离开，释放图片后清除高亮', async () => {
    const h = harness();
    const zone = h.element('record-image-drop-zone');
    zone.events.get('dragenter')(transferEvent());
    zone.events.get('dragenter')(transferEvent());
    zone.events.get('dragleave')(transferEvent());
    assert.equal(zone.classList.contains('drag-over'), true);
    const over = transferEvent();
    zone.events.get('dragover')(over);
    assert.equal(over.dataTransfer.dropEffect, 'copy');
    zone.events.get('dragleave')(transferEvent());
    assert.equal(zone.classList.contains('drag-over'), false);
    zone.events.get('dragenter')(transferEvent());
    await h.drop(transferEvent([image()]));
    assert.equal(zone.classList.contains('drag-over'), false);
});

test('快速切换图片时旧读取不能覆盖新预览', async () => {
    const h = harness();
    const old = h.ui.selectRecordImageFile(image('old.png', { deferred: true, token: 'b2xk' }));
    await h.ui.selectRecordImageFile(image('new.png', { token: 'bmV3' }));
    h.reads[0].succeed();
    await old;
    assert.equal(h.element('record-image-file-name').textContent, 'new.png');
    assert.match(h.element('record-image-preview-img').src, /bmV3$/);
});

test('图片预览读取失败使用简洁提示', async () => {
    const h = harness();
    await h.drop(transferEvent([image('broken.png', { fail: true })]));
    assert.match(h.element('record-image-file-info').textContent, /图片读取失败/);
    assert.equal(h.ui.state().dataUrl, null);
});

test('立即点击解析会等待同一次读取，并提交拖入的图片而非选择器旧文件', async () => {
    const h = harness();
    const file = image('drop.webp', { type: 'image/webp', deferred: true });
    h.element('record-image-file').files = [image('stale.png')];
    const preview = h.drop(transferEvent([file]));
    const button = { dataset: {}, textContent: '解析' };
    const parsing = h.ui.parseOrderImage(button);
    assert.equal(h.reads.length, 1);
    assert.equal(h.requests.length, 0);
    h.reads[0].succeed();
    await preview;
    await new Promise(setImmediate);
    assert.equal(h.requests[0].payload.imageFileName, 'drop.webp');
    assert.equal(h.requests[0].payload.imageMimeType, 'image/webp');
    assert.equal(h.requests[0].payload.dryRun, true);
    assert.equal(h.requests[0].payload.owner, 'test-owner');
    h.requests[0].succeed({ candidates: [] });
    await parsing;
    assert.equal(button.disabled, false);
});

test('切换图片后旧解析结果不能覆盖新图片界面或表单', async () => {
    const h = harness();
    await h.drop(transferEvent([image('old.png')]));
    const parsing = h.ui.parseOrderImage({ dataset: {}, textContent: '解析' });
    await h.drop(transferEvent([image('new.png')]));
    h.requests[0].succeed({ candidates: [{ productName: '不应填入的旧结果' }] });
    await parsing;
    assert.equal(h.ui.state().candidates.length, 0);
    assert.match(h.element('record-result').innerHTML, /等待截图解析/);
    assert.equal(h.element('record-image-file-name').textContent, 'new.png');
});

test('切换图片后旧解析请求失败不能覆盖新提示', async () => {
    const h = harness();
    await h.drop(transferEvent([image('old.png')]));
    const parsing = h.ui.parseOrderImage({ dataset: {}, textContent: '解析' });
    await h.drop(transferEvent([image('new.png')]));
    h.requests[0].reject(new Error('旧请求失败'));
    await parsing;
    assert.match(h.element('record-result').innerHTML, /等待截图解析/);
});
