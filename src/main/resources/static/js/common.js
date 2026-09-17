/* Общие утилиты: запросы к API с CSRF, уведомления, экранирование, ссылки в тексте задач. */
(function (global) {
  'use strict';

  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content || '';
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';

  async function api(method, url, body) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (csrfToken) headers[csrfHeader] = csrfToken;

    let res;
    try {
      res = await fetch(url, {
        method,
        headers,
        credentials: 'same-origin',
        body: body === undefined ? undefined : JSON.stringify(body)
      });
    } catch (e) {
      toast('Нет связи с сервером', 'error');
      throw e;
    }

    if (res.status === 401) {
      window.location.href = '/login?expired';
      throw new Error('unauthorized');
    }
    if (!res.ok) {
      let message = res.status === 403 ? 'Доступ запрещён (обновите страницу)' : 'Ошибка ' + res.status;
      try {
        const data = await res.json();
        if (data && data.message) message = data.message;
      } catch (_) { /* тело не JSON */ }
      toast(message, 'error');
      throw new Error(message);
    }
    if (res.status === 204) return null;
    return res.json();
  }

  function toast(message, type = 'info', timeout = 3500) {
    const host = document.getElementById('toasts');
    if (!host) return;
    const el = document.createElement('div');
    el.className = 'toast ' + type;
    el.textContent = message;
    host.appendChild(el);
    requestAnimationFrame(() => el.classList.add('show'));
    setTimeout(() => {
      el.classList.remove('show');
      setTimeout(() => el.remove(), 300);
    }, timeout);
  }

  function escapeHtml(str) {
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  const URL_RE = /https?:\/\/[^\s<>"']+/g;
  // Ключ Jira: ПРОЕКТ-123 (латиница или кириллица), без \b — он не работает с кириллицей.
  const JIRA_RE = /(?<![\p{L}\d-])(\p{Lu}[\p{Lu}\d]{1,14}-\d+)(?![\p{L}\d])/gu;

  /** Превращает URL и Jira-ключи в ссылки; остальное экранируется. */
  function linkify(text, jiraBase) {
    const base = (jiraBase || '').trim().replace(/\/+$/, '');
    let html = '';
    let last = 0;
    for (const m of String(text).matchAll(URL_RE)) {
      html += linkifyKeys(text.slice(last, m.index), base);
      const url = m[0];
      html += `<a href="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer" draggable="false">${escapeHtml(url)}</a>`;
      last = m.index + url.length;
    }
    html += linkifyKeys(text.slice(last), base);
    return html.replace(/\n/g, '<br>');
  }

  function linkifyKeys(chunk, base) {
    if (!base) return escapeHtml(chunk);
    return escapeHtml(chunk).replace(JIRA_RE, (key) =>
      `<a href="${escapeHtml(base + '/browse/' + key)}" target="_blank" rel="noopener noreferrer" draggable="false">${key}</a>`);
  }

  function fmtHours(h) {
    const n = Number(h) || 0;
    return (Number.isInteger(n) ? n : n.toFixed(1).replace(/\.0$/, '')) + 'ч';
  }

  function debounce(fn, ms) {
    let t;
    return (...args) => {
      clearTimeout(t);
      t = setTimeout(() => fn(...args), ms);
    };
  }

  /* --- Даты в виде строк yyyy-MM-dd, без сдвига часовых поясов --- */
  const pad = (n) => String(n).padStart(2, '0');
  const D = {
    parse(iso) {
      const [y, m, d] = iso.split('-').map(Number);
      return new Date(y, m - 1, d);
    },
    fmt(date) {
      return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate());
    },
    today() {
      return D.fmt(new Date());
    },
    addDays(iso, n) {
      const d = D.parse(iso);
      d.setDate(d.getDate() + n);
      return D.fmt(d);
    },
    isWeekend(iso) {
      const w = D.parse(iso).getDay();
      return w === 0 || w === 6;
    },
    range(from, to) {
      const out = [];
      for (let c = from; c <= to; c = D.addDays(c, 1)) out.push(c);
      return out;
    },
    DOW: ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'],
    MONTHS: ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь', 'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь'],
    MONTHS_GEN: ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'],
    long(iso) {
      const d = D.parse(iso);
      return d.getDate() + ' ' + D.MONTHS_GEN[d.getMonth()] + ' ' + d.getFullYear();
    },
    short(iso) {
      const d = D.parse(iso);
      return pad(d.getDate()) + '.' + pad(d.getMonth() + 1);
    }
  };

  /* --- Тема оформления: атрибут data-theme на <html>, cookie для серверного рендера --- */
  const THEMES = ['retrowave', '90s'];
  const theme = {
    get() {
      const t = document.documentElement.dataset.theme;
      return THEMES.includes(t) ? t : 'retrowave';
    },
    set(name, { silent = false } = {}) {
      if (!THEMES.includes(name)) return;
      const prev = theme.get();
      document.documentElement.dataset.theme = name;
      document.cookie = 'theme=' + name + '; path=/; max-age=31536000; SameSite=Lax';
      try { localStorage.setItem('theme', name); } catch (_) { /* приватный режим */ }
      theme.syncSwitches();
      if (!silent && prev !== name) {
        document.dispatchEvent(new CustomEvent('themechange', { detail: { theme: name, previous: prev } }));
      }
    },
    is90s() {
      return theme.get() === '90s';
    },
    syncSwitches() {
      const current = theme.get();
      document.querySelectorAll('.theme-btn').forEach((b) => {
        const active = b.dataset.themeChoice === current;
        b.classList.toggle('active', active);
        b.setAttribute('aria-pressed', String(active));
      });
    }
  };

  document.addEventListener('click', (e) => {
    const btn = e.target.closest('.theme-btn');
    if (btn) theme.set(btn.dataset.themeChoice);
  });
  theme.syncSwitches();

  /* Диалоги: закрытие по кнопкам [data-close] и клику по подложке */
  document.addEventListener('click', (e) => {
    const closeBtn = e.target.closest('[data-close]');
    if (closeBtn) {
      closeBtn.closest('dialog')?.close();
      return;
    }
    if (e.target instanceof HTMLDialogElement && e.target.open) {
      const r = e.target.getBoundingClientRect();
      const inside = e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom;
      if (!inside) e.target.close();
    }
  });

  global.App = { api, toast, escapeHtml, linkify, fmtHours, debounce, D, theme };
})(window);
