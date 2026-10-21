var isSingleView = false;
var autoSkipChallenge = false;
var useSliderMode = false;
var EMPTY_IMG_SRC = 'data:image/gif;base64,R0lGOODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';

// --- Utility Functions ---
function toImgSrc(imgLike) {
    if (!imgLike) return '';
    var s = (typeof imgLike === 'string') ? imgLike : extractImageLike(imgLike);
    if (!s) return '';
    s = s.trim();

    if (s.indexOf('data:') === 0) {
        // Remove internal whitespace that sometimes creeps into 4chan payloads
        return s.replace(/\s+/g, '');
    }

    // Protocol-relative or absolute URL
    if (s.indexOf('//') === 0) return 'https:' + s;
    if (s.indexOf('http://') === 0 || s.indexOf('https://') === 0) return s;

    // Relative path (like /captcha?...)
    if (s.indexOf('/') === 0) return 'https://sys.4chan.org' + s;

    // Pure Base64 payload (fallback check)
    if (/^[A-Za-z0-9+/=_-]+$/.test(s) && s.length > 20) {
        var clean = s.replace(/-/g, '+').replace(/_/g, '/');
        // Guess MIME if possible, default to PNG as it's the most common for captchas
        var mime = 'image/png';
        if (clean.indexOf('iVBOR') === 0) mime = 'image/png';
        else if (clean.indexOf('/9j/') === 0) mime = 'image/jpeg';
        else if (clean.indexOf('R0lGOD') === 0) mime = 'image/gif';
        else if (clean.indexOf('UklGR') === 0) mime = 'image/webp';
        return 'data:' + mime + ';base64,' + clean;
    }

    return s;
}

