// 导航与模块说明。
import { MODULE_HELP_TEXT } from "./config.js";
import { loadHomeDashboards } from "./dashboard.js";
import { loadNormalizationLibrary } from "./normalization.js";
import { $ } from "./shared.js";

export function updateModuleHelp(moduleName) {
    var help = MODULE_HELP_TEXT[moduleName];
    if (!help) return;
    // 说明栏跟随当前模块移到结果区外层，渲染结果时不会被 innerHTML 覆盖。
    var panel = $('module-help-panel');
    var result = $(moduleName + '-result');
    if (panel && result && result.parentElement) result.parentElement.insertBefore(panel, result);
    $('module-description').textContent = help.description;
    help.tips.forEach(function (tip, index) {
        $('module-tip-' + (index + 1)).textContent = tip;
    });
}

// 仅在模块HTML全部挂载后绑定一次，不在导航切换时重新初始化。
export function initNavigation() {
    const tabs = document.querySelectorAll('.tab');
    const modules = document.querySelectorAll('.module');
    const homeButton = $('home-button');
    const assistant = $('assistant');
    const launcher = $('assistant-launcher');
    const minimize = $('assistant-minimize');

    function setAssistantExpanded(expanded) {
        // 只切换会话的展示方式，消息、草稿和进行中的流式响应留在原节点。
        assistant.classList.toggle('assistant-floating', expanded);
        launcher.setAttribute('aria-expanded', String(expanded));
        const label = expanded ? '收起复购助手' : '展开复购助手';
        launcher.setAttribute('aria-label', label);
        launcher.title = label;
        launcher.firstElementChild.textContent = expanded ? '−' : '💬';
        minimize.hidden = !expanded;
    }

    function activateModule(moduleName) {
        const isHome = moduleName === assistant.id;
        modules.forEach(module => module.classList.toggle('active', module.id === moduleName));
        tabs.forEach(tab => {
            const active = tab.dataset.target === moduleName;
            tab.classList.toggle('active', active);
            if (active) tab.setAttribute('aria-current', 'page');
            else tab.removeAttribute('aria-current');
        });
        launcher.hidden = isHome;
        if (isHome) {
            homeButton.setAttribute('aria-current', 'page');
            setAssistantExpanded(false);
            loadHomeDashboards();
        } else {
            homeButton.removeAttribute('aria-current');
        }
        updateModuleHelp(moduleName);
        if (moduleName === 'normalization' || moduleName === 'compare') loadNormalizationLibrary(false);
    }

    function collapseAssistant() {
        setAssistantExpanded(false);
        launcher.focus({preventScroll: true});
    }

    tabs.forEach(tab => tab.addEventListener('click', () => activateModule(tab.dataset.target)));
    homeButton.addEventListener('click', () => activateModule(assistant.id));
    launcher.addEventListener('click', () => {
        if (assistant.classList.contains('assistant-floating')) {
            collapseAssistant();
        } else {
            setAssistantExpanded(true);
            minimize.focus({preventScroll: true});
        }
    });
    minimize.addEventListener('click', collapseAssistant);
    assistant.addEventListener('keydown', event => {
        if (event.key === 'Escape' && assistant.classList.contains('assistant-floating')) {
            event.preventDefault();
            collapseAssistant();
        }
    });
    activateModule(assistant.id);
}
