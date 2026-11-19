// 记录样本的订单截图识别与预览。
import { endpoints } from "./config.js";
import { invalidateRecordReview, syncRecordRuleSelectionByName, updateRecordMoreSummary, updateRecordNameEditor, updateRecordTotalQuantity } from "./record.js";
import { $, compact, formValues, formatAmount, formatPlatform, formatQuantity, htmlEscape, isEmptyValue, metric, optionalText, postJson, renderError, setLoading } from "./shared.js";
import { state } from "./state.js";

export function inferImageMimeType(file) {
    if (!file) return undefined;
    if (file.type) return file.type;
    var name = String(file.name || '').toLowerCase();
    if (name.endsWith('.png')) return 'image/png';
    if (name.endsWith('.jpg') || name.endsWith('.jpeg')) return 'image/jpeg';
    if (name.endsWith('.webp')) return 'image/webp';
    return undefined;
}

export function isSupportedImageFile(file) {
    var mimeType = inferImageMimeType(file);
    return ['image/png', 'image/jpeg', 'image/webp'].indexOf(mimeType) >= 0;
}

export function readFileAsDataUrl(file) {
    return new Promise(function (resolve, reject) {
        var reader = new FileReader();
        reader.onload = function () { resolve(reader.result); };
        reader.onerror = function () { reject(new Error('图片读取失败，请重新选择图片。')); };
        reader.readAsDataURL(file);
    });
}

export function hideRecordImagePreview() {
    var preview = $('record-image-preview');
    var previewImg = $('record-image-preview-img');
    var modalImg = $('record-image-modal-img');
    if (preview) { preview.classList.add('hidden'); preview.open = false; }
    if (previewImg) previewImg.removeAttribute('src');
    if (modalImg) modalImg.removeAttribute('src');
}

export function renderRecordImagePreview(imageDataUrl, fileName) {
    var preview = $('record-image-preview');
    var previewImg = $('record-image-preview-img');
    var previewTitle = $('record-image-preview-title');
    var modalImg = $('record-image-modal-img');
    var modalTitle = $('record-image-modal-title');
    if (!preview || !previewImg) return;
    preview.classList.remove('hidden');
    preview.open = false;
    previewImg.src = imageDataUrl;
    if (previewTitle) previewTitle.textContent = fileName || '订单截图预览';
    if (modalImg) modalImg.src = imageDataUrl;
    if (modalTitle) modalTitle.textContent = fileName || '订单截图预览';
}

export function openRecordImagePreview() {
    var modal = $('record-image-modal');
    if (!modal || !state.lastSelectedImageDataUrl) return;
    modal.classList.remove('hidden');
    modal.setAttribute('aria-hidden', 'false');
}

export function closeRecordImagePreview() {
    var modal = $('record-image-modal');
    if (!modal) return;
    modal.classList.add('hidden');
    modal.setAttribute('aria-hidden', 'true');
}

export function normalizeDateForInput(value) {
    if (isEmptyValue(value)) return '';
    var text = String(value).trim();
    var matched = text.match(/^\d{4}-\d{2}-\d{2}/);
    return matched ? matched[0] : '';
}

export function setRecordField(name, value) {
    var field = document.querySelector('#record-form [name="' + name + '"]');
    if (!field) return;
    var text = isEmptyValue(value) ? '' : String(value);
    field.value = text;
    // 保留截图中已识别的其他平台，不能因下拉选项缺失而静默丢掉原值。
    if (name === 'platform' && field.tagName === 'SELECT' && text && field.value !== text) {
        var option = document.createElement('option');
        option.value = text;
        option.textContent = formatPlatform(text);
        field.appendChild(option);
        field.value = text;
    }
}

export function candidateNormalizedName(candidate) {
    if (!candidate) return '';
    if (candidate.normalization && candidate.normalization.normalizedName) {
        return candidate.normalization.normalizedName;
    }
    return candidate.normalizedName || '';
}

