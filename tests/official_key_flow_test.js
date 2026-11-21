const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const officialUrl = 'https://platform.deepseek.com/api_keys';
const syntheticKey = 'sk-' + '0123456789abcdef'.repeat(2);

// A minimal test DOM for the public platform component structure.
class Element {
    constructor(tag, {role, classes = '', text = '', placeholder, click, style = {}} = {}) {
        this.tagName = tag.toUpperCase();
        this.parentElement = null;
        this.children = [];
        this.attributes = {role, placeholder};
        this.classList = new Set(classes.split(' '));
        this.classList.contains = this.classList.has.bind(this.classList);
        this.style = {display: 'block', visibility: 'visible', opacity: '1', ...style};
        this.text = text;
        this.onClick = click;
        this.clicks = 0;
        this.listeners = {};
    }

    append(...children) {
        for (const child of children) {
            child.parentElement = this;
            this.children.push(child);
        }
    }

    replaceChildren(...children) {
        for (const child of this.children) child.parentElement = null;
        this.children = [];
        this.append(...children);
    }

    querySelectorAll(selector) {
        const matches = (node, part) => {
            const attributes = [...part.matchAll(/\[([\w-]+)(?:="([^"]+)")?\]/g)];
            const classes = [...part.matchAll(/\.([\w-]+)/g)].map(match => match[1]);
            const tag = part.match(/^[a-z][a-z0-9]*/)?.[0];
            return (!tag || tag.toUpperCase() === node.tagName)
                && attributes.every(([, name, value]) => value === undefined
                    ? node.getAttribute(name) !== null : node.getAttribute(name) === value)
                && classes.every(name => node.classList.contains(name));
        };
        const selectors = selector.split(',').map(part => part.trim());
        return this.children.flatMap(child => [
            ...(selectors.some(part => matches(child, part)) ? [child] : []),
            ...child.querySelectorAll(selector)
        ]);
    }

    get innerText() {
        return this.text + this.children.map(child => child.innerText).join('');
    }

    getAttribute(name) { return this.attributes[name] ?? null; }
    getClientRects() { return [{width: 180, height: 30}]; }
    getBoundingClientRect() { return {left: 0, top: 0, width: 24, height: 24}; }
    contains(node) {
        for (; node; node = node.parentElement) if (node === this) return true;
        return false;
    }
    addEventListener(event, listener) { (this.listeners[event] ??= []).push(listener); }
    click() { this.clicks++; this.listeners.click?.forEach(listener => listener()); this.onClick?.(); }
}

class Input extends Element {
    constructor(placeholder) {
        super('input', {placeholder});
        this.type = 'text';
        this.value = '';
    }

    get value() { return this.currentValue; }
    set value(value) { this.currentValue = value; }
    dispatchEvent(event) { this.onInput?.(event); }
}

class InputEvent {
    constructor(type, {bubbles}) {
        this.type = type;
        this.bubbles = bubbles;
    }
}

const languages = {
    zh: {open: '创建 API key', name: '输入 API key 的名称', cancel: '取消', create: '创建', copy: '复制'},
    en: {open: 'Create new API key', name: 'Name of the API key', cancel: 'Cancel', create: 'Create API key', copy: 'Copy'}
};

function button(text, click, variant = 'filled') {
    const node = new Element('div', {role: 'button', classes: `ds-button ds-button--${variant}`, click});
    node.append(new Element('span', {classes: 'ds-button__content', text}));
    return node;
}

function fixture(source, language = 'zh', url = officialUrl, embeddedFrame = false) {
    const labels = languages[language];
    const document = new Element('html');
    const dialog = new Element('div', {role: 'dialog', classes: 'ds-modal-content'});
    const title = new Element('div', {classes: 'ds-modal-content__title', text: labels.open});
    const input = new Input(labels.name);
    const reveal = new Element('div', {classes: 'ds-copyable-text-line'});
    const secret = new Element('span', {classes: 'ds-copyable-text-line__text', text: syntheticKey});
    reveal.append(secret, button(labels.copy));
    const cancel = button(labels.cancel, null, 'outlined');
    const confirm = button(labels.create, () => dialog.replaceChildren(title, reveal));
    confirm.classList.add('ds-button--disabled');
    input.onInput = event => {
        if (event.type === 'input' && event.bubbles === true) confirm.classList.delete('ds-button--disabled');
    };
    dialog.append(title, input, cancel, confirm);
    const open = button(labels.open, () => document.append(dialog));
    const emptyStateLink = button(labels.open, () => assert.fail('The secondary table button was clicked'), 'text');
    document.append(open, emptyStateLink);
    const window = {location: new URL(url), getComputedStyle: node => node.style};
    window.top = embeddedFrame ? {} : window;
    const context = vm.createContext({window, document, HTMLInputElement: Input, Event: InputEvent});
    const run = stage => JSON.parse(JSON.stringify(vm.runInContext(`${source}(${JSON.stringify(stage)})`, context)));
    return {labels, document, dialog, title, input, reveal, secret, confirm, open, run};
}

function run() {
    const resource = path.join(__dirname, '../app/src/main/res/raw/official_key_flow.js');
    const source = fs.readFileSync(resource, 'utf8');
    const expect = (flow, stage, status) => assert.deepEqual(flow.run(stage), {status});

    function optionalEmail(flow, text = 'Bind Email') {
        const modal = new Element('div', {role: 'dialog', classes: 'ds-modal-content'});
        const title = new Element('div', {classes: 'ds-modal-content__title', text});
        const close = new Element('div', {classes: 'ds-modal-content__close', click: () => { modal.style.display = 'none'; }});
        const icon = new Element('svg');
        close.append(icon);
        const input = new Input('Email');
        Object.defineProperty(input, 'value', {get() { assert.fail('Read the email field'); }});
        const bind = button('Bind', () => assert.fail('Submitted an email binding'));
        const send = button('Send code', () => assert.fail('Requested an email code'));
        modal.append(close, title, input, send, bind);
        flow.document.append(modal);
        flow.document.elementFromPoint = () => icon;
        return {modal, close, icon, title, bind};
    }

    // The live platform exposes both an outer modal and its content as accessibility dialog nodes.
    function wrappedDialog(doc, content) {
        const shell = new Element('div', {classes: 'ds-modal'});
        shell.attributes['aria-modal'] = 'true';
        const middle = new Element('div');
        doc.replaceChildren(...doc.children.filter(node => node !== content), shell);
        content.classList.add('ds-elevated');
        middle.append(content);
        shell.append(middle);
        return shell;
    }
    const actualStructure = fixture(source, 'en');
    const actualEmail = optionalEmail(actualStructure);
    const shell = wrappedDialog(actualStructure.document, actualEmail.modal);
    actualEmail.close.onClick = () => { shell.style.display = 'none'; };
    expect(actualStructure, 'inspect', 'email');
    expect(actualStructure, 'dismissEmail', 'dismissed');
    expect(actualStructure, 'dismissEmail', 'gone');
    actualStructure.open.onClick = () => wrappedDialog(actualStructure.document, actualStructure.dialog);
    expect(actualStructure, 'open', 'opened');
    expect(actualStructure, 'prepare', 'prepared');
    expect(actualStructure, 'submit', 'submitted');
    assert.deepEqual(actualStructure.run('read'), {status: 'key', key: syntheticKey});
    assert.equal(actualStructure.open.clicks, 1);
    assert.equal(actualStructure.confirm.clicks, 1);

    const twoDialogs = fixture(source);
    const multiple = optionalEmail(twoDialogs);
    const commonShell = wrappedDialog(twoDialogs.document, multiple.modal);
    commonShell.append(new Element('div', {role: 'alertdialog'}));
    expect(twoDialogs, 'dismissEmail', 'email');
    assert.equal(multiple.close.clicks, 0);

    for (const [url, label] of [[officialUrl, 'Bind Email'], ['https://platform.deepseek.com/account/details', '绑定邮箱']]) {
        const flow = fixture(source, 'en', url);
        const {close} = optionalEmail(flow, label);
        expect(flow, 'inspect', 'email');
        assert.equal(close.clicks, 0);
        expect(flow, 'dismissEmail', 'dismissed');
        expect(flow, 'dismissEmail', 'gone');
        assert.equal(close.clicks, 1);
        assert.equal(flow.open.clicks, 0);
        expect(flow, 'inspect', url === officialUrl ? 'open' : 'clear');
    }
    for (const alter of [
        ({close}) => { close.disabled = true; },
        ({close}) => { close.style.display = 'none'; },
        ({close}) => { close.attributes['aria-disabled'] = 'true'; },
        ({close}) => { close.tagName = 'BUTTON'; close.type = 'submit'; },
        ({close}) => { close.classList.delete('ds-modal-content__close'); },
        ({modal}) => { modal.append(new Element('div', {classes: 'ds-modal-content__close'})); },
        ({title}) => { title.text = 'Verify your email'; },
        ({title}) => { title.text = '邮箱验证'; },
        ({bind}, flow) => { flow.document.elementFromPoint = () => bind; },
        (_, flow) => { flow.document.append(new Element('div', {role: 'alertdialog'})); },
        (_, flow) => { optionalEmail(flow); },
        ({modal}) => { modal.classList.delete('ds-modal-content'); }
    ]) {
        const flow = fixture(source);
        const parts = optionalEmail(flow);
        alter(parts, flow);
        expect(flow, 'dismissEmail', 'email');
        assert.equal(parts.close.clicks, 0);
        assert.equal(flow.open.clicks, 0);
    }

    const interruptedForm = fixture(source);
    expect(interruptedForm, 'open', 'opened');
    expect(interruptedForm, 'prepare', 'prepared');
    const opt = optionalEmail(interruptedForm);
    expect(interruptedForm, 'submit', 'email');
    expect(interruptedForm, 'dismissEmail', 'dismissed');
    expect(interruptedForm, 'inspect', 'submit');
    assert.equal(interruptedForm.confirm.clicks, 0);
    assert.equal(opt.close.clicks, 1);

    const binding = (label = '绑定邮箱', role = 'dialog') => {
        const modal = new Element('div', {role});
        modal.append(new Element('h2', {text: label}), new Input('Email'));
        return modal;
    };
    for (const label of ['绑定邮箱', '绑定电子邮箱', 'Bind email', 'Bind an email address', 'Link your email address']) {
        const flow = fixture(source);
        flow.document.append(binding(label));
        expect(flow, 'open', 'email');
        expect(flow, 'inspect', 'email');
        assert.equal(flow.open.clicks, 0);
    }
    for (const route of ['sign_in', 'usage', 'profile', 'account/bind-email', 'account/settings/', '']) {
        const flow = fixture(source, 'zh', 'https://platform.deepseek.com/' + route);
        expect(flow, 'inspect', 'clear');
        const modal = binding('Bind email', 'alertdialog');
        flow.document.append(modal);
        expect(flow, 'inspect', 'email');
        modal.attributes['aria-hidden'] = 'true';
        expect(flow, 'inspect', 'clear');
        assert.equal(flow.open.clicks, 0);
    }
    const layered = fixture(source);
    expect(layered, 'inspect', 'open');
    expect(layered, 'open', 'opened');
    expect(layered, 'inspect', 'prepare');
    const overlay = binding();
    layered.document.append(overlay);
    expect(layered, 'prepare', 'email');
    assert.equal(layered.input.value, '');
    overlay.style.display = 'none';
    expect(layered, 'prepare', 'prepared');
    overlay.style.display = 'block';
    expect(layered, 'submit', 'email');
    assert.equal(layered.confirm.clicks, 0);
    overlay.style.display = 'none';
    Object.defineProperty(layered.input, 'value', {configurable: true, get() { assert.fail('Inspector read a form value'); }});
    expect(layered, 'inspect', 'submit');
    delete layered.input.value;
    expect(layered, 'submit', 'submitted');
    overlay.style.display = 'block';
    expect(layered, 'read', 'email');
    overlay.style.display = 'none';
    Object.defineProperty(layered.secret, 'innerText', {configurable: true, get() { assert.fail('Inspector read a secret'); }});
    expect(layered, 'inspect', 'read');
    delete layered.secret.innerText;
    assert.deepEqual(layered.run('read'), {status: 'key', key: syntheticKey});
    expect(layered, 'inspect', 'read');
    assert.deepEqual(layered.run('read'), {status: 'key', key: syntheticKey});
    assert.equal(layered.confirm.clicks, 1);
    layered.document.replaceChildren(layered.open);
    expect(layered, 'inspect', 'unrecognized');
    expect(layered, 'open', 'unrecognized');

    const cancelled = fixture(source);
    expect(cancelled, 'open', 'opened');
    expect(cancelled, 'prepare', 'prepared');
    cancelled.document.replaceChildren(cancelled.open);
    expect(cancelled, 'inspect', 'open');
    assert.equal(cancelled.open.clicks, 1);
    expect(cancelled, 'open', 'opened');
    assert.equal(cancelled.confirm.clicks, 0);

    const mounting = fixture(source);
    mounting.open.onClick = null;
    expect(mounting, 'open', 'opened');
    expect(mounting, 'inspect', 'prepare');
    expect(mounting, 'open', 'unrecognized');
    assert.equal(mounting.open.clicks, 1);

    const manualCompletion = fixture(source);
    expect(manualCompletion, 'open', 'opened');
    expect(manualCompletion, 'prepare', 'prepared');
    manualCompletion.confirm.click();
    expect(manualCompletion, 'inspect', 'unrecognized');
    manualCompletion.document.replaceChildren(manualCompletion.open);
    expect(manualCompletion, 'inspect', 'unrecognized');
    expect(manualCompletion, 'open', 'unrecognized');

    const manualPending = fixture(source);
    manualPending.confirm.onClick = null;
    expect(manualPending, 'open', 'opened');
    expect(manualPending, 'prepare', 'prepared');
    manualPending.confirm.click();
    expect(manualPending, 'submit', 'unrecognized');
    manualPending.document.replaceChildren(manualPending.open);
    expect(manualPending, 'inspect', 'unrecognized');
    expect(manualPending, 'open', 'unrecognized');
    assert.equal(manualPending.confirm.clicks, 1);

    const labelled = fixture(source);
    const labelledModal = new Element('div');
    labelledModal.attributes['aria-modal'] = 'true';
    labelledModal.attributes['aria-label'] = '请先绑定邮箱';
    labelled.document.append(labelledModal);
    expect(labelled, 'inspect', 'email');
    labelledModal.attributes['aria-label'] = '其他设置';
    expect(labelled, 'inspect', 'unrecognized');
    const linkOnly = fixture(source);
    linkOnly.document.append(button('绑定邮箱'));
    expect(linkOnly, 'inspect', 'open');
    for (const url of ['https://platform.deepseek.com/top_up', 'https://platform.deepseek.com/wechat-pay',
        'https://platform.deepseek.com/top_up/receipt', 'https://platform.deepseek.com/account/checkout',
        'https://platform.deepseek.com/account/payment-result', 'https://platform.deepseek.com/account//bind-email',
        'https://platform.deepseek.com/account%2Fcheckout', 'https://platform.deepseek.com/#/checkout',
        'https://platform.deepseek.com/banned', 'https://platform.deepseek.com.evil.invalid/api_keys',
        'https://user@platform.deepseek.com/api_keys', 'https://example.com/', 'http://platform.deepseek.com/']) {
        const flow = fixture(source, 'zh', url);
        flow.document.querySelectorAll = () => assert.fail('Inspected outside supported routes');
        expect(flow, 'inspect', 'unrecognized');
        expect(flow, 'dismissEmail', 'unrecognized');
    }
    const embedded = fixture(source, 'zh', officialUrl, true);
    embedded.document.querySelectorAll = () => assert.fail('Inspected an iframe');
    expect(embedded, 'inspect', 'unrecognized');
    expect(embedded, 'dismissEmail', 'unrecognized');

    for (const language of ['zh', 'en']) {
        const flow = fixture(source, language);
        expect(flow, 'read', 'unrecognized');
        expect(flow, 'open', 'opened');
        expect(flow, 'submit', 'unrecognized');
        assert.equal(flow.confirm.clicks, 0);
        expect(flow, 'prepare', 'prepared');
        expect(flow, 'prepare', 'unrecognized');
        assert.equal(flow.input.value, 'deepsleep');
        assert.equal(flow.confirm.clicks, 0);
        expect(flow, 'submit', 'submitted');
        expect(flow, 'submit', 'unrecognized');
        assert.deepEqual(flow.run('read'), {status: 'key', key: syntheticKey});
        assert.deepEqual(flow.run('read'), {status: 'key', key: syntheticKey});
        assert.equal(flow.open.clicks, 1);
        assert.equal(flow.confirm.clicks, 1);
    }

    for (const [url, iframe] of [
        ['https://platform.deepseek.com/sign_in', false],
        ['https://platform.deepseek.com/mobile_verification', false],
        ['https://platform.deepseek.com/account/bind-email', false],
        ['https://platform.deepseek.com/top_up', false],
        ['https://platform.deepseek.com.evil.invalid/api_keys', false],
        ['http://platform.deepseek.com/api_keys', false],
        [officialUrl + '/other', false],
        [officialUrl, true]
    ]) {
        const flow = fixture(source, 'zh', url, iframe);
        flow.document.querySelectorAll = () => assert.fail('Read a document outside the official key page');
        for (const stage of ['open', 'prepare', 'submit', 'read']) expect(flow, stage, 'unrecognized');
        assert.equal(flow.open.clicks, 0);
    }

    for (const suffix of ['/', '?view=all', '#official', '/?view=all#official']) {
        const flow = fixture(source, 'zh', officialUrl + suffix);
        expect(flow, 'open', 'opened');
        assert.equal(flow.open.clicks, 1);
    }

    const duplicate = fixture(source);
    duplicate.document.append(button(duplicate.labels.open));
    expect(duplicate, 'open', 'unrecognized');
    assert.equal(duplicate.open.clicks, 0);

    const wrongEditor = fixture(source);
    wrongEditor.input.attributes.placeholder = 'Other field';
    expect(wrongEditor, 'open', 'opened');
    expect(wrongEditor, 'prepare', 'unrecognized');
    assert.equal(wrongEditor.input.value, '');
    assert.equal(wrongEditor.confirm.clicks, 0);

    const edited = fixture(source);
    expect(edited, 'open', 'opened');
    expect(edited, 'prepare', 'prepared');
    edited.input.value = 'Existing name';
    expect(edited, 'submit', 'unrecognized');
    assert.equal(edited.confirm.clicks, 0);

    const disabled = fixture(source);
    expect(disabled, 'open', 'opened');
    expect(disabled, 'prepare', 'prepared');
    disabled.confirm.disabled = true;
    expect(disabled, 'submit', 'wait');
    expect(disabled, 'submit', 'wait');
    assert.equal(disabled.confirm.clicks, 0);
    disabled.confirm.disabled = false;
    expect(disabled, 'submit', 'submitted');
    expect(disabled, 'submit', 'unrecognized');
    assert.equal(disabled.confirm.clicks, 1);

    const oldDialog = fixture(source);
    oldDialog.dialog.replaceChildren(oldDialog.title, oldDialog.reveal);
    oldDialog.document.append(oldDialog.dialog);
    expect(oldDialog, 'open', 'unrecognized');
    expect(oldDialog, 'read', 'unrecognized');
    assert.equal(oldDialog.open.clicks, 0);

    const waiting = fixture(source);
    waiting.confirm.onClick = null;
    expect(waiting, 'open', 'opened');
    expect(waiting, 'prepare', 'prepared');
    expect(waiting, 'submit', 'submitted');
    waiting.document.append(new Element('span', {classes: 'ds-copyable-text-line__text', text: syntheticKey}));
    expect(waiting, 'read', 'wait');

    const replaced = fixture(source);
    const replacement = new Element('div', {role: 'dialog', classes: 'ds-modal-content'});
    replacement.append(new Element('div', {classes: 'ds-modal-content__title', text: replaced.labels.open}), replaced.reveal);
    replaced.confirm.onClick = () => replaced.document.replaceChildren(replacement);
    expect(replaced, 'open', 'opened');
    expect(replaced, 'prepare', 'prepared');
    expect(replaced, 'submit', 'submitted');
    expect(replaced, 'read', 'unrecognized');

    for (const alter of [
        flow => { flow.secret.text += '-suffix'; },
        flow => { flow.secret.style.visibility = 'hidden'; },
        flow => { flow.reveal.append(new Element('span', {classes: 'ds-copyable-text-line__text', text: syntheticKey})); }
    ]) {
        const flow = fixture(source);
        alter(flow);
        expect(flow, 'open', 'opened');
        expect(flow, 'prepare', 'prepared');
        expect(flow, 'submit', 'submitted');
        expect(flow, 'read', 'unrecognized');
    }

    return 'Official key flow DOM fixtures passed';
}

if (require.main === module) console.log(run());
module.exports = run;
