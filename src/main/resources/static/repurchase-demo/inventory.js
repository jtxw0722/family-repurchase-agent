/* 独立库存交互；操作失败保留幂等键，网络重试不重复入库。 */
(function () {
    'use strict';
    const $ = id => document.getElementById('inventory-' + id);
    const units = ['kg','g','L','ml','袋','包','瓶','盒','件','个','卷','抽','片','支','罐','桶','套'];
    let selected = null;
    let inventoryProducts = [];
    let inventoryRows = [];
    let depletionAscending = true;
    let busy = false;
    let initializationRows = [];
    let initializationRange = null;
    let initializationFilter = {category:'', name:''};
    // 估算为只读请求；序号与日期/勾选签名共同隔离迟到响应，避免显示上一组选项的库存。
    let initializationEstimateSequence = 0;
    let initializationEstimateSignature = '';
    // 重置后优先定位该商品，防止旧筛选停留在另一个仍已同步的商品；待查询成功后清除。
    let resetProductName = '';
    const initializationSelected = new Set();
    const pending = new Map();
    const escape = value => String(value == null ? '—' : value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    const shown = value => value == null ? '—' : escape(value);
    // 换算由后端BigDecimal完成，前端只读取规则标准单位及配套数量，不单独替换单位标签。
    const displayedValue = (row, key) => Object.prototype.hasOwnProperty.call(row, 'display_' + key) ? row['display_' + key] : row[key];
    const displayedUnit = row => row.display_unit || row.base_unit;
    // 日均消耗仅在展示时保留两位并四舍五入，不将展示值写回库存或消耗参数。
    const dailyRateFormat = new Intl.NumberFormat('zh-CN', {useGrouping:false, minimumFractionDigits:2, maximumFractionDigits:2});
    const formattedRate = (value, missing) => value == null || value === '' ? missing : dailyRateFormat.format(value);
    // 库存列表仅格式化展示，不将两位小数用于数量确认、补货判断或保存。
    const formattedQuantity = value => formattedRate(value, '—');
    // 切换只控制可见性与浏览器校验，保留当前输入；自动模式保存时不提交隐藏的人工草稿。
    function updateConsumptionFields() {
        const manual = $('source').value === 'MANUAL';
        $('manual-fields').hidden = !manual;
        ['reference-quantity','reference-unit','reference-days'].forEach(id => {
            $(id).disabled = !manual;
            $(id).required = manual;
        });
    }
    const message = text => {
        $('message').textContent = text;
        $('message').hidden = !text;
    };
    async function api(path, body) {
        const response = await fetch('/api/inventory' + path, body ? {method:'POST', headers:{'Content-Type':'application/json'},body:JSON.stringify(body)} : {});
        const result = await response.json();
        if (!response.ok) throw new Error(result.error || '操作失败，请刷新核对后重试');
        return result;
    }
    async function run(work) {
        if (busy) return;
        busy = true;
        try {
            const result = await work();
            message(typeof result === 'string' ? result : '');
        }
        catch (error) { message(error.message); }
        finally { busy = false; updateInitializationConfirm(); }
    }
    async function mutate(path, body) {
        const signature = path + JSON.stringify(body);
        if (!pending.has(signature)) pending.set(signature, crypto.randomUUID());
        const result = await api(path, {...body, requestKey:pending.get(signature)});
        pending.delete(signature);
        return result;
    }
    // 标签只呈现后端预测；不足一天不等于耗尽，库存未知时也不能当作零库存。
    function replenishmentTag(row) {
        if (row.estimate_pending) return '';
        const quantity = row.estimated_quantity;
        if (quantity == null || quantity === '') return '';
        // 用十进制零值判断，避免展示取整或浮点转换把微量剩余误判为零。
        const exhausted = /^0(?:\.0+)?(?:[eE][+-]?\d+)?$/.test(String(quantity));
        const approaching = row.unit_compatible !== false && ['立即补货','即将进入补货周期','已超过预计耗尽日期'].includes(row.status);
        if (!exhausted && !approaching) return '';
        const description = exhausted ? '库存已耗尽，请补货' : row.status;
        return '<span class="inventory-replenishment-tag inventory-replenishment-' + (exhausted ? 'empty' : 'low') + '" title="' + escape(description) + '" aria-label="' + escape(description) + '">补货</span>';
    }
    // 详情保留完整状态文字，使用图标和颜色辅助识别，不改变后端补货状态。
    function detailStatusTag(row) {
        const status = row.estimate_pending ? '消耗待设置' : row.status || '暂无状态';
        let tone = 'neutral';
        let icon = 'i';
        if (status === '暂时不需要') {
            tone = 'success'; icon = '✓';
        } else if (['库存为零，请补货','已超过预计耗尽日期'].includes(status)
            || (status === '立即补货' && /^0(?:\.0+)?(?:[eE][+-]?\d+)?$/.test(String(row.estimated_quantity)))) {
            tone = 'danger'; icon = '!';
        } else if (['立即补货','即将进入补货周期'].includes(status)) {
            tone = 'warning'; icon = '!';
        }
        return '<span class="inventory-status-tag inventory-status-' + tone + '"><span class="inventory-status-icon" aria-hidden="true">' + icon + '</span>' + escape(status) + '</span>';
    }
    // ISO自然日按字典序排序；无预测日期始终置后，同日保留原顺序。
    function renderInventoryList() {
        $('sort-order').value = depletionAscending ? 'asc' : 'desc';
        const validDate = row => !row.estimate_pending && row.unit_compatible !== false && /^\d{4}-\d{2}-\d{2}$/.test(row.depletion_date || '') ? row.depletion_date : null;
        const rows = inventoryRows.slice().sort((left, right) => {
            const a = validDate(left), b = validDate(right);
            if (a == null || b == null) return a == null ? b == null ? 0 : 1 : -1;
            return (a < b ? -1 : a > b ? 1 : 0) * (depletionAscending ? 1 : -1);
        });
        $('list').setAttribute('data-empty', String(rows.length === 0));
        $('list').innerHTML = '<table><thead><tr>' + ['商品','预计当前库存','采用方式','剩余完整天数','耗尽日期','提醒日期','样本','操作'].map(x=>x === '耗尽日期' ? '<th aria-sort="' + (depletionAscending ? 'ascending' : 'descending') + '"><button class="inventory-sort-button" type="button" data-inventory-sort="depletion" aria-label="按耗尽日期' + (depletionAscending ? '降序' : '升序') + '排列">耗尽日期<span aria-hidden="true">' + (depletionAscending ? ' ↑' : ' ↓') + '</span></button></th>' : '<th>'+x+'</th>').join('') + '</tr></thead><tbody>' + rows.map(row => '<tr><td>' + escape(row.normalized_name) + replenishmentTag(row) + '</td><td>' + (row.estimate_pending ? '消耗待设置' : formattedQuantity(displayedValue(row,'estimated_quantity')) + ' ' + escape(displayedUnit(row))) + '</td><td>' + (row.effective_source === 'MANUAL' ? '用户自定义' : '自动估算') + '</td><td>' + shown(row.estimate_pending ? null : row.remaining_days) + '</td><td>' + shown(row.estimate_pending ? null : row.depletion_date) + '</td><td>' + shown(row.estimate_pending ? null : row.reminder_date) + '</td><td>' + escape(row.sample_label) + '</td><td><button class="btn btn-secondary btn-small" type="button" data-inventory-id="' + row.id + '">详情 / 设置</button></td></tr>').join('') + '</tbody></table>';
        if (!rows.length) $('list').innerHTML = '<div class="inventory-empty" role="status"><span class="inventory-empty-icon" aria-hidden="true">📦</span><div><strong>暂无库存记录</strong><p>可以同步购买记录，或在下方手动记录家中现有数量。</p></div></div>';
    }
    async function refresh() {
        inventoryRows = await api('');
        renderInventoryList();
        // 库存或消耗参数更新后，即便勾选未变，也重新获取当前组合的只读预估。
        updateInitializationEstimate(true);
    }
    async function open(id) {
        const previousInventoryId = selected && selected.id;
        selected = await api('/' + id);
        const row = selected;
        const unit = displayedUnit(row);
        const quantity = row.estimate_pending ? null : displayedValue(row,'estimated_quantity');
        populateInventoryUnits([unit,row.base_unit,row.manual_reference_unit]);
        $('detail').hidden = false;
        $('detail-title').innerHTML = escape(row.normalized_name) + detailStatusTag(row);
        const timelineNotes = [];
        if (row.history_rebuild_required) timelineNotes.push('旧版本库存已保留。请确认当前数量建立新基准，或重置库存后重新同步购买记录，按购买日起算。');
        if (row.estimate_pending) timelineNotes.push('消耗待设置，暂无法估算当前库存。设置消耗周期后将按入库日期重新估算，也可以直接确认家中当前数量。');
        $('timeline-note').textContent = timelineNotes.join(' ');
        $('timeline-note').hidden = timelineNotes.length === 0;
        // 仅强调估算数值及样本数，所有接口文本先转义，避免高亮标记引入HTML注入。
        $('auto').innerHTML = '系统自动估算：<strong class="inventory-estimate-number">' + escape(formattedRate(displayedValue(row,'auto_daily_rate'), '暂无')) + ' ' + escape(unit) + '/天</strong>；有效购买明细 <strong class="inventory-estimate-number">' + shown(row.purchase_record_count) + '</strong> 条，周期 <strong class="inventory-estimate-number">' + shown(row.auto_sample_count) + '</strong> 个；' + escape(row.sample_label) + '。';
        // 无异常时不展示默认规则说明；真实的单位冲突或被排除样本原因仍需提示。
        const warning = [row.unit_warning,row.warnings].filter(value => value != null && String(value).trim()).join('；');
        $('warning').textContent = warning;
        $('warning').hidden = !warning;
        $('warning-details').hidden = !warning;
        // 新商品默认收起；同一商品刷新时保留用户的展开状态，无明细时隐藏整块区域。
        if (previousInventoryId !== row.id || !warning) $('warning-details').open = false;
        $('quantity').value = quantity == null ? '' : formattedQuantity(quantity);
        $('unit').value = unit;
        $('source').value = row.effective_source;
        $('reference-quantity').value = row.manual_reference_quantity || '';
        $('reference-unit').value = row.manual_reference_unit || unit;
        $('reference-days').value = row.manual_reference_days || '';
        $('lead').value = row.reminder_lead_days;
        updateConsumptionFields();
    }
    async function change(operation, body) {
        if (!selected) throw new Error('请先打开库存详情');
        await mutate('/' + selected.id + '/' + operation, body);
        await refresh(); await open(selected.id);
        if (operation === 'reset') {
            resetProductName = selected.normalized_name;
            // 重置后的同步是新操作，不能沿用上次网络失败留下的旧同步请求键。
            for (const signature of pending.keys()) {
                if (signature.startsWith('/initialization/confirm')) pending.delete(signature);
            }
            // 重置后同步预览不能保留旧的灰显关联；重新查询不自动勾选或再次入库。
            if (!$('init-panel').hidden && initializationIsCurrent()) {
                try { await queryInitialization(); }
                catch (error) {
                    $('init-summary').textContent = '库存已重置，列表刷新失败，请重新查询。';
                    return selected.normalized_name + '库存已重置为0，购买记录列表刷新失败：' + error.message + '；请重新查询。';
                }
            }
            return selected.normalized_name + '库存已重置为0，请在该商品的购买记录中重新勾选同步。';
        }
    }

    // 使用上海自然日和日历年，闰日回退至上一年二月末，不依赖浏览器所在时区。
    function defaultInitializationRange() {
        const parts = new Intl.DateTimeFormat('en-CA', {timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date());
        const dayParts = Object.fromEntries(parts.filter(part => part.type !== 'literal').map(part => [part.type, part.value]));
        const previousYear = Number(dayParts.year) - 1;
        const lastDay = new Date(Date.UTC(previousYear, Number(dayParts.month), 0)).getUTCDate();
        const previousDay = String(Math.min(Number(dayParts.day), lastDay)).padStart(2,'0');
        return {startDate:previousYear + '-' + dayParts.month + '-' + previousDay, endDate:dayParts.year + '-' + dayParts.month + '-' + dayParts.day};
    }
    function currentInitializationRange() {
        return {startDate:$('init-start').value, endDate:$('init-end').value};
    }
    function initializationIsCurrent() {
        const current = currentInitializationRange();
        return initializationRange && current.startDate === initializationRange.startDate && current.endDate === initializationRange.endDate;
    }
    function updateInitializationConfirm() {
        $('init-confirm').disabled = busy || !initializationIsCurrent() || initializationSelected.size === 0;
        $('init-confirm').textContent = '同步已勾选记录（' + initializationSelected.size + '）';
    }
    async function updateInitializationEstimate(force = false) {
        const purchaseRecordIds = [...initializationSelected].sort((left,right)=>left-right);
        if (!initializationIsCurrent() || !purchaseRecordIds.length) {
            initializationEstimateSequence++;
            initializationEstimateSignature = '';
            $('init-estimate').hidden = true;
            $('init-estimate-message').textContent = '';
            $('init-estimate-rows').innerHTML = '';
            $('init-estimate-retry').hidden = true;
            return;
        }
        const body = {...initializationRange, purchaseRecordIds};
        const signature = JSON.stringify(body);
        if (!force && signature === initializationEstimateSignature) return;
        initializationEstimateSignature = signature;
        const sequence = ++initializationEstimateSequence;
        $('init-estimate').hidden = false;
        $('init-estimate-message').textContent = '正在按购买日期估算截至今日的库存…';
        $('init-estimate-rows').innerHTML = '';
        $('init-estimate-retry').hidden = true;
        try {
            const result = await api('/initialization/estimate', body);
            if (sequence !== initializationEstimateSequence || !initializationIsCurrent()) return;
            if (!Array.isArray(result.products)) throw new Error('预计库存数据无效');
            $('init-estimate-message').textContent = '购买入库量包含已消耗部分。下方为合并该商品已有库存后，截至今日的估算；自动估算仅供参考。';
            $('init-estimate-rows').innerHTML = '<table><thead><tr><th>商品</th><th>本次购买入库量</th><th>同步后预计当前库存</th></tr></thead><tbody>'
                + result.products.map(product => '<tr><td>' + escape(product.normalizedName) + '</td><td>' + shown(product.purchaseQuantity) + ' ' + shown(product.unit)
                    + '</td><td>' + (product.pending ? '消耗待设置，暂无法估算当前库存' : product.estimatedQuantity == null ? '暂无法估算当前库存' : formattedQuantity(product.estimatedQuantity) + ' ' + shown(product.unit))
                    + (product.reason ? '<span class="inventory-product-specification">' + escape(product.reason) + '</span>' : '') + '</td></tr>').join('') + '</tbody></table>';
        } catch (error) {
            if (sequence !== initializationEstimateSequence) return;
            $('init-estimate-message').textContent = '库存估算失败：' + error.message + '。请重试估算，当前未显示估算结果。';
            $('init-estimate-rows').innerHTML = '';
            $('init-estimate-retry').hidden = false;
        }
    }
    function filteredInitializationRows() {
        return initializationRows.filter(row => (!initializationFilter.category || row.category === initializationFilter.category)
            && (!initializationFilter.name || row.normalizedName === initializationFilter.name));
    }
    // 只列出当前周期、所选品类的名称；同名购买明细合并为一个选项，切换品类时清理失效选择。
    function updateInitializationNames(preferredName = $('init-name').value) {
        const category = $('init-category').value;
        const names = [...new Set(initializationRows.filter(row => !category || row.category === category)
            .map(row => row.normalizedName).filter(name => typeof name === 'string' && name.trim()))];
        // 当前周期无该商品购买时也保留定位选项，明确展示空结果，而不是跳到其他商品。
        if (!category && resetProductName && !names.includes(resetProductName)) names.push(resetProductName);
        names.sort();
        $('init-name').innerHTML = '<option value="">全部归一化名称</option>' + names.map(name => '<option value="' + escape(name) + '">' + escape(name) + '</option>').join('');
        $('init-name').value = names.includes(preferredName) ? preferredName : '';
    }
    function canSynchronize(row) {
        return row.selectable && !row.linked;
    }
    function renderInitialization() {
        const rows = filteredInitializationRows();
        const visibleSelected = rows.filter(row => initializationSelected.has(row.purchaseRecordId)).length;
        $('init-summary').textContent = '显示 ' + rows.length + ' / ' + initializationRows.length + ' 条，已勾选 ' + initializationSelected.size
            + ' 条（筛选外 ' + (initializationSelected.size - visibleSelected) + ' 条）。未勾选的不会同步。';
        if (!rows.length) {
            $('init-rows').textContent = initializationRows.length ? '没有符合筛选条件的记录。' : '该周期没有购买记录。';
            $('init-rows').innerHTML = '<p>' + $('init-rows').textContent + '</p>';
        } else {
            $('init-rows').innerHTML = '<table><thead><tr>' + ['勾选','购买日期','品类','归一化名称','商品名称/规格','购买数量','购买入库量','同步状态'].map(label => '<th>' + label + '</th>').join('')
                + '</tr></thead><tbody>' + rows.map(row => '<tr' + (row.linked ? ' class="inventory-sync-linked"' : '') + '><td><input type="checkbox" aria-label="选择购买明细 ' + row.purchaseRecordId
                    + '" data-inventory-init-id="' + row.purchaseRecordId + '"' + (canSynchronize(row) && initializationSelected.has(row.purchaseRecordId) ? ' checked' : '')
                    + (canSynchronize(row) ? '' : ' disabled') + '></td><td>' + escape(String(row.purchaseDate || '—').slice(0, 10)) + '</td><td>' + escape(row.category)
                    + '</td><td>' + escape(row.normalizedName || '未归一化') + '</td><td class="inventory-product-details"><span>' + escape(row.productName)
                    + '</span><span class="inventory-product-specification">' + shown(row.specification) + '</span></td><td>' + shown(row.quantity) + ' ' + shown(row.unit)
                    + '</td><td>' + shown(row.displayInventoryQuantity === undefined ? row.inventoryQuantity : row.displayInventoryQuantity) + ' ' + shown(row.displayUnit || row.baseUnit)
                    + '</td><td>' + (row.linked ? '<span class="inventory-sync-status">该记录已经同步至库存中</span>' : escape(row.reason || '可同步，待勾选确认')) + '</td></tr>').join('') + '</tbody></table>';
        }
        updateInitializationConfirm();
        updateInitializationEstimate();
    }
    function invalidateInitialization() {
        initializationRange = null;
        initializationRows = [];
        initializationSelected.clear();
        initializationFilter = {category:'', name:''};
        $('init-category').innerHTML = '<option value="">全部品类</option>';
        $('init-category').value = '';
        updateInitializationNames('');
        $('init-rows').innerHTML = '';
        $('init-summary').textContent = '日期已修改，请重新查询后勾选。';
        updateInitializationConfirm();
        updateInitializationEstimate();
    }
    async function queryInitialization(preferredName = resetProductName || $('init-name').value) {
        invalidateInitialization();
        const range = currentInitializationRange();
        if (!range.startDate || !range.endDate || range.startDate > range.endDate) throw new Error('请选择正确的开始日期和结束日期');
        const preview = await api('/initialization/preview?startDate=' + encodeURIComponent(range.startDate) + '&endDate=' + encodeURIComponent(range.endDate));
        const current = currentInitializationRange();
        // 查询期间变更了日期，旧响应不能覆盖新的选择区间或用于确认。
        if (current.startDate !== range.startDate || current.endDate !== range.endDate) return '日期已修改，请重新查询。';
        initializationRange = {startDate:preview.startDate, endDate:preview.endDate};
        initializationRows = preview.records;
        const categories = [...new Set(initializationRows.map(row => row.category))].sort();
        $('init-category').innerHTML = '<option value="">全部品类</option>' + categories.map(category => '<option value="' + escape(category) + '">' + escape(category) + '</option>').join('');
        $('init-category').value = '';
        updateInitializationNames(preferredName);
        initializationFilter = {category:'', name:$('init-name').value};
        resetProductName = '';
        renderInitialization();
        return '已列出购买记录，请勾选后确认同步；未勾选的不会入库。';
    }
    const initialRange = defaultInitializationRange();
    $('init-start').value = initialRange.startDate;
    $('init-end').value = initialRange.endDate;
    $('init-start').max = initialRange.endDate;
    $('init-end').max = initialRange.endDate;
    $('initialize').onclick = () => run(async () => {
        $('init-panel').hidden = false;
        $('initialize').setAttribute('aria-expanded','true');
        return queryInitialization();
    });
    $('init-query-form').onsubmit = event => { event.preventDefault(); run(queryInitialization); };
    ['init-start','init-end'].forEach(id => { $(id).oninput = invalidateInitialization; });
    $('init-category').onchange = () => updateInitializationNames();
    $('init-filter-form').onsubmit = event => {
        event.preventDefault();
        if (busy) return;
        initializationFilter = {category:$('init-category').value, name:$('init-name').value};
        renderInitialization();
    };
    $('init-rows').onchange = event => {
        const checkbox = event.target.closest('[data-inventory-init-id]');
        if (!checkbox) return;
        const id = Number(checkbox.dataset.inventoryInitId);
        const row = initializationRows.find(candidate => candidate.purchaseRecordId === id);
        if (busy || !row || !canSynchronize(row) || !initializationIsCurrent()) {
            checkbox.checked = initializationSelected.has(id);
            return;
        }
        if (checkbox.checked) initializationSelected.add(id); else initializationSelected.delete(id);
        renderInitialization();
    };
    $('init-select-visible').onclick = () => {
        if (busy || !initializationIsCurrent()) return;
        filteredInitializationRows().filter(canSynchronize).forEach(row => initializationSelected.add(row.purchaseRecordId));
        renderInitialization();
    };
    $('init-clear').onclick = () => { if (!busy) { initializationSelected.clear(); renderInitialization(); } };
    $('init-estimate-retry').onclick = () => updateInitializationEstimate(true);
    $('init-confirm').onclick = () => run(async () => {
        if (!initializationIsCurrent()) throw new Error('日期已修改，请重新查询');
        if (!initializationSelected.size) throw new Error('请至少勾选一条购买记录');
        updateInitializationConfirm();
        const result = await mutate('/initialization/confirm', {...initializationRange, purchaseRecordIds:[...initializationSelected].sort((left,right)=>left-right)});
        await refresh();
        // 从详情重置后再同步时，同步刷新已展开的数量基准，避免继续显示重置时的0。
        if (selected) await open(selected.id);
        await queryInitialization();
        return '已同步 ' + result.synchronizedCount + ' 条，已同步跳过 ' + result.alreadySyncedCount + ' 条；未勾选的未入库。';
    });
    // 仅用于必填提示，不参与数量计算；包装换算仍以服务端BigDecimal规则校验为准。
    function manualUnitFamily(unit) {
        const normalized = String(unit || '').trim().toLowerCase();
        if (['kg','g','千克','公斤','克'].includes(normalized)) return 'weight';
        if (['l','ml','升','毫升'].includes(normalized)) return 'volume';
        return null;
    }
    function updateManualSpecification() {
        const product = inventoryProducts.find(item => String(item.id) === $('product').value);
        const requiresSpecification = Boolean(product && manualUnitFamily(product.standard_unit)
            && $('create-unit').value && !manualUnitFamily($('create-unit').value));
        $('create-specification').required = requiresSpecification;
        $('create-specification-state').textContent = requiresSpecification ? '必填' : '按需填写';
        $('create-specification-state').setAttribute('data-required', String(requiresSpecification));
        $('create-specification-help').textContent = requiresSpecification
            ? '当前包装单位需要换算，请填写明确的单包装净含量。' : '直接使用kg/g、L/ml等标准单位时可不填。';
    }
    function populateInventoryUnits(extraUnits = []) {
        const configuredUnits = inventoryProducts.map(product => product.standard_unit);
        const availableUnits = [...new Set([...units,...configuredUnits,...extraUnits].filter(Boolean))];
        ['create-unit','unit','reference-unit'].forEach(id => {
            const previous = $(id).value;
            $(id).innerHTML = availableUnits.map(unit => '<option value="' + escape(unit) + '">' + escape(unit) + '</option>').join('');
            if (availableUnits.includes(previous)) $(id).value = previous;
        });
    }
    function selectManualProductUnit() {
        const product = inventoryProducts.find(item => String(item.id) === $('product').value);
        if (product && product.standard_unit) $('create-unit').value = product.standard_unit;
        updateManualSpecification();
    }
    populateInventoryUnits();
    document.querySelector('[data-target="inventory"]').addEventListener('click', () => run(async () => {
        const products = await api('/products');
        const previousProduct = $('product').value;
        inventoryProducts = products;
        populateInventoryUnits();
        $('product').innerHTML = products.map(p=>'<option value="'+p.id+'">'+escape(p.normalized_name)+'</option>').join('');
        if (products.some(product => String(product.id) === previousProduct)) {
            $('product').value = previousProduct;
            updateManualSpecification();
        } else if (products.length) {
            $('product').value = String(products[0].id);
            selectManualProductUnit();
        }
        await refresh();
    }));
    $('product').onchange = selectManualProductUnit;
    $('create-unit').onchange = updateManualSpecification;
    $('sort-order').onchange = () => {
        depletionAscending = $('sort-order').value !== 'desc';
        renderInventoryList();
    };
    $('list').onclick = event => {
        const sortButton = event.target.closest('[data-inventory-sort]');
        if (sortButton && sortButton.dataset.inventorySort === 'depletion') {
            depletionAscending = !depletionAscending;
            renderInventoryList();
            return;
        }
        const button = event.target.closest('[data-inventory-id]');
        if (button) run(async () => {
            await open(button.dataset.inventoryId);
            // 仅在用户打开配置时同步手动入库商品，不在保存或刷新时覆盖用户另选的商品及单位。
            const productId = String(selected.normalized_product_id);
            if ($('product').value !== productId && inventoryProducts.some(product => String(product.id) === productId)) {
                $('product').value = productId;
                selectManualProductUnit();
            }
        });
    };
    $('create-form').onsubmit = event => { event.preventDefault(); run(async () => {
        const row = await mutate('', {normalizedProductId:Number($('product').value), quantity:$('create-quantity').value, unit:$('create-unit').value, specification:$('create-specification').value || null, receiptDate:$('create-date').value || null, note:$('create-note').value || null});
        await refresh(); await open(row.id);
    }); };
    $('quantity-form').onsubmit = event => {
        event.preventDefault();
        // 未编辑的两位显示值仍提交原精度；数量或单位有变化时采用用户输入。
        const original = selected && displayedValue(selected,'estimated_quantity');
        const quantity = original != null && $('quantity').value === formattedQuantity(original) && $('unit').value === displayedUnit(selected)
            ? original : $('quantity').value;
        run(()=>change('set',{quantity,unit:$('unit').value}));
    };
    $('reset').onclick = () => run(()=>change('reset',{}));
    $('source').onchange = updateConsumptionFields;
    updateConsumptionFields();
    $('settings-form').onsubmit = event => { event.preventDefault(); run(()=>change('settings',{
        source:$('source').value, referenceQuantity:$('source').value === 'MANUAL' ? $('reference-quantity').value || null : null,
        referenceUnit:$('source').value === 'MANUAL' ? $('reference-unit').value : null,
        referenceDays:$('source').value === 'MANUAL' ? $('reference-days').value || null : null, leadDays:Number($('lead').value)
    })); };
}());