export function candidateWarnings(candidate) {
    if (!candidate) return [];
    // 页面仅展示可操作的核对提示，接口中的模型、进程和规则诊断保留给后台排查。
    var warnings = [];
    if (!candidate.productName) warnings.push('请补充商品名称');
    if (!candidate.sku) warnings.push('请核对商品规格');
    if (isEmptyValue(candidate.price)) warnings.push('请填写实付金额');
    if (isEmptyValue(candidate.purchaseQuantity)) warnings.push('请填写购买数量');
    if (isEmptyValue(candidate.quantity) || !candidate.unit) warnings.push('请确认每件含量和计量单位');
    if (Array.isArray(candidate.warnings) && candidate.warnings.some(function (warning) {
        return String(warning).indexOf('数量不一致') >= 0;
    })) warnings.push('规格数量可能不一致，请核对');
    if (Array.isArray(candidate.warnings) && candidate.warnings.some(function (warning) {
        return String(warning).indexOf('商品标题未完整显示') >= 0;
    })) warnings.push('请展开商品标题后重新截图，或手动补充商品名称');
    if (Array.isArray(candidate.warnings) && candidate.warnings.some(function (warning) {
        return String(warning).indexOf('次卡') >= 0;
    })) warnings.push('次卡需核对全部权益总含量，并确认实付金额对应整张卡');
    if (/\.{2,}|…/.test((candidate.productName || '') + (candidate.sku || ''))) {
        warnings.push('截图中的文字未显示完整，请核对补充');
    }
    if (candidate.normalization && !candidate.normalization.matched) warnings.push('请手动选择商品分类');
    return warnings;
}

export function fillRecordFormFromCandidate(candidate) {
    if (!candidate) return;
    invalidateRecordReview();
    state.activeParsedPurchaseCandidate = candidate;
    setRecordField('owner', candidate.owner);
    setRecordField('purchaseDate', normalizeDateForInput(candidate.purchaseDate));
    $('record-receive-inventory').checked = false;
    setRecordField('productName', candidate.productName);
    setRecordField('normalizedName', candidateNormalizedName(candidate));
    setRecordField('sku', candidate.sku);
    setRecordField('skuQuantity', candidate.quantity);
    setRecordField('price', candidate.price);
    setRecordField('purchaseQuantity', candidate.purchaseQuantity);
    setRecordField('unit', candidate.unit);
    updateRecordTotalQuantity();
    syncRecordRuleSelectionByName(false);
    setRecordField('platform', candidate.platform);
    setRecordField('shopName', candidate.shopName);

    state.lastGeneratedOcrNote = formatParsedOrderNote(candidate, candidateNormalizedName(candidate));
    setRecordField('note', state.lastGeneratedOcrNote);
    setRecordField('sourceText', candidate.sourceText || (state.lastParseOrderImageResult && state.lastParseOrderImageResult.rawText) || '');
    invalidateRecordReview();
    updateRecordNameEditor(true);
    updateRecordMoreSummary();
}

export function formatParsedOrderNote(candidate, normalizedName) {
    var normalized = String(normalizedName || '').trim().length > 0;
    var note = '【OCR解析】-【' + (normalized ? '已归一化' : '未归一化') + '】';
    var giftDetails = candidate && String(candidate.giftDetails || '').trim();
    return giftDetails ? note + '；赠品：' + giftDetails : note;
}

export function updateParsedOrderNote() {
    if (!state.activeParsedPurchaseCandidate) return;
    var note = document.querySelector('#record-form [name="note"]');
    var normalizedName = $('record-normalized-name');
    // 人工改写过的备注保留原文；仅自动生成的备注随归一化状态更新。
    if (!note || !normalizedName || note.value !== state.lastGeneratedOcrNote) return;
    state.lastGeneratedOcrNote = formatParsedOrderNote(state.activeParsedPurchaseCandidate, normalizedName.value);
    note.value = state.lastGeneratedOcrNote;
}

export function buildParseOrderImagePayload(file, imageDataUrl) {
    var value = formValues('record-form');
    return compact({
        imageBase64: imageDataUrl,
        imageFileName: file ? file.name : undefined,
        imageMimeType: inferImageMimeType(file),
        owner: optionalText(value.owner),
        parseMode: 'order_screenshot',
        dryRun: true
    });
}

export function renderCandidateAction(index, selectedIndex) {
    var selected = index === selectedIndex;
    return '<button class="btn btn-secondary btn-small" type="button" data-use-parsed-candidate="' + index + '">' +
        (selected ? '已填入' : '填入表单') +
        '</button>';
}

