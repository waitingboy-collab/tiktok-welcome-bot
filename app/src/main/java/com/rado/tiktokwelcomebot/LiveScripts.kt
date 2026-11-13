package com.rado.tiktokwelcomebot

import org.json.JSONObject

/**
 * JavaScript, който се изпълнява в страницата на TikTok LIVE (desktop версия).
 *
 * На Android няма работеща библиотека за събитията на лайва, затова четем чата директно
 * от страницата. Ако TikTok смени страницата, обнови селекторите в CFG / INPUT_SELECTORS.
 * Бутонът „🔍 Диагностика“ показва кои data-e2e атрибути има в момента на страницата.
 */
object LiveScripts {

    /** Слуша чата и праща всяко ново съобщение към Kotlin през RadoBridge.onEvent(json). */
    val SCRAPER = """
(function () {
  if (window.__radoBot) return 'already';
  window.__radoBot = true;

  const CFG = {
    // Елементи-съобщения в чата
    item: [
      '[data-e2e="chat-message"]',
      '[data-e2e="enter-message"]',
      '[data-e2e="social-message"]',
      '[data-e2e="gift-message"]'
    ].join(','),
    // Името на автора вътре в съобщението
    name: '[data-e2e="message-owner-name"]',
    // Резервно разпознаване по текст (английски и български интерфейс)
    join: /joined|присъедини/i,
    follow: /followed|последва/i,
    gift: /sent|изпрати|подари/i
  };

  const seen = new WeakSet();

  function classify(el, text) {
    const kind = el.getAttribute('data-e2e') || '';
    if (kind.indexOf('enter') >= 0) return 'join';
    if (kind.indexOf('gift') >= 0) return 'gift';
    if (kind.indexOf('social') >= 0) return CFG.follow.test(text) ? 'follow' : 'social';
    if (kind.indexOf('chat') >= 0) return 'chat';
    if (CFG.join.test(text)) return 'join';
    if (CFG.follow.test(text)) return 'follow';
    if (CFG.gift.test(text)) return 'gift';
    return 'chat';
  }

  function handle(el) {
    if (seen.has(el)) return;
    seen.add(el);
    const nameEl = el.querySelector(CFG.name);
    const name = nameEl ? (nameEl.getAttribute('title') || nameEl.textContent || '').trim() : '';
    let text = (el.innerText || '').trim();
    if (name && text.indexOf(name) === 0) text = text.slice(name.length).replace(/^[\s:：]+/, '');
    if (!name && !text) return;
    RadoBridge.onEvent(JSON.stringify({ type: classify(el, text), name: name, text: text }));
  }

  function scan(node) {
    if (!(node instanceof Element)) return;
    if (node.matches(CFG.item)) handle(node);
    node.querySelectorAll(CFG.item).forEach(handle);
  }

  // Старите съобщения не се броят — иначе ще поздравим цялата история.
  document.querySelectorAll(CFG.item).forEach(function (e) { seen.add(e); });

  new MutationObserver(function (mutations) {
    mutations.forEach(function (m) { m.addedNodes.forEach(scan); });
  }).observe(document.body, { childList: true, subtree: true });

  return 'ok';
})();
""".trimIndent()

    /** Пише текст в полето за коментар и го изпраща. Връща 'ok' или 'no-input'. */
    private val SEND_TEMPLATE = """
(function (text) {
  const INPUT_SELECTORS = [
    '[data-e2e="comment-input"] [contenteditable]',
    'div[contenteditable="plaintext-only"]',
    'div[contenteditable="true"]'
  ];
  const SEND_SELECTORS = [
    '[data-e2e="comment-post"]',
    '[data-e2e="comment-send"]'
  ];

  let el = null;
  for (const s of INPUT_SELECTORS) { el = document.querySelector(s); if (el) break; }
  if (!el) return 'no-input';

  el.scrollIntoView({ block: 'center' });
  el.focus();
  document.execCommand('selectAll', false, null);
  document.execCommand('insertText', false, text);

  setTimeout(function () {
    for (const s of SEND_SELECTORS) {
      const btn = document.querySelector(s);
      if (btn) { btn.click(); return; }
    }
    const opts = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
    el.dispatchEvent(new KeyboardEvent('keydown', opts));
    el.dispatchEvent(new KeyboardEvent('keypress', opts));
    el.dispatchEvent(new KeyboardEvent('keyup', opts));
  }, 300);

  return 'ok';
})(__TEXT__);
""".trimIndent()

    fun send(text: String): String = SEND_TEMPLATE.replace("__TEXT__", JSONObject.quote(text))

    /** Брои всички data-e2e атрибути на страницата — за намиране на нови селектори. */
    val DIAGNOSE = """
(function () {
  const counts = {};
  document.querySelectorAll('[data-e2e]').forEach(function (e) {
    const k = e.getAttribute('data-e2e');
    counts[k] = (counts[k] || 0) + 1;
  });
  return JSON.stringify(counts);
})();
""".trimIndent()
}