function looksLikeImageString(s) {
    if (!s || typeof s !== 'string') return false;
    var t = s.trim();
    if (!t) return false;
    if (t.indexOf('data:image/') === 0) return true;
    if (t.indexOf('//') === 0) return true;
    if (t.indexOf('<svg') === 0 || t.indexOf('<?xml') === 0) return true;
    if (t.indexOf('/') === 0 || t.indexOf('./') === 0) {
        if (/\.(png|jpe?g|gif|webp|svg)(\?|#|$)/i.test(t)) return true;
    }
    if (t.length > 20) {
        var u = t.replace(/\s+/g, '');
        if (/^[A-Za-z0-9+/=_-]+$/.test(u)) return true;
    }
    return false;
}

function extractImageLike(v) {
    if (!v) return '';
    if (typeof v === 'string') return v;
    if (typeof v === 'object') {
        var keys = ['img', 'image', 'svg', 'data', 'src', 'url', 'uri', 'href', 'path', 'b64', 'png', 'jpg', 'jpeg', 'webp', 'gif', 'target_image', 'targetImg', 'target_img'];
        for (var i = 0; i < keys.length; i++) {
            var k = keys[i];
            if (v[k] != null && typeof v[k] === 'string') return v[k];
        }
    }
    return '';
}

function getChoices(task) {
    if (!task || typeof task !== 'object') return [];
    var direct = null;
    var keys = ['choices', 'images', 'imgs', 'tiles', 'options', 'pool', 'items', 'rows', 'list'];
    for (var i = 0; i < keys.length; i++) {
        var k = keys[i];
        if (task[k] && Array.isArray(task[k])) {
            direct = task[k];
            break;
        }
    }
    if (direct && Array.isArray(direct)) {
        if (direct.length && Array.isArray(direct[0])) {
            var flat = [];
            direct.forEach(function(row) { if (Array.isArray(row)) flat = flat.concat(row); });
            direct = flat;
        }
        return direct;
    }
    var best = [];
    for (var prop in task) {
        if (!Object.prototype.hasOwnProperty.call(task, prop)) continue;
        var v = task[prop];
        if (!Array.isArray(v) || !v.length) continue;
        var count = 0;
        for (var j = 0; j < v.length; j++) {
            var img = extractImageLike(v[j]);
            if (looksLikeImageString(img)) count++;
        }
        if (count > best.length) best = v;
    }
    return best;
}

function getTarget(task) {
    if (!task || typeof task !== 'object') return '';
    var keys = ['target', 'target_image', 'targetImg', 'target_img', 'needle', 'query', 'object', 'prompt', 'pick', 'goal', 'image', 'img', 'pic'];
    for (var i = 0; i < keys.length; i++) {
        var k = keys[i];
        var val = task[k];
        var img = extractImageLike(val);
        if (img && looksLikeImageString(img)) return img;
    }
    for (var prop in task) {
        if (!Object.prototype.hasOwnProperty.call(task, prop)) continue;
        var v = task[prop];
        if (typeof v === 'string' && looksLikeImageString(v)) return v;
        if (typeof v === 'object' && v) {
            var vv = extractImageLike(v);
            if (vv && looksLikeImageString(vv)) return vv;
        }
    }
    return '';
}

function parse4chanTaskHtml(html) {
    if (!html || typeof html !== 'string') return null;
    var s = html.trim();
    var targetSrc = '';

    var imgMatch = s.match(/<img[^>]+src\s*=\s*["']([^"']+)["']/i) || s.match(/<img[^>]+src\s*=\s*([^>\s]+)/i);
    if (imgMatch) targetSrc = imgMatch[1].trim().replace(/^["']|["']$/g, '');

    var div = document.createElement('div');
    div.innerHTML = s;

    var imgs = div.querySelectorAll('img');
    if (imgs.length && !targetSrc && imgs[0].src) {
         targetSrc = imgs[0].getAttribute('src') || imgs[0].src;
    }
    if (targetSrc && targetSrc.indexOf('data:') !== 0 && targetSrc.indexOf('http') !== 0) {
        targetSrc = toImgSrc(targetSrc) || targetSrc;
    }

    var all = div.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
        var el = all[i];
        if (el.tagName === 'IMG') {
            el.parentNode && el.parentNode.removeChild(el);
            continue;
        }
        var st = window.getComputedStyle ? window.getComputedStyle(el) : (el.style || {});
        var isHidden = st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0' || (parseInt(st.width) <= 1 && parseInt(st.height) <= 1);
        if (isHidden) {
            el.parentNode && el.parentNode.removeChild(el);
        }
    }

    var instructionHtml = div.innerHTML.replace(/\s+/g, ' ').trim();
    if (!instructionHtml && s.indexOf('<') < 0) instructionHtml = s;

    return (targetSrc || instructionHtml) ? { targetSrc: targetSrc, instruction: instructionHtml } : null;
}

function get4chanTaskParsed(task) {
    if (!task) return null;
    if (typeof task === 'string') return parse4chanTaskHtml(task);
    if (typeof task !== 'object') return null;
    var keys = ['html', 'content', 'markup', 'body', 'task', 't_task', 'task_html', 'prompt_html', 'str', 's'];
    for (var i = 0; i < keys.length; i++) {
        var v = task[keys[i]];
        if (typeof v === 'string') {
            var parsed = parse4chanTaskHtml(v);
            if (parsed) return parsed;
        }
    }
    return null;
}

function getInstructionText(task) {
    var fallback = 'Select the image containing the object shown on the right.';
    if (!task || typeof task !== 'object') return fallback;

    var parsed = get4chanTaskParsed(task);
    if (parsed && parsed.instruction) return parsed.instruction;

    function extractTextLike(v) {
        if (!v) return '';
        if (typeof v === 'string') {
            var t = v.trim();
            if (t && t.length > 0 && t.length < 500) return t;
            return '';
        }
        if (Array.isArray(v)) {
            for (var i = 0; i < v.length; i++) {
                var inner = extractTextLike(v[i]);
                if (inner) return inner;
            }
            return '';
        }
        if (typeof v === 'object') {
            var k2 = ['text', 'msg', 'prompt', 'instruction', 'instructions', 'question', 'title', 'label', 'desc', 'description', 'hint', 'value', 'str', 's'];
            for (var j = 0; j < k2.length; j++) {
                var vv = v[k2[j]];
                if (vv) {
                    var extracted = extractTextLike(vv);
                    if (extracted) return extracted;
                }
            }
        }
        return '';
    }

    var keys = ['instruction', 'instructions', 'prompt', 'question', 'text', 'msg', 'title', 'label', 'desc', 'description', 'hint', 'query', 'goal', 'pick', 'instruction_text', 'instruction_msg', 'str', 's'];
    for (var i = 0; i < keys.length; i++) {
        var v = extractTextLike(task[keys[i]]);
        if (v && typeof v === 'string') {
            if (v.indexOf('<') < 0) {
                v = v.replace(/\b(exactly\s+)?(\d+)\s+(stars?|spikes?|points?|sides?|objects?)\b/gi, '<b>$1$2 $3</b>');
            }
            return v;
        }
    }
    return fallback;
}

// --- App Functions ---

function showMsgButton(label, disabled, initialSeconds) {
    var msg = document.getElementById('t-msg');
    if (!msg) return;
    var existing = document.getElementById('t-msg-btn');
    if (!existing) {
        existing = document.createElement('button');
        existing.id = 't-msg-btn';
        existing.type = 'button';
        existing.onclick = function() {
            if (this.disabled) return;
            this.disabled = true;
            this.innerText = 'Loading...';
            requestCaptcha();
        };
        msg.appendChild(existing);
    }

    if (disabled && initialSeconds !== undefined && initialSeconds > 0) {
        existing.textContent = (label || 'Get Captcha') + ' (' + initialSeconds + 's)';
        existing.disabled = true;
    } else {
        existing.textContent = label || 'Get Captcha';
        existing.disabled = !!disabled;
    }
}

function hideMsgButton() {
    var existing = document.getElementById('t-msg-btn');
    if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
}

var expiryInterval = null;
// Exact expiry time is 115 secs, I added 1 additional sec for reasons
var expirySeconds = 116;

function showJsOverlay(message, title, isError, showGetCaptcha, showReset) {
    var old = document.getElementById('clover-overlay');
    if (old && old.parentNode) old.parentNode.removeChild(old);

    var ov = document.createElement('div');
    ov.id = 'clover-overlay';
    ov.className = 't-client-overlay';

    var content = document.createElement('div');
    content.className = 'ov-content';

    if (title) {
        var h = document.createElement('div');
        h.className = isError ? 'ov-title-error' : 'ov-title';
        h.textContent = title;
        content.appendChild(h);
    }

    var msgEl = document.createElement('div');
    msgEl.className = isError ? 'ov-msg-error' : 'ov-msg';
    var escaped = message.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    msgEl.innerHTML = escaped.replace(/\n/g, '<br>');
    content.appendChild(msgEl);

    if (showGetCaptcha) {
        var btn = document.createElement('button');
        btn.className = 'btn';
        btn.textContent = 'Get New Captcha';
        btn.onclick = function() { requestCaptcha(); };
        content.appendChild(btn);
    }

    ov.appendChild(content);

    if (showReset) {
        var r = document.createElement('div');
        r.className = 'ov-reset-link';
        r.textContent = '\u26a0\ufe0f Reset Session';
        r.onclick = function() { CaptchaCallback.onResetSession(); };
        ov.appendChild(r);
    }

    document.body.appendChild(ov);

    if (expiryInterval) { clearInterval(expiryInterval); expiryInterval = null; }
    if (cooldownInterval) { clearInterval(cooldownInterval); cooldownInterval = null; }
}

function showClientOverlay(msg) {
    var tm = document.getElementById('t-msg');
    if (tm) tm.style.display = 'none';
    showJsOverlay(msg, '', false, true, false);
}

function startExpiryTimer(ttl) {
    if (expiryInterval) clearInterval(expiryInterval);
    expirySeconds = (ttl && ttl > 0) ? Math.round(ttl) : 116;
    updateExpiryDisplay();
    expiryInterval = setInterval(function() {
        expirySeconds--;
        if (expirySeconds <= 0) {
            clearInterval(expiryInterval);
            expirySeconds = 0;
            document.getElementById('t-native').style.display = 'none';
            showClientOverlay('Captcha expired.\nPlease request a new one.');
        }
        updateExpiryDisplay();
    }, 1000);
}

function updateExpiryDisplay() {
    var el = document.getElementById('t-expiry-wrap');
    if (el) {
        el.textContent = 'Expires: ' + expirySeconds + 's';
        if (expirySeconds < 30) {
            el.style.color = '#e53935';
        } else {
            el.style.color = '';
        }
    }
}

function parseCountdown(val) {
    var n = parseInt(val, 10);
    return (n >= 0 && !isNaN(n)) ? n : 0;
}

var SIGNIN_LINK = 'https://sys.4chan.org/signin';
function escapeHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
function cooldownMessage(pcdMsg) {
    if (!pcdMsg || typeof pcdMsg !== 'string') return 'Please wait a while before making a post or verify your email here.';
    var safe = escapeHtml(pcdMsg);
    if (safe.toLowerCase().indexOf('verify your email') >= 0) {
        return safe.replace(/\bverify your email here\.?/gi, '<a href="' + SIGNIN_LINK + '" target="_blank">verify your email here.</a>');
    }
    return safe + ' <a href="' + SIGNIN_LINK + '" target="_blank">Verify your email here.</a>';
}

var cooldownInterval = null;
function buildFromJson(data) {
    if (data.twister) data = data.twister;
    if (data.single_view !== undefined) isSingleView = !!data.single_view;
    if (data.auto_skip_challenge !== undefined) autoSkipChallenge = !!data.auto_skip_challenge;

    // Overlay mode: Java sends { overlay: { message, title, isError, showGetCaptcha, showReset } }
    if (data.overlay) {
        var ov = data.overlay;
        showJsOverlay(
            ov.message || '',
            ov.title || (ov.isError ? 'Error while posting' : ''),
            !!ov.isError,
            ov.showGetCaptcha !== false,
            ov.showReset !== false
        );
        try { CaptchaCallback.onCaptchaLoaded(); } catch(e) {}
        return;
    }

    if (data.ticket) {
        window.savedTicket = data.ticket;
        try { CaptchaCallback.saveTicket(data.ticket); } catch(e) {}
    }

    if (data.pcd_msg != null && (data.pcd === undefined || data.pcd === null)) {
        document.getElementById('t-msg').style.display = 'block';
        document.getElementById('t-msg').classList.add('with-warning');
        document.getElementById('t-msg-main').innerHTML = cooldownMessage(data.pcd_msg);
        document.getElementById('t-msg-secondary').textContent = '';
        showMsgButton('Get Captcha', false);
        try { CaptchaCallback.onCaptchaLoaded(); } catch(e) {}
        return;
    }

    if (cooldownInterval) {
        clearInterval(cooldownInterval);
        cooldownInterval = null;
    }

    if (data.pcd !== undefined && data.pcd !== null) {
        var pcd = parseCountdown(data.pcd);
        document.getElementById('t-msg').style.display = 'block';
        document.getElementById('t-msg').classList.add('with-warning');
        document.getElementById('t-msg-main').innerHTML = 'Please wait a while before making a post or <a href="' + SIGNIN_LINK + '" target="_blank">verify your email here.</a>';

        function updatePcd() {
            document.getElementById('t-msg-secondary').textContent = pcd + 's';
            if (pcd <= 0) {
                clearInterval(cooldownInterval);
                cooldownInterval = null;
                document.getElementById('t-msg-secondary').textContent = '0s';
                document.getElementById('t-msg-main').textContent = 'You can now request a captcha.';
                showMsgButton('Get Captcha', false);
                return;
            }
            showMsgButton('Get Captcha', true, pcd);
            pcd--;
        }
        updatePcd();
        cooldownInterval = setInterval(updatePcd, 1000);
        try { CaptchaCallback.onCaptchaLoaded(); } catch(e) {}
        return;
    } else if (data.error) {
        var cd = parseCountdown(data.cd);
        document.getElementById('t-msg').style.display = 'block';
        document.getElementById('t-msg').classList.add('with-warning');
        document.getElementById('t-msg-main').textContent = data.error;

        function updateCd() {
            document.getElementById('t-msg-secondary').textContent = cd + 's';
            if (cd <= 0) {
                clearInterval(cooldownInterval);
                cooldownInterval = null;
                document.getElementById('t-msg').classList.remove('with-warning');
                showMsgButton('Get Captcha', false);
                return;
            }
            showMsgButton('Get Captcha', true, cd);
            cd--;
        }
        updateCd();
        cooldownInterval = setInterval(updateCd, 1000);
        try { CaptchaCallback.onCaptchaLoaded(); } catch(e) {}
        return;
    }

    document.getElementById('t-msg').style.display = 'none';
    document.getElementById('t-msg').classList.remove('with-warning');
    hideMsgButton();
    document.getElementById('t-challenge').value = data.challenge || '';

    if (data.challenge === 'noop') {
        globalOnCaptchaEntered();
        return;
    }

    if (data.tasks && Array.isArray(data.tasks) && data.tasks.length > 0) {
        if (isSingleView) {
            buildSingleViewTasksUI(data);
        } else {
            buildNativeTasksUI(data);
        }
        return;
    }

    CaptchaCallback.onCaptchaLoaded();
}

function buildNativeTasksUI(data) {
    document.getElementById('t-stepped-ui').style.display = 'block';
    document.getElementById('t-single-ui').style.display = 'none';

    var targetWrap = document.getElementById('t-native-target-wrap');
    if (targetWrap) {
        targetWrap.onclick = function() {
            this.classList.toggle('zoomed');
        };
    }

    var tasks = data.tasks;
    var total = tasks.length;
    var currentIndex = 0;
    var answers = [];

    document.getElementById('t-native').style.display = 'block';
    startExpiryTimer(data.ttl);

    var globalTarget = getTarget(data) || '';

    function renderChallenge() {
        var task = tasks[currentIndex];
        var choices = getChoices(task);
        var parsed = get4chanTaskParsed(task);
        var target = (parsed && parsed.targetSrc) ? parsed.targetSrc : (getTarget(task) || globalTarget);
        if (!target && task && typeof task === 'object') {
            var idx = (typeof task.target === 'number') ? task.target : (task.target && typeof task.target.index === 'number' ? task.target.index : null);
            if (idx !== null && idx >= 0 && idx < choices.length) target = choices[idx];
        }

        var headerEl = document.getElementById('t-native-counter');
        if (headerEl) headerEl.textContent = 'Task ' + (currentIndex + 1) + ' of ' + total;

        var instrEl = document.getElementById('t-native-instructions-text');
        if (instrEl) {
            var html = getInstructionText(task);
            if (html.indexOf('<') >= 0) instrEl.innerHTML = html;
            else instrEl.textContent = html;
        }

        var targetWrap = document.getElementById('t-native-target-wrap');
        var targetImg = document.getElementById('t-native-target');
        
        if (!target) {
            if (targetWrap) targetWrap.style.display = 'none';
        } else {
            if (targetWrap) targetWrap.style.display = 'block';
            var targetSrc = toImgSrc(target) || EMPTY_IMG_SRC;
            if (targetImg) {
                targetImg.src = targetSrc;
                targetImg.alt = 'Target';
            }
        }

        var wrap = document.getElementById('t-native-choices');
        wrap.innerHTML = '';

        var sliderWrap = document.getElementById('t-slider-wrap-stepped');
        var sliderInput = document.getElementById('t-slider-input-stepped');
        var sliderTrack = document.getElementById('t-slider-track-stepped');
        var toggleBtn = document.getElementById('t-toggle-slider');

        function setupSlider() {
            sliderTrack.innerHTML = '';
            choices.forEach(function(choice) {
                var img = document.createElement('img');
                img.className = 't-slider-img';
                img.src = toImgSrc(choice);
                sliderTrack.appendChild(img);
            });
            sliderInput.max = choices.length - 1;
            sliderInput.oninput = function() {
                var idx = parseInt(this.value);
                sliderTrack.style.transform = 'translateX(-' + (idx * 100) + '%)';
                answers[currentIndex] = idx;
                document.getElementById('t-native-next').disabled = false;
                reportCaptchaAnswers(answers.join(','));
            };
            // Sync slider value with answer if it exists
            if (answers[currentIndex] !== undefined) {
                sliderInput.value = answers[currentIndex];
                sliderTrack.style.transform = 'translateX(-' + (answers[currentIndex] * 100) + '%)';
            } else {
                sliderInput.value = 0;
                sliderTrack.style.transform = 'translateX(0)';
            }
        }

        toggleBtn.onclick = function() {
            useSliderMode = !useSliderMode;
            this.textContent = useSliderMode ? 'Use Grid' : 'Use Slider';
            renderChallenge();
        };

        toggleBtn.style.display = 'inline-block';

        if (useSliderMode) {
            document.getElementById('t-native-choices-wrap').style.display = 'none';
            sliderWrap.style.display = 'block';
            setupSlider();
        } else {
            document.getElementById('t-native-choices-wrap').style.display = 'block';
            sliderWrap.style.display = 'none';
            choices.forEach(function(choice, idx) {
                var div = document.createElement('div');
                div.className = 't-choice' + (answers[currentIndex] === idx ? ' selected' : '');
                var img = document.createElement('img');
                img.src = toImgSrc(choice);
                div.appendChild(img);
                div.onclick = function() {
                    answers[currentIndex] = idx;
                    var all = wrap.querySelectorAll('.t-choice');
                    for (var i = 0; i < all.length; i++) all[i].classList.remove('selected');
                    div.classList.add('selected');
                    document.getElementById('t-native-next').disabled = false;
                    reportCaptchaAnswers(answers.join(','));
                    if (autoSkipChallenge && currentIndex < total - 1) {
                        setTimeout(advanceChallenge, 150);
                    }
                };
                wrap.appendChild(div);
            });
        }

        var btn = document.getElementById('t-native-next');
        btn.disabled = answers[currentIndex] === undefined;
        btn.textContent = (currentIndex === total - 1) ? 'Submit' : 'Next';

        var backBtn = document.getElementById('t-native-back');
        if (backBtn) backBtn.style.display = currentIndex > 0 ? 'block' : 'none';
    }

    function advanceChallenge() {
        if (answers[currentIndex] === undefined) return;
        if (currentIndex === total - 1) {
            var challengeEl = document.getElementById('t-challenge');
            var challenge = challengeEl ? challengeEl.value : '';
            var r = answers.join('');
            var msg = { twister: { challenge: challenge, response: r } };
            if (window.savedTicket) msg.twister.ticket = window.savedTicket;
            try { window.parent.postMessage(msg, '*'); } catch (e) {}
            if (expiryInterval) clearInterval(expiryInterval);
            if (typeof CaptchaCallback !== 'undefined' && typeof CaptchaCallback.onCaptchaEntered === 'function') {
                CaptchaCallback.onCaptchaEntered(challenge, r);
            }
            return;
        }
        currentIndex++;
        renderChallenge();
    }

    document.getElementById('t-native-back').onclick = function() {
        if (currentIndex > 0) {
            currentIndex--;
            renderChallenge();
        }
    };

    document.getElementById('t-native-next').onclick = advanceChallenge;

    window.__restoreCaptchaAnswers = function(ch, csv) {
        try {
            if (currentChallengeValue() !== ch) return;
            var parts = (csv || '').split(',');
            for (var i = 0; i < total && i < parts.length; i++) {
                if (parts[i] !== '') answers[i] = parseInt(parts[i], 10);
            }
            currentIndex = 0;
            renderChallenge();
        } catch (e) {}
    };

    renderChallenge();
    CaptchaCallback.onCaptchaLoaded();
}

function buildSingleViewTasksUI(data) {
    document.getElementById('t-stepped-ui').style.display = 'none';
    document.getElementById('t-single-ui').style.display = 'block';
    document.getElementById('t-native').style.display = 'block';

    var container = document.getElementById('t-single-tasks-container');
    container.innerHTML = '';

    var tasks = data.tasks;
    var total = tasks.length;
    var answers = new Array(total).fill(undefined);
    var renderers = [];

    startExpiryTimer(data.ttl);

    var globalTarget = getTarget(data) || '';

    var toggleBtn = document.getElementById('t-toggle-slider');
    toggleBtn.style.display = 'none'; // Individual buttons used instead

    tasks.forEach(function(task, taskIdx) {
        var taskDiv = document.createElement('div');
        taskDiv.className = 't-single-task';
        var taskUseSlider = false;

        var header = document.createElement('div');
        header.style.display = 'flex';
        header.style.justifyContent = 'space-between';
        header.style.alignItems = 'center';
        header.style.marginBottom = '8px';

        var title = document.createElement('div');
        title.style.fontSize = '14px';
        title.style.fontWeight = 'bold';
        title.textContent = 'Task ' + (taskIdx + 1) + ' of ' + total;
        header.appendChild(title);

        var taskSliderBtn = document.createElement('button');
        taskSliderBtn.className = 't-slider-btn';
        taskSliderBtn.type = 'button';
        taskSliderBtn.textContent = 'Use Slider';
        header.appendChild(taskSliderBtn);
        taskDiv.appendChild(header);

        var instrDiv = document.createElement('div');
        instrDiv.style.fontSize = '14px';
        instrDiv.style.lineHeight = '1.4';
        instrDiv.style.marginBottom = '12px';
        instrDiv.style.color = 'var(--c-muted)';
        instrDiv.style.display = 'flex';
        instrDiv.style.gap = '12px';
        instrDiv.style.alignItems = 'flex-start';
        instrDiv.style.justifyContent = 'space-between';

        var instrText = document.createElement('div');
        instrText.style.flex = '1';
        instrText.style.minWidth = '0';
        var html = getInstructionText(task);
        if (html.indexOf('<') >= 0) instrText.innerHTML = html;
        else instrText.textContent = html;
        instrDiv.appendChild(instrText);

        var choices = getChoices(task);
        var parsed = get4chanTaskParsed(task);
        var target = (parsed && parsed.targetSrc) ? parsed.targetSrc : (getTarget(task) || globalTarget);
        if (!target && task && typeof task === 'object') {
            var idx = (typeof task.target === 'number') ? task.target : (task.target && typeof task.target.index === 'number' ? task.target.index : null);
            if (idx !== null && idx >= 0 && idx < choices.length) target = choices[idx];
        }

        if (target) {
            var targetWrap = document.createElement('div');
            targetWrap.className = 't-target-wrap';
            targetWrap.style.flexShrink = '0';
            targetWrap.style.width = '80px';
            targetWrap.style.height = '80px';
            targetWrap.style.border = '2px solid var(--c-input-border)';
            targetWrap.style.borderRadius = '4px';
            targetWrap.style.overflow = 'hidden';
            targetWrap.style.background = 'var(--c-input-bg)';

            var targetImg = document.createElement('img');
            targetImg.style.width = '100%';
            targetImg.style.height = '100%';
            targetImg.style.objectFit = 'contain';
            targetImg.src = toImgSrc(target) || EMPTY_IMG_SRC;
            targetWrap.appendChild(targetImg);
            targetWrap.onclick = function() {
                this.classList.toggle('zoomed');
            };
            instrDiv.appendChild(targetWrap);
        }
        taskDiv.appendChild(instrDiv);

        var contentWrap = document.createElement('div');

        function renderChoices() {
            contentWrap.innerHTML = '';
            var choicesGrid = document.createElement('div');
            choicesGrid.style.display = 'flex';
            choicesGrid.style.flexDirection = 'column';
            choicesGrid.style.gap = '8px';
            choicesGrid.style.alignItems = 'center';

            choices.forEach(function(choice, choiceIdx) {
                var choiceDiv = document.createElement('div');
                choiceDiv.className = 't-choice' + (answers[taskIdx] === choiceIdx ? ' selected' : '');
                var img = document.createElement('img');
                img.src = toImgSrc(choice);
                choiceDiv.appendChild(img);

                choiceDiv.onclick = function() {
                    answers[taskIdx] = choiceIdx;
                    var all = choicesGrid.querySelectorAll('.t-choice');
                    for (var i = 0; i < all.length; i++) all[i].classList.remove('selected');
                    choiceDiv.classList.add('selected');
                    updateSubmitButtonStatus();
                    reportCaptchaAnswers(answers.join(','));
                    if (autoSkipChallenge) {
                        var tasks = container.querySelectorAll('.t-single-task');
                        if (taskIdx + 1 < tasks.length) {
                            tasks[taskIdx + 1].scrollIntoView({behavior: 'smooth', block: 'start'});
                        } else {
                            document.getElementById('t-native-submit').scrollIntoView({behavior: 'smooth', block: 'nearest'});
                        }
                    }
                };
                choicesGrid.appendChild(choiceDiv);
            });
            contentWrap.appendChild(choicesGrid);
        }

        function renderSlider() {
            contentWrap.innerHTML = '';
            var sWrap = document.createElement('div');
            sWrap.className = 't-slider-wrap';

            var viewport = document.createElement('div');
            viewport.className = 't-slider-viewport';

            var track = document.createElement('div');
            track.className = 't-slider-track';

            choices.forEach(function(choice) {
                var img = document.createElement('img');
                img.className = 't-slider-img';
                img.src = toImgSrc(choice);
                track.appendChild(img);
            });
            viewport.appendChild(track);
            sWrap.appendChild(viewport);

            var sInput = document.createElement('input');
            sInput.type = 'range';
            sInput.className = 't-slider-input';
            sInput.min = '0';
            sInput.max = choices.length - 1;
            sInput.step = '1';

            if (answers[taskIdx] !== undefined) {
                sInput.value = answers[taskIdx];
                track.style.transform = 'translateX(-' + (answers[taskIdx] * 100) + '%)';
            } else {
                sInput.value = 0;
                track.style.transform = 'translateX(0)';
            }

            sInput.oninput = function() {
                var idx = parseInt(this.value);
                track.style.transform = 'translateX(-' + (idx * 100) + '%)';
                answers[taskIdx] = idx;
                updateSubmitButtonStatus();
                reportCaptchaAnswers(answers.join(','));
            };
            sWrap.appendChild(sInput);
            contentWrap.appendChild(sWrap);
        }

        taskSliderBtn.onclick = function() {
            taskUseSlider = !taskUseSlider;
            this.textContent = taskUseSlider ? 'Use Grid' : 'Use Slider';
            if (taskUseSlider) renderSlider();
            else renderChoices();
        };

        renderChoices();
        renderers.push(renderChoices);
        taskDiv.appendChild(contentWrap);
        container.appendChild(taskDiv);
    });

    function updateSubmitButtonStatus() {
        var allAnswered = answers.every(function(a) { return a !== undefined; });
        document.getElementById('t-native-submit').disabled = !allAnswered;
    }

    document.getElementById('t-native-submit').disabled = true;
    window.__restoreCaptchaAnswers = function(ch, csv) {
        try {
            if (currentChallengeValue() !== ch) return;
            var parts = (csv || '').split(',');
            for (var i = 0; i < total && i < parts.length; i++) {
                if (parts[i] !== '') answers[i] = parseInt(parts[i], 10);
            }
            for (var j = 0; j < renderers.length; j++) renderers[j]();
            updateSubmitButtonStatus();
        } catch (e) {}
    };
    document.getElementById('t-native-submit').onclick = function() {
        var allAnswered = answers.every(function(a) { return a !== undefined; });
        if (!allAnswered) return;
        var challengeEl = document.getElementById('t-challenge');
        var challenge = challengeEl ? challengeEl.value : '';
        var r = answers.join('');
        var msg = { twister: { challenge: challenge, response: r } };
        if (window.savedTicket) msg.twister.ticket = window.savedTicket;
        try { window.parent.postMessage(msg, '*'); } catch (e) {}
        if (expiryInterval) clearInterval(expiryInterval);
        if (typeof CaptchaCallback !== 'undefined' && typeof CaptchaCallback.onCaptchaEntered === 'function') {
            CaptchaCallback.onCaptchaEntered(challenge, r);
        }
    };

    CaptchaCallback.onCaptchaLoaded();
}

function requestCaptcha() {
    if (expiryInterval) clearInterval(expiryInterval);
    if (cooldownInterval) clearInterval(cooldownInterval);
    CaptchaCallback.onRequestCaptcha();
}

function currentChallengeValue() {
    var el = document.getElementById('t-challenge');
    return el ? el.value : '';
}

function reportCaptchaAnswers(csv) {
    try {
        if (typeof CaptchaCallback !== 'undefined' && typeof CaptchaCallback.onAnswersChanged === 'function') {
            CaptchaCallback.onAnswersChanged(currentChallengeValue(), csv || '');
        }
    } catch (e) {}
}

window.__restoreCaptchaAnswers = null;

window.__syncExpiry = function(s) { try { startExpiryTimer(s); } catch (e) {} };

window.globalOnCaptchaEntered = function() {
    if (expiryInterval) clearInterval(expiryInterval);
    if (cooldownInterval) clearInterval(cooldownInterval);
    var c = document.getElementById("t-challenge").value;
    var msg = { twister: { challenge: c, response: "" } };
    if (window.savedTicket) msg.twister.ticket = window.savedTicket;
    try { window.parent.postMessage(msg, '*'); } catch (e) {}
    if (typeof CaptchaCallback !== 'undefined' && typeof CaptchaCallback.onCaptchaEntered === 'function') {
        CaptchaCallback.onCaptchaEntered(c, '');
    }
};

(function preventKeyboard() {
    function blurInput(el) {
        if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) el.blur();
    }
    document.addEventListener('focusin', function(e) { blurInput(e.target); });
    window.addEventListener('load', function() { blurInput(document.activeElement); });
})();

window.addEventListener("load", function() {
    try {
        var el = document.getElementById('clover-payload');
        var jsonStr = el ? el.textContent : '';
        var data = JSON.parse(jsonStr);
        buildFromJson(data);
    } catch (e) {
        var mainEl = document.getElementById('t-msg-main');
        if (mainEl) mainEl.textContent = 'Error loading captcha. Please try again.';
        var secEl = document.getElementById('t-msg-secondary');
        if (secEl) secEl.textContent = '';
        showMsgButton('Retry', false);
        try { CaptchaCallback.onCaptchaLoaded(); } catch (ex) {}
    }
});
