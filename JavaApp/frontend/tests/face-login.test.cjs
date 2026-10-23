const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const elements = new Map();
function element(id) {
    if (!elements.has(id)) {
        const classes = new Set();
        const children = [];
        elements.set(id, {
            style: {}, value: '', innerText: '', disabled: false, children,
            get innerHTML() { return this.html || ''; },
            set innerHTML(value) { this.html = value; children.length = 0; },
            classList: {
                add: name => classes.add(name),
                remove: name => classes.delete(name),
                contains: name => classes.has(name),
                toggle: (name, enabled) => {
                    const add = enabled === undefined ? !classes.has(name) : enabled;
                    if (add) classes.add(name); else classes.delete(name);
                }
            },
            getContext: () => ({}), addEventListener() {}, appendChild(child) { children.push(child); }, focus() {},
            querySelector(selector) {
                return children.find(child => (child.className || '').split(' ').includes(selector.slice(1)));
            },
            getTracks: () => [], scrollHeight: 40, remove() {}
        });
    }
    return elements.get(id);
}
class FakeSocket {
    static OPEN = 1;
    constructor() { this.readyState = 1; this.sent = []; FakeSocket.latest = this; }
    send(message) { this.sent.push(JSON.parse(message)); }
}
const context = vm.createContext({
    window: { addEventListener() {} },
    document: {
        getElementById: element, querySelectorAll: () => [],
        createElement: () => element('new-element-' + elements.size), visibilityState: 'visible'
    },
    WebSocket: FakeSocket, console, requestAnimationFrame() {}, setTimeout() {},
    navigator: {}, localStorage: { getItem: () => null, setItem() {}, removeItem() {} }
});
vm.runInContext(fs.readFileSync(path.join(__dirname, '../app.js'), 'utf8'), context);
vm.runInContext('startWebcamPreview = () => {};', context);
const socket = FakeSocket.latest;
function receive(data) { socket.onmessage({ data: JSON.stringify(data) }); }

receive({ type: 'login_result', success: false, message: 'Face not recognized.' });
assert.equal(element('login-error').innerText, 'Face not recognized.');
assert.equal(element('app-view').classList.contains('active'), false);

receive({ type: 'login_result', success: true, user_id: 1, session_id: 1 });
assert.equal(element('app-view').classList.contains('active'), true);
vm.runInContext('startFaceReenrollment()', context);
assert.equal(socket.sent.at(-1).action, 'begin_face_reenrollment');
assert.equal(socket.sent.some(message => message.action === 'setup_begin'), false);
receive({ type: 'face_reenrollment_started', success: true });
assert.equal(element('setup-card-face').classList.contains('active'), true);
assert.equal(element('btn-skip-face').innerText, 'Cancel');
vm.runInContext('skipFaceSetup()', context);
assert.equal(element('app-view').classList.contains('active'), true);
assert.equal(element('setup-wizard-view').classList.contains('active'), false);

receive({ type: 'face_reenrollment_started', success: true });
vm.runInContext('currentFaceAngleIdx = 3; updateFaceAngleUI()', context);
assert.equal(element('app-view').classList.contains('active'), true);
assert.equal(element('setup-card-interview').classList.contains('active'), false);
vm.runInContext('startGuidedSetupWizard()', context);
assert.equal(socket.sent.at(-1).action, 'setup_begin');
assert.equal(element('setup-card-welcome').classList.contains('active'), true);
assert.equal(element('btn-skip-face').innerText, 'Skip Face Login');
console.log('Face login and reenrollment UI tests passed.');

receive({ type: 'login_result', success: true, user_id: 1, session_id: 10 });
receive({ type: 'conversations_list', user_id: 1, session_id: 10,
    conversations: [{ conversation_id: 101 }] });
assert.equal(socket.sent.at(-1).conversation_id, 101);
receive({ type: 'messages_list', user_id: 1, session_id: 10, conversation_id: 101,
    messages: [{ role: 'user', content: 'A private question' }, { role: 'assistant', content: 'A private answer' }] });
assert.equal(element('chat-box').children.length, 2);
element('text-chat-input').value = 'A unsent draft';
receive({ type: 'chat_start', user_id: 1, session_id: 10 });
vm.runInContext('logout()', context);
assert.equal(element('chat-box').children.length, 0);
assert.equal(element('text-chat-input').value, '');
receive({ type: 'chat_token', user_id: 1, session_id: 10, text: 'late A reply' });
assert.equal(element('chat-box').children.length, 0);
receive({ type: 'login_result', success: true, user_id: 2, session_id: 11 });
receive({ type: 'messages_list', user_id: 1, session_id: 10, conversation_id: 101,
    messages: [{ role: 'assistant', content: 'late A history' }] });
receive({ type: 'chat_start', user_id: 1, session_id: 10 });
receive({ type: 'chat', user_id: 1, session_id: 10, sender: 'AI', text: 'late A reply' });
assert.equal(element('chat-box').children.length, 0);
receive({ type: 'conversations_list', user_id: 2, session_id: 11, conversations: [{ conversation_id: 202 }] });
receive({ type: 'messages_list', user_id: 2, session_id: 11, conversation_id: 202,
    messages: [{ role: 'user', content: 'B private question' }] });
assert.equal(element('chat-box').children[0].innerText, 'B private question');
receive({ type: 'chat_start', user_id: 2, session_id: 11 });
receive({ type: 'chat_token', user_id: 1, session_id: 10, text: 'wrong user' });
receive({ type: 'chat_token', user_id: 2, session_id: 11, text: 'B answer' });
assert.equal(element('chat-box').children[1].innerText, 'B answer');
receive({ type: 'login_result', success: true, user_id: 1, session_id: 12 });
receive({ type: 'conversations_list', user_id: 1, session_id: 12, conversations: [{ conversation_id: 101 }] });
receive({ type: 'messages_list', user_id: 1, session_id: 12, conversation_id: 101,
    messages: [{ role: 'user', content: 'A private question' }] });
assert.equal(element('chat-box').children[0].innerText, 'A private question');
receive({ type: 'chat', user_id: 1, session_id: 10, sender: 'AI', text: 'old session of same user' });
assert.equal(element('chat-box').children.length, 1);
console.log('Account switching, private history, and stale response UI tests passed.');

receive({ type: 'memory_reset_result', success: true, user_id: 1,
    previous_session_id: 12, session_id: 13, message: 'Memory cleared.' });
assert.equal(element('chat-box').children.length, 0);
receive({ type: 'chat', user_id: 1, session_id: 12, sender: 'AI', text: 'pre-reset reply' });
receive({ type: 'messages_list', user_id: 1, session_id: 12, conversation_id: 101,
    messages: [{ role: 'assistant', content: 'pre-reset history' }] });
assert.equal(element('chat-box').children.length, 0);
receive({ type: 'chat', user_id: 1, session_id: 13, sender: 'You', text: 'New conversation' });
assert.equal(element('chat-box').children[0].innerText, 'New conversation');
receive({ type: 'memory_reset_result', success: false, user_id: 1,
    previous_session_id: 13, session_id: 14, message: 'Reset failed.' });
assert.equal(element('chat-box').children.length, 1, 'Failed reset must not falsely clear the panel');
receive({ type: 'memory_reset_result', success: true, user_id: 2,
    previous_session_id: 14, session_id: 15, message: 'Wrong user reset' });
assert.equal(element('chat-box').children.length, 1);
console.log('Memory reset UI tests passed.');
