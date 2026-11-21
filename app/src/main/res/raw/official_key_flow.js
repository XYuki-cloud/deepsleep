(function(stage) {
    'use strict';

    const wait = {status: 'wait'};
    const unrecognized = {status: 'unrecognized'};
    const location = window.location;
    const inspecting = stage === 'inspect';
    const dismissingEmail = stage === 'dismissEmail';
    const keyPage = location.pathname === '/api_keys' || location.pathname === '/api_keys/';
    if (window !== window.top || location.origin !== 'https://platform.deepseek.com'
        || location.username || location.password) return unrecognized;
    // Account routes may host the same email prompt. Key operations remain restricted to /api_keys.
    if (!keyPage) {
        const plainPath = /^\/(?:[a-z\d_-]+(?:\/[a-z\d_-]+)*\/?)?$/i;
        const excluded = /^(?:banned|top[-_]?up|recharge|billing|cashier|checkout|wallet|wechat[-_]?pay|ali[-_]?pay|payment|pay|order)s?(?:$|[-_])/i;
        if (!(inspecting || dismissingEmail) || !plainPath.test(location.pathname)
            || location.pathname.split('/').some(segment => excluded.test(segment))
            || /^#!?\//.test(location.hash)) return unrecognized;
    }

    const stateSlot = Symbol.for('com.simpledeep.chat.officialKeyFlow');
    const state = document[stateSlot];
    const labels = [
        {title: '创建 API key', name: '输入 API key 的名称', cancel: '取消', create: '创建', copy: ['复制', '已复制']},
        {title: 'Create new API key', name: 'Name of the API key', cancel: 'Cancel', create: 'Create API key', copy: ['Copy', 'Copied']}
    ];
    const keyName = 'deepsleep';
    const primaryButton = '[role="button"].ds-button--filled';
    const buttonLabel = '.ds-button__content';
    const keyText = '.ds-copyable-text-line__text';

    function isVisible(element) {
        if (!Array.from(element.getClientRects()).some(rect => rect.width > 0 && rect.height > 0)) return false;
        for (let ancestor = element; ancestor; ancestor = ancestor.parentElement) {
            if (ancestor.getAttribute('aria-hidden') === 'true') return false;
            const style = window.getComputedStyle(ancestor);
            if (style.display === 'none' || style.visibility !== 'visible' || Number(style.opacity) === 0) return false;
        }
        return true;
    }

    function visible(scope, selector) {
        return Array.from(scope.querySelectorAll(selector)).filter(isVisible);
    }

    function textOf(element) {
        return element.innerText.trim().replace(/\s+/g, ' ');
    }

    function labelOf(button) {
        const contents = visible(button, buttonLabel);
        return contents.length === 1 ? textOf(contents[0]) : '';
    }

    function canClick(button) {
        return !button.disabled && button.getAttribute('aria-disabled') !== 'true'
            && !button.classList.contains('ds-button--loading')
            && !button.classList.contains('ds-button--disabled');
    }

    const dialogs = Array.from(document.querySelectorAll('[role="dialog"], [role="alertdialog"], [aria-modal="true"], dialog[open], .ds-modal-content'));
    const visibleDialogs = dialogs.filter(isVisible);
    // The platform wraps its role=dialog content in a separate aria-modal shell, with transition nodes between them.
    const activeDialogs = visibleDialogs.filter(shell => {
        if (!shell.classList.contains('ds-modal') || shell.getAttribute('aria-modal') !== 'true') return true;
        const nested = visibleDialogs.filter(node => node !== shell && shell.contains(node));
        if (nested.length !== 1 || nested[0].getAttribute('role') !== 'dialog'
            || !nested[0].classList.contains('ds-modal-content')) return true;
        return visible(shell, '.ds-modal-content__title, .ds-modal-content__close')
            .some(node => !nested[0].contains(node));
    });
    const emailPrompt = /^(?:(?:请先|请)?(?:绑定|綁定|关联|驗證|验证)(?:您的?|你的?)?(?:电子|電子)?(?:邮箱(?:地址)?|郵箱(?:地址)?|郵件地址)|(?:电子邮箱|邮箱|郵箱)(?:验证|驗證)|(?:please\s+)?(?:bind|link|verify)(?:\s+(?:your|an?))?\s+e-?mail(?:\s+address)?|e-?mail\s+(?:verification|binding))\s*[：:。.]?$/i;
    const headingSelector = '.ds-modal-content__title, [role="heading"], h1, h2, h3';
    const hasEmailPrompt = visibleDialogs.some(dialog => emailPrompt.test((dialog.getAttribute('aria-label') || '').trim()))
        || visible(document, headingSelector).some(heading => emailPrompt.test(textOf(heading)));
    if (dismissingEmail) {
        if (!hasEmailPrompt) return {status: 'gone'};
        // Only dismiss the platform's optional binding dialog. A verification page stays open.
        const bindingTitles = ['bind email', '绑定邮箱', '绑定电子邮箱', '綁定郵箱', '綁定電子郵箱'];
        const candidates = activeDialogs.filter(dialog => {
            if (dialog.getAttribute('role') !== 'dialog' || !dialog.classList.contains('ds-modal-content')) return false;
            const titles = visible(dialog, '.ds-modal-content__title');
            return titles.length === 1 && bindingTitles.includes(textOf(titles[0]).toLowerCase());
        });
        if (candidates.length !== 1) return {status: 'email'};
        const [dialog] = candidates;
        if (activeDialogs.length > 2) return {status: 'email'};
        for (const other of activeDialogs) {
            if (other === dialog) continue;
            if (other.contains(dialog) || dialog.contains(other)) return {status: 'email'};
            if (state && other === state.dialog) continue;
            // The key dialog may already be open when the email overlay interrupts its first setup step.
            const titles = visible(other, '.ds-modal-content__title');
            if (!state || state.phase !== 'opened' || state.existingDialogs.has(other) || titles.length !== 1
                || !other.classList.contains('ds-modal-content') || textOf(titles[0]) !== state.language.title) return {status: 'email'};
        }
        const controls = visible(dialog, '.ds-modal-content__close');
        if (controls.length !== 1 || !canClick(controls[0])) return {status: 'email'};
        const [close] = controls;
        if (close.tagName !== 'DIV' && !(close.tagName === 'BUTTON' && close.type === 'button')) return {status: 'email'};
        const box = close.getBoundingClientRect();
        const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
        if (!close.contains(hit)) return {status: 'email'};
        close.click();
        return {status: 'dismissed'};
    }
    if (hasEmailPrompt) return {status: 'email'};
    if (!keyPage) return activeDialogs.length ? unrecognized : {status: 'clear'};

    // Reopen only a form we actually prepared and still recognize as an unsubmitted editor.
    // A pending dialog mount, a submitted form, or a dismissed success view is not grounds to create again.
    const closedEditor = state && state.phase === 'prepared' && !activeDialogs.length
        && Array.from(state.dialog.querySelectorAll('input')).includes(state.input)
        && !state.dialog.querySelectorAll(keyText).length;
    if (inspecting) {
        // Inference is confined to this document and the dialog opened by this helper.
        stage = state && !closedEditor ? {opened: 'prepare', prepared: 'submit', submitted: 'read'}[state.phase] : 'open';
    }

    if (stage === 'open') {
        if ((state && !closedEditor) || activeDialogs.length !== 0) return unrecognized;
        // The empty table also has a text button with this label. Use the primary filled button.
        const buttons = visible(document, primaryButton).filter(button => labels.some(label => label.title === labelOf(button)));
        if (buttons.length > 1) return unrecognized;
        if (inspecting) return {status: 'open'};
        if (!buttons.length || !canClick(buttons[0])) return wait;
        const language = labels.find(label => label.title === labelOf(buttons[0]));
        const existingDialogs = new Set(dialogs);
        if (closedEditor) existingDialogs.delete(state.dialog);
        document[stateSlot] = {phase: 'opened', existingDialogs, language};
        buttons[0].click();
        return {status: 'opened'};
    }

    if (!state) return unrecognized;
    if (stage === 'prepare' && state.phase !== 'opened') return unrecognized;
    if (stage === 'submit' && state.phase !== 'prepared') return unrecognized;
    if (stage === 'read' && state.phase !== 'submitted') return unrecognized;
    if (stage !== 'prepare' && stage !== 'submit' && stage !== 'read') return unrecognized;
    if (!activeDialogs.length) {
        if (state.dialog) return unrecognized;
        return inspecting ? {status: stage} : wait;
    }
    if (activeDialogs.length !== 1) return unrecognized;
    const [dialog] = activeDialogs;
    if (!dialog.classList.contains('ds-modal-content') || state.existingDialogs.has(dialog)
        || (state.dialog && state.dialog !== dialog)) return unrecognized;
    const title = visible(dialog, '.ds-modal-content__title');
    const language = state.language;
    if (title.length !== 1 || textOf(title[0]) !== language.title) return unrecognized;

    const inputs = visible(dialog, 'input').filter(input => input.type === 'text');
    if (stage === 'prepare' || stage === 'submit') {
        const confirm = visible(dialog, primaryButton).filter(button => labelOf(button) === language.create);
        const cancel = visible(dialog, '[role="button"].ds-button--outlined').filter(button => labelOf(button) === language.cancel);
        if (confirm.length !== 1 || cancel.length !== 1 || inputs.length !== 1
            || dialog.querySelectorAll(keyText).length !== 0) return unrecognized;
        const [input] = inputs;
        if (input.getAttribute('placeholder') !== language.name || input.disabled || input.readOnly) return unrecognized;
        if (inspecting) return stage === 'submit' && state.input !== input ? unrecognized : {status: stage};

        if (stage === 'prepare') {
            if (input.value !== '') return unrecognized;
            state.dialog = dialog;
            state.input = input;
            // A user may submit this form by hand while paused, before the website replaces or closes it.
            // Keep that case out of both automatic submission and reopening a cancelled form.
            const manualSubmit = () => {
                if (state.phase !== 'submitted') state.phase = 'manual';
            };
            confirm[0].addEventListener('click', manualSubmit, {capture: true, once: true});
            dialog.addEventListener('submit', manualSubmit, {capture: true, once: true});
            const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
            setter.call(input, keyName);
            input.dispatchEvent(new Event('input', {bubbles: true}));
            state.phase = 'prepared';
            return {status: 'prepared'};
        }

        if (state.input !== input || input.value !== keyName) return unrecognized;
        if (!canClick(confirm[0])) return wait;
        state.phase = 'submitted';
        confirm[0].click();
        return {status: 'submitted'};
    }

    // The known success view replaces the form inside the same dialog.
    if (inputs.includes(state.input)) return inspecting ? {status: 'read'} : wait;
    if (inputs.length) return unrecognized;
    const lines = visible(dialog, '.ds-copyable-text-line');
    if (lines.length !== 1) return unrecognized;
    const copy = visible(lines[0], primaryButton).filter(button => language.copy.includes(labelOf(button)));
    const keys = visible(lines[0], keyText);
    if (copy.length !== 1 || keys.length !== 1) return unrecognized;
    if (inspecting) return {status: 'read'};
    const key = textOf(keys[0]);
    if (!/^sk-[0-9a-f]{32}$/i.test(key) || !key.startsWith('sk-')) return unrecognized;
    // Reading is idempotent if Android discarded a callback on pause. A submit can never run in this phase.
    return {status: 'key', key};
})
