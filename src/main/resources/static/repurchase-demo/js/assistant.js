import MarkdownIt from '../vendor/markdown-it-15.0.3.min.js';

const CHAT_ENDPOINT = '/api/assistant/chat/stream';
const MAX_HISTORY_TURNS = 4;
const MAX_HISTORY_ANSWER_LENGTH = 4000;
const SCROLL_TOLERANCE = 32;
const markdown = new MarkdownIt({html: false, breaks: true}).disable('image');

export function renderAssistantMarkdown(text) {
    return markdown.render(text);
}

export async function readAssistantStream(response, onDelta) {
    if (!response.ok) {
        const result = await response.json().catch(() => null);
        throw new Error(result?.error || '暂时无法收到回复，请重试。');
    }
    if (!response.body) throw new Error('浏览器未收到回复流，请重试。');

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    try {
        while (true) {
            const {done, value} = await reader.read();
            buffer += done ? decoder.decode() : decoder.decode(value, {stream: true});
            let lineEnd = buffer.indexOf('\n');
            while (lineEnd !== -1) {
                const line = buffer.slice(0, lineEnd);
                buffer = buffer.slice(lineEnd + 1);
                if (line.trim() && handleStreamLine(line, onDelta)) return;
                lineEnd = buffer.indexOf('\n');
            }
            if (done) {
                if (buffer.trim() && handleStreamLine(buffer, onDelta)) return;
                throw new Error('回复提前结束，请重试。');
            }
        }
    } finally {
        await reader.cancel().catch(() => {});
        reader.releaseLock();
    }
}

function handleStreamLine(line, onDelta) {
    let event;
    try {
        event = JSON.parse(line);
    } catch {
        throw new Error('回复格式异常，请重试。');
    }
    if (event?.error) throw new Error(event.error);
    if (typeof event?.delta === 'string') onDelta(event.delta);
    return event?.done === true;
}

export function initAssistant() {
    const form = document.getElementById('assistant-form');
    const input = document.getElementById('assistant-message');
    const messages = document.getElementById('assistant-messages');
    const sendButton = document.getElementById('assistant-send');
    const status = document.getElementById('assistant-status');
    const history = [];

    function appendMessage(role, text) {
        const item = document.createElement('li');
        item.className = 'assistant-message assistant-message--' + role;
        const sender = document.createElement('strong');
        sender.textContent = role === 'user' ? '你' : '复购助手';
        const bubble = document.createElement(role === 'user' ? 'p' : 'div');
        bubble.textContent = text;
        if (role === 'assistant') bubble.className = 'assistant-reply';
        item.append(sender, bubble);
        messages.appendChild(item);
        return {item, bubble};
    }

    form.addEventListener('submit', async event => {
        event.preventDefault();
        const question = input.value.trim();
        if (sendButton.disabled) return;
        if (!question) {
            status.textContent = '请输入消息。';
            return;
        }

        const pendingMessage = appendMessage('user', question);
        messages.scrollTop = messages.scrollHeight;
        input.value = '';
        input.readOnly = true;
        sendButton.disabled = true;
        status.textContent = '正在等待回复…';
        let answer = '';
        let reply;
        let frame = null;
        let completed = false;
        function renderReply() {
            frame = null;
            const shouldFollow = messages.scrollHeight - messages.clientHeight - messages.scrollTop <= SCROLL_TOLERANCE;
            reply ??= appendMessage('assistant', '');
            reply.bubble.innerHTML = renderAssistantMarkdown(answer);
            if (shouldFollow) messages.scrollTop = messages.scrollHeight;
        }

        try {
            const response = await fetch(CHAT_ENDPOINT, {
                method: 'POST',
                headers: {'Content-Type': 'application/json', Accept: 'application/x-ndjson, application/json'},
                body: JSON.stringify({message: question, history})
            }).catch(() => {
                throw new Error('聊天连接失败，请重试。');
            });
            await readAssistantStream(response, delta => {
                answer += delta;
                status.textContent = '正在接收回复…';
                // 同一帧合并多个片段，用完整回复渲染尚未闭合的列表或表格。
                if (frame === null) frame = requestAnimationFrame(renderReply);
            });
            if (!answer.trim()) throw new Error('模型未返回回复，请重试。');
            history.push({question, answer: answer.slice(0, MAX_HISTORY_ANSWER_LENGTH)});
            if (history.length > MAX_HISTORY_TURNS) history.shift();
            completed = true;
            status.textContent = '';
        } catch (error) {
            status.textContent = (answer.trim() ? '回复已中断。' : '') + error.message;
        } finally {
            if (frame !== null) cancelAnimationFrame(frame);
            if (answer.trim()) renderReply();
            // 保留已显示的片段，只有正常结束的问答才进入后续上下文。
            if (!completed) {
                if (!answer.trim()) {
                    pendingMessage.item.remove();
                    reply?.item.remove();
                }
                input.value = question;
            }
            input.readOnly = false;
            sendButton.disabled = false;
        }
    });
}