export function candidateSaveStatus(candidate) {
    var status = candidate && candidate.duplicate && candidate.duplicate.status;
    if (status === 'saved') return '已上传';
    if (status === 'possible') return '可能重复';
    return '待确认';
}

export function renderOrderImageCandidateTable(candidates, selectedIndex) {
    if (!candidates || !candidates.length) return '';
    var html = '<div class="table-wrap"><table><thead><tr>' +
        '<th>操作</th><th>记录状态</th><th>商品名称</th><th>商品分类</th><th>规格</th><th>实付金额</th><th>购买数量</th><th>每件含量</th><th>平台</th><th>归属人</th><th>购买日期</th><th>店铺</th><th>核对提示</th>' +
        '</tr></thead><tbody>';
    candidates.forEach(function (candidate, index) {
        html += '<tr>' +
            '<td>' + renderCandidateAction(index, selectedIndex) + '</td>' +
            '<td>' + htmlEscape(candidateSaveStatus(candidate)) + '</td>' +
            '<td class="wrap">' + htmlEscape(candidate.productName) + '</td>' +
            '<td>' + htmlEscape(candidateNormalizedName(candidate) || '—') + '</td>' +
            '<td class="wrap">' + htmlEscape(candidate.sku) + '</td>' +
            '<td>' + htmlEscape(formatAmount(candidate.price)) + '</td>' +
            '<td>' + htmlEscape(formatQuantity(candidate.purchaseQuantity)) + '</td>' +
            '<td>' + htmlEscape(isEmptyValue(candidate.quantity) ? '—' :
                formatQuantity(candidate.quantity) + ' ' + (candidate.unit || '')) + '</td>' +
            '<td>' + htmlEscape(formatPlatform(candidate.platform)) + '</td>' +
            '<td>' + htmlEscape(candidate.owner) + '</td>' +
            '<td>' + htmlEscape(candidate.purchaseDate) + '</td>' +
            '<td class="wrap">' + htmlEscape(candidate.shopName) + '</td>' +
            '<td class="wrap">' + htmlEscape(candidateWarnings(candidate).join('；') || '—') + '</td>' +
            '</tr>';
    });
    html += '</tbody></table></div>';
    return html;
}

export function bindParsedCandidateButtons() {
    Array.prototype.forEach.call(document.querySelectorAll('[data-use-parsed-candidate]'), function (button) {
        button.addEventListener('click', function () {
            var index = Number(button.dataset.useParsedCandidate);
            var candidate = state.lastParsedPurchaseCandidates[index];
            if (!candidate) return;
            fillRecordFormFromCandidate(candidate);
            renderParseOrderImageResult(state.lastParseOrderImageResult, index);
        });
    });
}

export function orderImageRecognitionSourceText(source) {
    if (source === 'local') return '本次使用本地模型解析。';
    if (source === 'llm') return '本次使用 LLM 模型解析。';
    return '本次已完成截图解析。';
}

