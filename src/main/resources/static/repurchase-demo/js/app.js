// 应用入口：先完成所有静态模块挂载，再绑定事件，避免异步加载丢失监听器。
import { initCompare, updateCompareNameEditor, updateCompareTotalQuantity } from "./compare.js";
import { initRecord, updateRecordMoreSummary, updateRecordNormalizedField, updateRecordTotalQuantity } from "./record.js";
import { initNormalization, loadNormalizationLibrary, updateNormalizationFormMode } from "./normalization.js";
import { initOrderImport } from "./order-import.js";
import { initActions } from "./actions.js";
import { initNavigation } from "./navigation.js?v=20261119-assistant-home";
import { initInventory } from "./inventory.js";
import { initAssistant } from "./assistant.js?v=20261119-stream";

export const modules = ["compare", "purchases", "record", "normalization", "order-import", "inventory", "assistant"];
const VERSION = '20261119-assistant-home';

export async function loadModule(name) {
    const response = await fetch(new URL('../modules/' + name + '.html?v=' + VERSION, import.meta.url));
    if (!response.ok) throw new Error('模块加载失败：' + name);
    return response.text();
}

export async function bootstrap() {
    const fragments = await Promise.all(modules.map(loadModule));
    document.getElementById('modules-root').innerHTML = fragments.join('\n');
    // 图片预览为记录模块专属结构，挂载到body，保持原固定定位及遮罩层级。
    document.body.appendChild(document.getElementById('record-image-modal'));
    initCompare();
    initRecord();
    initNormalization();
    initOrderImport();
    initActions();
    initNavigation();
    initInventory();
    initAssistant();
    updateCompareNameEditor();
    updateNormalizationFormMode();
    updateCompareTotalQuantity();
    updateRecordTotalQuantity();
    updateRecordNormalizedField();
    updateRecordMoreSummary();
    loadNormalizationLibrary(false);
    document.querySelectorAll('.tab, #home-button').forEach(button => { button.disabled = false; });
    document.getElementById('modules-root').removeAttribute('aria-busy');
}

function showBootstrapError(error) {
    const target = document.getElementById('modules-root');
    target.replaceChildren();
    const message = document.createElement('p');
    message.setAttribute('role', 'alert');
    message.textContent = '页面加载失败，请重试。' + error.message;
    const retry = document.createElement('button');
    retry.className = 'btn btn-secondary';
    retry.textContent = '重新加载';
    retry.onclick = () => window.location.reload();
    target.append(message, retry);
    target.removeAttribute('aria-busy');
}

if (typeof window !== 'undefined') bootstrap().catch(showBootstrapError);
