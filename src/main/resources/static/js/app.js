(function () {
    'use strict';

    // ── Chrome strings ────────────────────────────────────────
    // One interface language at a time (design turn 3): every chrome string
    // flips together, and nothing is glossed inline.
    var STRINGS = {
        ta: {
            brand: 'குரலகம்',
            online: 'ஆன்லைனில் உள்ளது',
            offline: 'மீண்டும் இணைக்கிறது',
            langName: 'தமிழ்',
            menuLabel: 'இடைமுக மொழி',
            soon: 'விரைவில்',
            placeholder: 'தமிழில் தட்டச்சு செய்யுங்கள்…',
            connected: 'இணைக்கப்பட்டது',
            disconnected: 'இணைப்பு துண்டிக்கப்பட்டது — மீண்டும் முயல்கிறது',
            today: 'இன்று',
            greeting: 'வணக்கம்! நான் குரலகம். தமிழ்நாடு அரசின் சுகாதாரம் மற்றும் கல்வித் திட்டங்கள் பற்றி உங்களுக்கு உதவுவேன் — முதலமைச்சரின் விரிவான மருத்துவக் காப்பீட்டுத் திட்டம் (CMCHIS), இலவச மருந்து வழங்கல், கல்வி உதவித்தொகை, சத்துணவுத் திட்டம். என்ன தெரிந்துகொள்ள விரும்புகிறீர்கள்?',
            starters: ['CMCHIS-ல் யார் சேரலாம்?', 'உதவித்தொகை விண்ணப்பம்', 'அருகில் இலவச மருந்தகம்'],
            declineFollowups: ['CMCHIS தகுதி', 'கல்வி உதவித்தொகை'],
            disclaimer: 'குரலகம் சுகாதாரம் மற்றும் கல்வித் திட்டங்கள் பற்றி மட்டுமே பதிலளிக்கும்.',
            translate: 'மொழிபெயர்ப்பு',
            hide: 'மறை',
            sourceLabel: 'ஆதாரம்',
            thinking: 'யோசிக்கிறேன்…',
            streaming: 'பதில் வந்துகொண்டிருக்கிறது',
            stop: 'நிறுத்து',
            stopped: 'நிறுத்தப்பட்டது',
            outOfScope: 'எனது எல்லைக்கு வெளியே',
            errTitle: 'இணைப்பில் சிக்கல் — பதில் வரவில்லை.',
            errBody: 'உங்கள் முந்தைய செய்திகள் பாதுகாப்பாக உள்ளன.',
            retry: 'மீண்டும் முயற்சி',
            listening: 'கேட்டுக்கொண்டிருக்கிறேன்…',
            micError: 'ஒலிவாங்கியை அணுக முடியவில்லை.',
            sttError: 'பேச்சை உரையாக மாற்ற முடியவில்லை.',
            speak: 'பேசுங்கள்',
            // The banner addresses someone typing the *other* language, so it
            // deliberately speaks that language (design 3b).
            detectTitle: 'நீங்கள் ஆங்கிலத்தில் எழுதினீர்கள்.',
            detectBody: 'Keep the app in Tamil, or switch the interface to English?',
            detectYes: 'English',
            detectNo: 'தமிழே',
            months: ['ஜனவரி','பிப்ரவரி','மார்ச்','ஏப்ரல்','மே','ஜூன்','ஜூலை','ஆகஸ்ட்','செப்டம்பர்','அக்டோபர்','நவம்பர்','டிசம்பர்']
        },
        en: {
            brand: 'Kuralagam',
            online: 'Online',
            offline: 'Reconnecting',
            langName: 'English',
            menuLabel: 'Interface language',
            soon: 'Coming soon',
            placeholder: 'Type your question…',
            connected: 'Connected',
            disconnected: 'Disconnected — retrying',
            today: 'Today',
            greeting: 'Hello! I am Kuralagam. I help you with the Tamil Nadu government’s Health and Education schemes — the Chief Minister’s Comprehensive Health Insurance Scheme (CMCHIS), free medicines, education scholarships and the mid-day meal scheme. What would you like to know?',
            starters: ['Who can join CMCHIS?', 'Scholarship application', 'Free medicines nearby'],
            declineFollowups: ['CMCHIS eligibility', 'Education scholarships'],
            disclaimer: 'Kuralagam answers questions about Health and Education schemes only.',
            translate: 'Translation',
            hide: 'Hide',
            sourceLabel: 'Source',
            thinking: 'Thinking…',
            streaming: 'Replying',
            stop: 'Stop',
            stopped: 'Stopped',
            outOfScope: 'Outside my scope',
            errTitle: 'Connection problem — no reply came through.',
            errBody: 'Your earlier messages are safe.',
            retry: 'Try again',
            listening: 'Listening…',
            micError: 'Could not reach the microphone.',
            sttError: 'Could not turn that speech into text.',
            speak: 'Speak',
            detectTitle: 'நீங்கள் தமிழில் எழுதினீர்கள்.',
            detectBody: 'Keep the app in English, or switch the interface to Tamil?',
            detectYes: 'தமிழ்',
            detectNo: 'English',
            months: ['January','February','March','April','May','June','July','August','September','October','November','December']
        }
    };

    var LANG_META = {
        ta: { code: 'ta-IN', name: 'Tamil',   swap: 'அ→A' },
        en: { code: 'en-IN', name: 'English', swap: 'A→அ' }
    };

    // The scope marker the model prefixes to an out-of-domain decline.
    // It is a routing signal, never reader-facing text.
    var MARK = '[OFF_TOPIC]';

    // ── State ─────────────────────────────────────────────────
    var uiLang = localStorage.getItem('kuralagam.uiLang') || 'ta';
    if (!STRINGS[uiLang]) uiLang = 'ta';

    var langOffered = false;   // the switch banner is offered once per session
    var streamAbort = null;
    var pendingRetry = null;   // text held for the error card's retry
    var recorder = null;
    var recording = false;
    var speakReplies = false;  // a spoken question earns a spoken answer

    var conversationId = sessionStorage.getItem('kuralagam_conversationId');
    if (!conversationId) {
        conversationId = (window.crypto && crypto.randomUUID)
            ? crypto.randomUUID()
            : String(Date.now()) + Math.random().toString(16).slice(2);
        sessionStorage.setItem('kuralagam_conversationId', conversationId);
    }

    function $(id) { return document.getElementById(id); }
    var app = $('app'), thread = $('thread'), input = $('input'), sendBtn = $('sendBtn');

    function t() { return STRINGS[uiLang]; }
    function other(lang) { return lang === 'ta' ? 'en' : 'ta'; }

    // Tamil Unicode block. The composer stays script-agnostic, so what the user
    // actually typed — not the interface setting — decides the reply language.
    function detectLang(text) { return /[஀-௿]/.test(text) ? 'ta' : 'en'; }

    function el(tag, cls, text) {
        var n = document.createElement(tag);
        if (cls) n.className = cls;
        if (text != null) n.textContent = text;
        return n;
    }

    // Models occasionally emit HTML entities (e.g. "&nbsp;") in plain-text
    // replies. Decode them via a detached <textarea> — browsers never execute
    // markup placed there, so this is safe even though the input is untrusted —
    // and the result is still only ever inserted with textContent, never innerHTML.
    var entityDecoder = document.createElement('textarea');
    function decodeEntities(text) {
        entityDecoder.innerHTML = text;
        return entityDecoder.value;
    }

    function scrollDown() { thread.scrollTop = thread.scrollHeight; }

    // Pulls a balanced "(...)" group off the end of text, if the text ends
    // with one. Scans backward counting depth so a gloss that itself contains
    // parentheses doesn't get cut short.
    function extractTrailingGloss(text) {
        var end = text.length;
        while (end > 0 && /\s/.test(text.charAt(end - 1))) end--;
        if (end === 0 || text.charAt(end - 1) !== ')') return null;

        var depth = 0, i = end - 1;
        for (; i >= 0; i--) {
            if (text.charAt(i) === ')') depth++;
            else if (text.charAt(i) === '(') {
                depth--;
                if (depth === 0) break;
            }
        }
        if (i < 0) return null;

        return { gloss: text.slice(i + 1, end - 1).trim(), rest: text.slice(0, i).trim() };
    }

    // What to show while a reply is still streaming in, so a same-line gloss
    // never flashes into the main bubble before finish() can move it to the
    // pill. Holds back a trailing "(...)" group — whether already closed or
    // still being typed — since until more text arrives after it there is no
    // way to tell a mid-sentence parenthetical (e.g. "CMCHIS (Chief Minister's
    // ... Scheme)") apart from the final gloss; a legitimate one reappears the
    // moment the next token lands.
    function visibleStreamText(text) {
        var complete = extractTrailingGloss(text);
        if (complete) return complete.rest;

        var depth = 0, openStart = -1;
        for (var i = 0; i < text.length; i++) {
            var ch = text.charAt(i);
            if (ch === '(') {
                if (depth === 0) openStart = i;
                depth++;
            } else if (ch === ')') {
                depth = Math.max(0, depth - 1);
            }
        }
        if (depth > 0 && openStart !== -1) return text.slice(0, openStart).trim();

        return text;
    }

    // ── Interface language ────────────────────────────────────
    function applyLang() {
        var s = t();
        var offline = app.classList.contains('offline');
        app.className = 'app lang-' + uiLang + (offline ? ' offline' : '');
        document.documentElement.lang = uiLang;
        $('brand').textContent = s.brand;
        $('presenceText').textContent = offline ? s.offline : s.online;
        $('footText').textContent = offline ? s.disconnected : s.connected;
        $('langBtnText').textContent = s.langName;
        $('langMenuLabel').textContent = s.menuLabel;
        $('langSoonLabel').textContent = s.soon;
        $('scriptHint').textContent = uiLang === 'ta' ? 'அ / A' : 'A / அ';
        $('micBtn').title = s.speak;
        input.placeholder = s.placeholder;
        Array.prototype.forEach.call(document.querySelectorAll('.lang-opt'), function (b) {
            b.setAttribute('aria-checked', String(b.getAttribute('data-lang') === uiLang));
        });
        try { localStorage.setItem('kuralagam.uiLang', uiLang); } catch (e) { /* private mode */ }
    }

    function setLang(next) {
        if (next === uiLang || !STRINGS[next]) return;
        uiLang = next;
        applyLang();
        resetThread();
    }

    function closeMenu() {
        $('langMenu').hidden = true;
        $('langBtn').setAttribute('aria-expanded', 'false');
    }

    $('langBtn').addEventListener('click', function (e) {
        e.stopPropagation();
        var opening = $('langMenu').hidden;
        $('langMenu').hidden = !opening;
        $('langBtn').setAttribute('aria-expanded', String(opening));
    });
    $('langMenu').addEventListener('click', function (e) {
        e.stopPropagation();
        var opt = e.target.closest ? e.target.closest('.lang-opt') : null;
        if (!opt) return;
        closeMenu();
        setLang(opt.getAttribute('data-lang'));
    });
    document.addEventListener('click', closeMenu);
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeMenu(); });

    // ── Connection state ──────────────────────────────────────
    function setOnline(ok) {
        if (app.classList.contains('offline') === !ok) return;
        app.classList.toggle('offline', !ok);
        var s = t();
        $('presenceText').textContent = ok ? s.online : s.offline;
        $('footText').textContent = ok ? s.connected : s.disconnected;
    }
    window.addEventListener('online',  function () { setOnline(true); });
    window.addEventListener('offline', function () { setOnline(false); });

    // ── Opening state ─────────────────────────────────────────
    function resetThread() {
        thread.textContent = '';
        var s = t();
        var now = new Date();
        thread.appendChild(el('div', 'daymark',
            s.today + ' · ' + now.getDate() + ' ' + s.months[now.getMonth()]));

        var intro = makeBotBubble(uiLang);
        intro.setText(s.greeting);
        thread.appendChild(intro.node);
        // The intro's own gloss is the same greeting in the other language.
        intro.setActions(STRINGS[other(uiLang)].greeting, []);

        var chips = el('div', 'chips');
        chips.setAttribute('data-opening', '');
        s.starters.forEach(function (q) { chips.appendChild(makeChip(q)); });
        thread.appendChild(chips);

        var note = el('div', 'disclaimer', s.disclaimer);
        note.setAttribute('data-opening', '');
        thread.appendChild(note);
        scrollDown();
    }

    function makeChip(text) {
        var c = el('button', 'chip', text);
        c.type = 'button';
        c.addEventListener('click', function () { send(text); });
        return c;
    }

    function clearOpeningFurniture() {
        Array.prototype.forEach.call(thread.querySelectorAll('[data-opening]'), function (n) {
            n.remove();
        });
    }

    // ── Message rendering ─────────────────────────────────────
    // Model text is only ever inserted with textContent — never innerHTML.
    function renderBody(container, text, lang) {
        container.className = 'body' + (lang === 'ta' ? ' ta' : '');
        container.textContent = '';
        var list = null;
        text.split('\n').forEach(function (raw) {
            var line = raw.trim();
            if (!line) { list = null; return; }
            if (/^[-*•]\s+/.test(line)) {
                if (!list) { list = el('ul'); container.appendChild(list); }
                list.appendChild(el('li', null, line.replace(/^[-*•]\s+/, '')));
                return;
            }
            list = null;
            // A short line ending in a colon reads as a section heading (design 1b).
            if (line.length < 44 && /[:：]$/.test(line)) {
                container.appendChild(el('div', 'subhead', line.replace(/[:：]$/, '')));
            } else {
                container.appendChild(el('p', null, line));
            }
        });
    }

    function makeBotBubble(replyLang) {
        var node = el('div', 'bubble bot');
        var body = el('div', 'body');
        var xlate = el('div', 'xlate');
        xlate.hidden = true;
        node.appendChild(body);
        node.appendChild(xlate);

        return {
            node: node,
            body: body,

            setText: function (text) { renderBody(body, text, replyLang); },

            markDeclined: function () {
                node.classList.add('declined');
                node.insertBefore(el('div', 'decline-label', t().outOfScope), node.firstChild);
                var chips = el('div', 'chips');
                t().declineFollowups.forEach(function (q) { chips.appendChild(makeChip(q)); });
                node.appendChild(chips);
            },

            // The gloss sits behind its own pill, off by default; the sources
            // sit beside it as ஆதாரம் links (designs 1a–1c, 3c).
            setActions: function (translation, sources) {
                var s = t();
                var actions = el('div', 'actions');

                if (translation) {
                    var glossLang = other(replyLang);
                    var head = el('div', 'xlate-head');
                    head.appendChild(el('span', 'swap', LANG_META[replyLang].swap));
                    head.appendChild(el('span', null, STRINGS[glossLang].langName));
                    var hide = el('button', 'hide', s.hide);
                    hide.type = 'button';
                    head.appendChild(hide);
                    xlate.textContent = '';
                    xlate.appendChild(head);
                    xlate.appendChild(el('div', 'xlate-body' + (glossLang === 'ta' ? ' ta' : ''), translation));

                    var pill = el('button', 'pill');
                    pill.type = 'button';
                    pill.appendChild(el('span', 'swap', LANG_META[replyLang].swap));
                    pill.appendChild(el('span', null, s.translate));

                    var show = function (on) {
                        xlate.hidden = !on;
                        pill.hidden = on;
                    };
                    pill.addEventListener('click', function () { show(true); });
                    hide.addEventListener('click', function () { show(false); });
                    show(false);

                    actions.appendChild(pill);
                }

                if (sources && sources.length) {
                    actions.appendChild(el('div', 'src-label', s.sourceLabel));
                    sources.slice(0, 2).forEach(function (src) {
                        var a = el('a', 'src');
                        a.href = src.url;
                        a.target = '_blank';
                        a.rel = 'noopener noreferrer';
                        a.title = src.label || src.host;
                        a.appendChild(el('span', null, src.host));
                        a.appendChild(el('span', 'out', '↗'));
                        actions.appendChild(a);
                    });
                }

                if (actions.children.length) node.appendChild(actions);
            }
        };
    }

    function addUser(text) {
        var node = el('div', 'bubble user');
        node.appendChild(el('div', 'body' + (detectLang(text) === 'ta' ? ' ta' : ''), text));
        thread.appendChild(node);
        scrollDown();
        return node;
    }

    // ── Detected-language banner ──────────────────────────────
    function maybeOfferSwitch(replyLang) {
        if (langOffered || replyLang === uiLang) return;
        langOffered = true;
        var s = t();

        var card = el('div', 'detect');
        card.appendChild(el('span', 'mark'));

        var copy = el('div', 'copy');
        copy.appendChild(el('b', null, s.detectTitle));
        copy.appendChild(document.createTextNode(s.detectBody));
        card.appendChild(copy);

        var acts = el('div', 'acts');
        var yes = el('button', 'yes', s.detectYes);
        var no  = el('button', 'no',  s.detectNo);
        yes.type = no.type = 'button';
        yes.addEventListener('click', function () { card.remove(); setLang(replyLang); });
        no.addEventListener('click',  function () { card.remove(); });
        acts.appendChild(yes);
        acts.appendChild(no);
        card.appendChild(acts);

        thread.appendChild(card);
        scrollDown();
    }

    // ── Inline network error ──────────────────────────────────
    function showError(userNode, text) {
        var s = t();
        pendingRetry = text;
        if (userNode) userNode.classList.add('stale');

        var card = el('div', 'errcard');
        card.appendChild(el('div', 'bang', '!'));
        var copy = el('div', 'copy');
        copy.appendChild(el('b', null, s.errTitle));
        copy.appendChild(el('span', null, s.errBody));
        card.appendChild(copy);

        var retry = el('button', null, s.retry);
        retry.type = 'button';
        retry.addEventListener('click', function () {
            card.remove();
            if (userNode) userNode.classList.remove('stale');
            var again = pendingRetry;
            pendingRetry = null;
            if (again) send(again, userNode);
        });
        card.appendChild(retry);

        thread.appendChild(card);
        scrollDown();
    }

    // ── Send ──────────────────────────────────────────────────
    function refreshSend() { sendBtn.disabled = input.value.trim() === ''; }
    input.addEventListener('input', refreshSend);
    input.addEventListener('keydown', function (e) { if (e.key === 'Enter') send(); });
    sendBtn.addEventListener('click', function () { send(); });

    function send(preset, reuseNode) {
        var text = (preset != null ? preset : input.value).trim();
        if (!text || streamAbort) return;

        input.value = '';
        refreshSend();
        clearOpeningFurniture();

        var userNode = reuseNode || addUser(text);
        var replyLang = detectLang(text);
        maybeOfferSwitch(replyLang);

        var s = t();
        var thinking = el('div', 'thinking');
        thinking.appendChild(el('i'));
        thinking.appendChild(el('i'));
        thinking.appendChild(el('i'));
        thinking.appendChild(el('span', null, s.thinking));
        thread.appendChild(thinking);
        scrollDown();

        var controller = new AbortController();
        streamAbort = controller;

        var bot = null, raw = '', sources = [], stopped = false, streamRow = null, settled = false;

        function ensureBubble() {
            if (bot) return;
            thinking.remove();
            bot = makeBotBubble(replyLang);
            thread.appendChild(bot.node);

            streamRow = el('div', 'streamrow');
            streamRow.appendChild(el('span', 'live'));
            streamRow.appendChild(el('span', null, s.streaming));
            var stop = el('button', 'stop', s.stop);
            stop.type = 'button';
            stop.addEventListener('click', function () {
                stopped = true;
                controller.abort();
            });
            streamRow.appendChild(stop);
            bot.node.appendChild(streamRow);
            scrollDown();
        }

        // Strip the scope marker from anything the reader sees.
        function visible(value) {
            var v = value.replace(/^\s+/, '');
            var stripped = v.indexOf(MARK) === 0 ? v.slice(MARK.length).replace(/^\s+/, '') : value;
            return decodeEntities(stripped);
        }

        fetch('/api/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            signal: controller.signal,
            body: JSON.stringify({
                message: text,
                language: LANG_META[replyLang].code,
                languageName: LANG_META[replyLang].name,
                conversationId: conversationId
            })
        }).then(function (res) {
            if (!res.ok || !res.body) throw new Error('chat/stream returned ' + res.status);
            setOnline(true);

            var reader = res.body.getReader();
            var decoder = new TextDecoder();
            var buffer = '';

            function pump() {
                return reader.read().then(function (chunk) {
                    if (chunk.done) return;
                    buffer += decoder.decode(chunk.value, { stream: true });

                    var end;
                    while ((end = buffer.indexOf('\n\n')) !== -1) {
                        var frame = buffer.slice(0, end);
                        buffer = buffer.slice(end + 2);

                        // Per the SSE spec a payload containing newlines arrives as
                        // several data: lines, rejoined with a newline — that is what
                        // keeps the reply and its parenthesised gloss on separate lines.
                        var name = 'token', parts = [];
                        frame.split('\n').forEach(function (line) {
                            if (line.indexOf('event:') === 0) name = line.slice(6).trim();
                            // Spring's SseEmitter writes "data:" with no separator space, so a
                            // leading space here is real token content (word-initial BPE tokens
                            // start with one) — stripping it would glue every word together.
                            else if (line.indexOf('data:') === 0) parts.push(line.slice(5));
                        });
                        var data = parts.join('\n');

                        if (name === 'meta') {
                            try {
                                sources = (JSON.parse(data) || {}).sources || [];
                            } catch (e) { /* metadata is optional */ }
                            continue;
                        }

                        ensureBubble();
                        raw += data;
                        // Only line 1 streams into view — the gloss stays behind its pill,
                        // whether it lands on its own line or trails the same one.
                        bot.setText(visibleStreamText(visible(raw).split('\n')[0]));
                        bot.body.appendChild(el('span', 'caret'));
                        scrollDown();
                    }
                    return pump();
                });
            }
            return pump();
        }).then(finish).catch(function (err) {
            if (stopped) { finish(); return; }
            settle();
            thinking.remove();
            if (bot && !raw) bot.node.remove();
            setOnline(false);
            console.error('chat stream failed', err);
            showError(userNode, text);
        });

        function settle() {
            if (streamAbort === controller) streamAbort = null;
        }

        function finish() {
            if (settled) return;
            settled = true;
            settle();
            thinking.remove();

            if (!bot || !raw.trim()) {
                if (bot) bot.node.remove();
                showError(userNode, text);
                return;
            }
            if (streamRow) streamRow.remove();

            var offTopic = raw.replace(/^\s+/, '').indexOf(MARK) === 0;
            var clean = visible(raw).trim();

            // The gloss (GroqService.splitReply's contract) is meant to trail the
            // reply, either on its own line or — as models often do despite the
            // prompt — tacked onto the end of the same line. Either way it is the
            // last thing in the response, so pull off a balanced parenthesised
            // group anchored at the very end rather than requiring a dedicated line.
            var translation = null;
            var gloss = extractTrailingGloss(clean);
            if (gloss) {
                translation = gloss.gloss;
                clean = gloss.rest;
            }
            var reply = clean.split('\n')[0].trim();

            if (offTopic) bot.markDeclined();
            bot.setText(clean || reply);

            if (stopped) {
                var row = el('div', 'streamrow done');
                row.appendChild(el('span', 'live'));
                row.appendChild(el('span', null, s.stopped));
                bot.node.appendChild(row);
            }

            bot.setActions(translation, sources);
            scrollDown();
            if (speakReplies) speak(reply, replyLang);
        }
    }

    // ── Read the reply aloud (Web Speech API) ─────────────────
    function speak(text, lang) {
        if (!text || !window.speechSynthesis) return;
        window.speechSynthesis.cancel();
        var utt = new SpeechSynthesisUtterance(text);
        utt.lang = LANG_META[lang].code;
        utt.rate = 0.95;
        var voices = window.speechSynthesis.getVoices();
        var match = voices.filter(function (v) { return v.lang.indexOf(lang) === 0; })[0]
                 || voices.filter(function (v) { return v.lang.indexOf('IN') !== -1; })[0];
        if (match) utt.voice = match;
        window.speechSynthesis.speak(utt);
    }

    if (window.speechSynthesis) {
        window.speechSynthesis.getVoices();
        window.speechSynthesis.onvoiceschanged = function () { window.speechSynthesis.getVoices(); };
    }

    // ── Voice input (Groq Whisper) ────────────────────────────
    // Phase 2 designs the voice-first shell; Phase 1 keeps the shipped
    // transcribe path behind a quiet mic in the composer.
    $('micBtn').addEventListener('click', function () {
        if (recording) {
            if (recorder && recorder.state !== 'inactive') {
                recorder.stop();
                recorder.stream.getTracks().forEach(function (tr) { tr.stop(); });
            }
            return;
        }
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
            flashPlaceholder(t().micError);
            return;
        }

        navigator.mediaDevices.getUserMedia({ audio: true }).then(function (stream) {
            recorder = new MediaRecorder(stream);
            var chunks = [];
            recorder.ondataavailable = function (e) { if (e.data.size > 0) chunks.push(e.data); };
            recorder.onstart = function () {
                recording = true;
                $('micBtn').classList.add('recording');
                input.placeholder = t().listening;
            };
            recorder.onstop = function () {
                recording = false;
                $('micBtn').classList.remove('recording');
                input.placeholder = t().placeholder;
                transcribe(new Blob(chunks, { type: 'audio/webm' }));
            };
            recorder.start();
        }).catch(function (err) {
            console.error('microphone unavailable', err);
            flashPlaceholder(t().micError);
        });
    });

    function flashPlaceholder(message) {
        input.placeholder = message;
        setTimeout(function () { input.placeholder = t().placeholder; }, 4000);
    }

    function transcribe(blob) {
        var form = new FormData();
        form.append('audio', blob);
        form.append('language', LANG_META[uiLang].code);

        fetch('/api/transcribe', { method: 'POST', body: form })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (!data.success || !data.text) throw new Error(data.error || 'empty transcript');
                speakReplies = true;   // a spoken question earns a spoken answer
                send(data.text);
            })
            .catch(function (err) {
                console.error('transcription failed', err);
                flashPlaceholder(t().sttError);
            });
    }

    // ── Boot ──────────────────────────────────────────────────
    applyLang();
    setOnline(navigator.onLine);
    resetThread();
    refreshSend();
})();