export function renderParseOrderImageResult(data, selectedIndex) {
    var candidates = data && Array.isArray(data.candidates) ? data.candidates : [];
    var selectedCandidate = candidates[selectedIndex];
    var saveStatus = candidateSaveStatus(selectedCandidate);
    var duplicateStatus = selectedCandidate && selectedCandidate.duplicate && selectedCandidate.duplicate.status;
    var html = '<div class="result-head"><div><h3>已识别订单信息</h3><p>' +
        htmlEscape(data && data.imagePath ? data.imagePath : '订单截图') +
        '</p></div><span class="tag">' + htmlEscape(saveStatus) + '</span></div>';
    html += '<div class="metrics image-result-metrics">';
    html += metric('识别商品', formatQuantity(data && !isEmptyValue(data.candidateCount) ? data.candidateCount : candidates.length), '');
    html += metric('已填入', candidates.length ? '第 ' + (selectedIndex + 1) + ' 条' : '—', '');
    html += metric('保存状态', saveStatus, '');
    html += '</div>';
    if (duplicateStatus === 'saved') {
        html += '<div class="reason-card duplicate-notice" role="status"><h4>该订单记录已上传</h4>' +
            '已找到该订单下的相同商品记录，无需再次保存。</div>';
    } else if (duplicateStatus === 'possible') {
        html += '<div class="reason-card duplicate-notice" role="status"><h4>发现可能重复的记录</h4>' +
            '历史记录中有相同的购买信息，请先核对是否已经保存。</div>';
    }
    html += '<div class="reason-card"><h4>解析提示</h4>' +
        htmlEscape(orderImageRecognitionSourceText(data && data.recognitionSource));

    if (!candidates.length) {
        html += '请换一张清晰完整的截图，或手动填写。</div>';
        html += '<div class="empty-state"><div><strong>没有识别到商品信息</strong><span>可以换一张更清晰的订单截图，或手动填写表单。</span></div></div>';
    } else {
        html += '已将第 ' + htmlEscape(selectedIndex + 1) + ' 件商品填入左侧表单。';
        if (duplicateStatus === 'saved') html += '该商品已有购买记录，本次解析没有重复保存。';
        else if (duplicateStatus === 'possible') html += '请核对历史记录，确认是另一笔购买后再保存。';
        else {
            html += '请核对商品名称、规格、实付金额和购买数量，确认后保存。';
            if (!duplicateStatus || duplicateStatus === 'unavailable') html += '本次尚未完成历史记录核对。';
        }
        html += '</div>';
        var reviewWarnings = candidateWarnings(selectedCandidate).filter(function (warning) {
            return warning.indexOf('展开商品标题') >= 0 || warning.indexOf('次卡') >= 0;
        });
        if (reviewWarnings.length) {
            html += '<div class="reason-card" role="status"><h4>请核对</h4>' +
                htmlEscape(reviewWarnings.join('；')) + '</div>';
        }
        html += '<h4 class="section-title">识别结果</h4>';
        html += renderOrderImageCandidateTable(candidates, selectedIndex);
    }

    $('record-result').className = '';
    $('record-result').innerHTML = html;
    bindParsedCandidateButtons();
}

export function updateRecordImageFileInfo() {
    var fileInput = $('record-image-file');
    return selectRecordImageFile(fileInput && fileInput.files ? fileInput.files[0] : null);
}

export function transferredImageFiles(transfer) {
    if (!transfer) return [];
    var files = Array.prototype.slice.call(transfer.files || []);
    if (files.length) return files;
    Array.prototype.forEach.call(transfer.items || [], function (item) {
        if (item.kind !== 'file') return;
        var file = item.getAsFile();
        if (file) files.push(file);
    });
    return files;
}

export function receiveRecordImageFiles(files) {
    var info = $('record-image-file-info');
    if (!files.length) {
        info.textContent = '未读取到图片，请复制图片后粘贴，或点击“选择截图”。';
        return Promise.resolve();
    }
    if (files.length !== 1) {
        info.textContent = '一次只能添加一张订单截图，请重新选择。';
        return Promise.resolve();
    }
    // 清除文件选择器中的旧文件，解析始终使用统一的当前图片状态。
    $('record-image-file').value = '';
    return selectRecordImageFile(files[0]);
}

export function handleRecordImageDrop(event) {
    event.preventDefault();
    event.stopPropagation();
    state.orderImageDragDepth = 0;
    $('record-image-drop-zone').classList.remove('drag-over');
    return receiveRecordImageFiles(transferredImageFiles(event.dataTransfer));
}

export function handleRecordImagePaste(event) {
    if (event.defaultPrevented || !$('record').classList.contains('active')) return;
    var files = transferredImageFiles(event.clipboardData);
    // 普通文字粘贴保持原样；只有记录样本页收到实际图片文件时才接管粘贴操作。
    if (!files.length) return;
    event.preventDefault();
    return receiveRecordImageFiles(files);
}

export function bindRecordImageTransferEvents() {
    var imageDropZone = $('record-image-drop-zone');
    if (imageDropZone) {
        imageDropZone.addEventListener('dragenter', function (event) {
            event.preventDefault();
            state.orderImageDragDepth += 1;
            imageDropZone.classList.add('drag-over');
        });
        imageDropZone.addEventListener('dragover', function (event) {
            event.preventDefault();
            if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
        });
        imageDropZone.addEventListener('dragleave', function (event) {
            event.preventDefault();
            state.orderImageDragDepth = Math.max(0, state.orderImageDragDepth - 1);
            if (!state.orderImageDragDepth) imageDropZone.classList.remove('drag-over');
        });
        imageDropZone.addEventListener('drop', handleRecordImageDrop);
    }
    document.addEventListener('paste', handleRecordImagePaste);
}

