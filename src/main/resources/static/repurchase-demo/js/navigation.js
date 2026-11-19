// 导航与模块说明。
import { MODULE_HELP_TEXT } from "./config.js";
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
    Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (tab) {
        tab.addEventListener('click', function () {
            Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (item) { item.classList.remove('active'); });
            Array.prototype.forEach.call(document.querySelectorAll('.module'), function (item) { item.classList.remove('active'); });
            tab.classList.add('active');
            $(tab.dataset.target).classList.add('active');
            updateModuleHelp(tab.dataset.target);
            if (tab.dataset.target === 'normalization' || tab.dataset.target === 'compare') loadNormalizationLibrary(false);
        });
    });
}