export async function selectRecordImageFile(file) {
    var info = $('record-image-file-info');
    var fileName = $('record-image-file-name');
    if (!info) return;
    var revision = ++state.orderImageSelectionRevision;
    state.selectedOrderImageFile = file || null;
    invalidateRecordReview();
    state.selectedOrderImageReadPromise = null;
    state.lastSelectedImageDataUrl = null;
    state.lastSelectedImageFileName = '';
    state.lastParseOrderImageResult = null;
    state.lastParsedPurchaseCandidates = [];
    state.activeParsedPurchaseCandidate = null;
    state.lastGeneratedOcrNote = '';
    hideRecordImagePreview();
    closeRecordImagePreview();
    $('record-result').className = 'empty-state';
    $('record-result').innerHTML = '<div><strong>等待截图解析</strong><span>添加截图后，点击“订单截图解析”查看识别结果。</span></div>';
    if (!file) {
        if (fileName) fileName.textContent = '未选择文件';
        info.textContent = '支持 PNG、JPG、JPEG、WEBP 图片，可选择、拖入或粘贴。';
        return;
    }
    var sizeKb = file.size ? Math.round(file.size / 1024) : 0;
    var displayName = file.name || '订单截图';
    if (fileName) fileName.textContent = displayName;
    state.lastSelectedImageFileName = displayName;
    if (!isSupportedImageFile(file)) {
        info.textContent = '当前图片格式不支持，请选择 PNG、JPG、JPEG 或 WEBP 图片。';
        return;
    }
    info.textContent = '已添加截图，约 ' + sizeKb + ' KB，点击“解析”继续。';
    try {
        state.selectedOrderImageReadPromise = readFileAsDataUrl(file);
        var imageDataUrl = await state.selectedOrderImageReadPromise;
        if (revision !== state.orderImageSelectionRevision) return;
        state.lastSelectedImageDataUrl = imageDataUrl;
        renderRecordImagePreview(imageDataUrl, displayName);
    } catch (error) {
        if (revision !== state.orderImageSelectionRevision) return;
        state.selectedOrderImageReadPromise = null;
        info.textContent = error.message || '图片预览失败，请重新选择图片。';
    }
}

export async function parseOrderImage(button) {
    var file = state.selectedOrderImageFile;
    var revision = state.orderImageSelectionRevision;
    if (!file) {
        renderError('record-result', '请选择订单截图', new Error('请先选择一张 PNG、JPG、JPEG 或 WEBP 图片。'));
        return;
    }
    if (!isSupportedImageFile(file)) {
        renderError('record-result', '图片格式不支持', new Error('当前只支持 PNG、JPG、JPEG、WEBP 图片。'));
        return;
    }

    setLoading(button, true);
    try {
        var imageDataUrl = state.lastSelectedImageDataUrl || await (state.selectedOrderImageReadPromise || readFileAsDataUrl(file));
        if (revision !== state.orderImageSelectionRevision) return;
        state.lastSelectedImageDataUrl = imageDataUrl;
        state.lastSelectedImageFileName = file.name;
        renderRecordImagePreview(imageDataUrl, file.name);
        var payload = buildParseOrderImagePayload(file, imageDataUrl);
        var data = await postJson(endpoints.parseOrderImage, payload);
        // 上传新截图后，旧截图的识别结果不能再回填表单或覆盖提示。
        if (revision !== state.orderImageSelectionRevision) return;
        state.lastParseOrderImageResult = data || {};
        state.lastParsedPurchaseCandidates = Array.isArray(state.lastParseOrderImageResult.candidates)
            ? state.lastParseOrderImageResult.candidates
            : [];
        if (state.lastParsedPurchaseCandidates.length) {
            fillRecordFormFromCandidate(state.lastParsedPurchaseCandidates[0]);
        }
        renderParseOrderImageResult(state.lastParseOrderImageResult, 0);
    } catch (error) {
        if (revision !== state.orderImageSelectionRevision) return;
        renderError('record-result', '暂时无法识别这张截图',
            new Error('请稍后重试，或换一张清晰完整的截图，也可以手动填写。'));
    } finally {
        setLoading(button, false);
    }
}
