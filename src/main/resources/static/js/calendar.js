/* Календарь: бесконечная горизонтальная прокрутка по дням, строки — сотрудники,
   задачи (в т.ч. растянутые на несколько рабочих дней) и события (простой / аренда / отпуск)
   в ячейках, drag-and-drop задач между днями и сотрудников в списке. */
(function () {
  'use strict';

  const { api, toast, escapeHtml, linkify, fmtHours, debounce, D } = window.App;

  const board = document.getElementById('board');
  const inner = document.getElementById('boardInner');
  const hdrRow = document.getElementById('hdrRow');
  const rangeLabel = document.getElementById('rangeLabel');
  const monthLabel = document.getElementById('monthLabel');
  const boardEmpty = document.getElementById('boardEmpty');
  const jiraInput = document.getElementById('jiraBase');

  const CHUNK = 14;       // дней за одну подгрузку
  const EDGE = 900;       // за сколько px до края начинать подгрузку
  const NORM = 8;         // норма часов в день при ставке 1

  const ABS = {
    DOWNTIME: { label: 'Простой', cls: 'abs-downtime', noteLabel: 'Причина', notePlaceholder: 'например, ждём макеты' },
    RENTAL: { label: 'Аренда', cls: 'abs-rental', noteLabel: 'Кому / куда', notePlaceholder: 'например, команда биллинга' },
    VACATION: { label: 'Отпуск', cls: 'abs-vacation', noteLabel: 'Комментарий', notePlaceholder: '' }
  };

  const state = {
    employees: [],
    tasks: new Map(),     // id -> task
    cells: new Map(),     // "empId|day" -> [taskId] (растянутая задача лежит в каждом своём дне)
    absences: new Map(),  // id -> absence
    absCells: new Map(),  // "empId|day" -> [absenceId]
    start: null,
    end: null,
    jiraBase: '',
    loading: { left: false, right: false },
    drag: null,           // { type: 'task' | 'emp', id }
    hover: null,          // подсвеченная цель drop
    releases: new Set(),
    epics: new Set(),
    viewMonth: null       // месяц (yyyy-MM), который сейчас виден слева
  };
  const els = { heads: new Map(), rows: new Map(), cells: new Map() };

  const cellKey = (empId, day) => empId + '|' + day;
  const today = D.today();

  /* ---------- Задачи: дни и часы ---------- */

  /** Норма дня сотрудника: 8ч × ставка. */
  function normFor(empId) {
    const emp = employeeById(empId);
    return NORM * (emp && emp.rate ? Number(emp.rate) : 1);
  }

  /** Часы, которые задача занимает в календаре (сервер отдаёт готовое значение: досрочная — потраченные). */
  const taskTotal = (t) => (t.hours !== undefined && t.hours !== null
    ? Number(t.hours)
    : (t.completedEarly && t.spent !== null && t.spent !== undefined ? Number(t.spent) : (Number(t.estimate) || 0) + (Number(t.overtime) || 0)));

  /** Сколько рабочих дней займёт задача: по норме в день, остаток на последний (12ч при 8 → 2 дня). */
  const spanDays = (total, norm) => (norm > 0 ? Math.max(1, Math.min(60, Math.ceil(total / norm - 1e-9))) : 1);
  const taskSpan = (t) => (t.days && t.days > 0 ? t.days : spanDays(taskTotal(t), normFor(t.employeeId)));

  /** Дни задачи: день начала (даже выходной), затем следующие рабочие дни. */
  function taskDays(t) {
    const out = [t.day];
    let cur = t.day;
    const days = taskSpan(t);
    while (out.length < days) {
      cur = D.addDays(cur, 1);
      if (!D.isWeekend(cur)) out.push(cur);
    }
    return out;
  }

  /** Часы задачи в её день с индексом idx: по норме сотрудника в день, остаток на последний. */
  function taskHoursOn(t, idx) {
    const total = taskTotal(t);
    const norm = normFor(t.employeeId);
    const days = taskSpan(t);
    if (idx < 0 || idx >= days) return 0;
    if (idx === days - 1) return Math.max(0, total - norm * idx);
    return Math.max(0, Math.min(norm, total - norm * idx));
  }

  /** Стабильный оттенок для эпика: одинаковое имя — одинаковый цвет. */
  function epicHue(name) {
    let h = 0;
    for (const ch of String(name)) h = (h * 31 + ch.codePointAt(0)) % 360;
    return h;
  }

  /* ---------- Данные ---------- */

  function pushTo(map, key, id) {
    if (!map.has(key)) map.set(key, []);
    const list = map.get(key);
    if (!list.includes(id)) list.push(id);
  }

  function ingestTasks(list) {
    for (const t of list) {
      if (state.tasks.has(t.id)) continue;
      state.tasks.set(t.id, t);
      for (const day of taskDays(t)) pushTo(state.cells, cellKey(t.employeeId, day), t.id);
      if (t.release) state.releases.add(t.release);
      if (t.epic) state.epics.add(t.epic);
    }
  }

  /** Перечитать задачи загруженного периода (после смены ставки сотрудника меняется растяжка). */
  async function reloadTasks() {
    state.tasks.clear();
    state.cells.clear();
    ingestTasks(await api('GET', `/api/tasks?from=${state.start}&to=${state.end}`));
    renderDays(D.range(state.start, state.end));
  }

  function removeTask(id) {
    const t = state.tasks.get(id);
    if (!t) return null;
    state.tasks.delete(id);
    for (const day of taskDays(t)) {
      const list = state.cells.get(cellKey(t.employeeId, day));
      if (list) {
        const i = list.indexOf(id);
        if (i >= 0) list.splice(i, 1);
      }
    }
    return t;
  }

  function ingestAbsences(list) {
    for (const a of list) {
      if (state.absences.has(a.id)) continue;
      state.absences.set(a.id, a);
      for (const day of D.range(a.startDay, a.endDay)) pushTo(state.absCells, cellKey(a.employeeId, day), a.id);
    }
  }

  function removeAbsence(id) {
    const a = state.absences.get(id);
    if (!a) return null;
    state.absences.delete(id);
    for (const day of D.range(a.startDay, a.endDay)) {
      const list = state.absCells.get(cellKey(a.employeeId, day));
      if (list) {
        const i = list.indexOf(id);
        if (i >= 0) list.splice(i, 1);
      }
    }
    return a;
  }

  function cellTasks(empId, day) {
    const ids = state.cells.get(cellKey(empId, day)) || [];
    return ids.map((id) => state.tasks.get(id)).filter(Boolean)
      .sort((a, b) => a.position - b.position || a.id - b.id);
  }

  function cellAbsences(empId, day) {
    const ids = state.absCells.get(cellKey(empId, day)) || [];
    return ids.map((id) => state.absences.get(id)).filter(Boolean).sort((a, b) => a.id - b.id);
  }

  function employeeById(id) {
    return state.employees.find((e) => e.id === id);
  }

  /** Дни диапазона, ограниченные загруженным периодом. */
  function loadedRange(from, to) {
    const a = from < state.start ? state.start : from;
    const b = to > state.end ? state.end : to;
    return a <= b ? D.range(a, b) : [];
  }

  /* ---------- Рендер ---------- */

  /** Выходной без задач у всех сотрудников показываем узкой колонкой. */
  function isNarrow(day) {
    return D.isWeekend(day) && !state.employees.some((e) => (state.cells.get(cellKey(e.id, day)) || []).length > 0);
  }

  function dayClasses(base, day) {
    return base + (D.isWeekend(day) ? ' weekend' : '') + (day === today ? ' today' : '') + (isNarrow(day) ? ' narrow' : '');
  }

  function makeHead(day) {
    const d = D.parse(day);
    const el = document.createElement('div');
    el.className = dayClasses('day-head', day);
    el.dataset.day = day;
    const month = d.getDate() === 1 ? `<span class="dh-month">${D.MONTHS[d.getMonth()]} ${d.getFullYear()}</span>` : '';
    el.innerHTML = `<span class="dh-date">${D.short(day)}</span><span class="dh-dow">${D.DOW[d.getDay()]}</span>${month}`;
    els.heads.set(day, el);
    return el;
  }

  function makeCell(emp, day) {
    const el = document.createElement('div');
    el.className = dayClasses('day-cell', day);
    el.dataset.emp = emp.id;
    el.dataset.day = day;
    el.innerHTML = '<div class="absences"></div><div class="tasks"></div>' +
      '<div class="cell-foot"><span class="sum"></span>' +
      '<button class="add-btn" type="button" title="Добавить задачу или событие">+</button></div>';
    els.cells.set(cellKey(emp.id, day), el);
    return el;
  }

  function makeEmpCell(emp) {
    const el = document.createElement('div');
    el.className = 'emp-cell';
    el.draggable = true;
    el.dataset.emp = emp.id;
    el.title = 'Перетащите, чтобы изменить порядок. Клик — редактировать';
    el.style.setProperty('--emp-color', emp.color);
    const rate = Number(emp.rate) || 1;
    el.innerHTML = `<span class="grip" aria-hidden="true"></span>` +
      `<span class="dot"></span>` +
      `<span class="emp-name">${escapeHtml(emp.name)}</span>` +
      (rate !== 1 ? `<span class="emp-rate" title="Ставка ${rate}: ${fmtHours(NORM * rate)} в день">×${rate}</span>` : '') +
      `<span class="emp-total" title="Часы задач за месяц в поле зрения"></span>`;
    return el;
  }

  /** Сумма часов задач каждого сотрудника за месяц, который сейчас виден в календаре. */
  let totalsQueued = false;
  function scheduleTotals() {
    if (totalsQueued) return;
    totalsQueued = true;
    queueMicrotask(() => {
      totalsQueued = false;
      updateEmpTotals();
    });
  }

  function updateEmpTotals() {
    const month = state.viewMonth;
    const sums = new Map();
    if (month) {
      for (const t of state.tasks.values()) {
        taskDays(t).forEach((day, i) => {
          if (day.startsWith(month)) sums.set(t.employeeId, (sums.get(t.employeeId) || 0) + taskHoursOn(t, i));
        });
      }
    }
    for (const emp of state.employees) {
      const el = els.rows.get(emp.id)?.querySelector('.emp-total');
      if (!el) continue;
      const h = sums.get(emp.id) || 0;
      el.textContent = h ? fmtHours(h) : '';
    }
  }

  function makeRow(emp) {
    const row = document.createElement('div');
    row.className = 'emp-row';
    row.dataset.emp = emp.id;
    row.appendChild(makeEmpCell(emp));
    for (const day of D.range(state.start, state.end)) row.appendChild(makeCell(emp, day));
    els.rows.set(emp.id, row);
    return row;
  }

  const JIRA_SHORT_RE = /(\p{Lu}[\p{Lu}\d]{1,14})-(\d+)/u;

  /** Короткая подпись для шапки карточки (тема 90s): номер Jira-задачи или начало текста. */
  function shortId(title) {
    const m = String(title).match(JIRA_SHORT_RE);
    if (m) return '#' + m[2];
    const first = String(title).split('\n')[0].trim();
    return first.length > 18 ? first.slice(0, 17) + '…' : first;
  }

  function taskCard(t, emp, day) {
    const days = taskDays(t);
    const part = days.indexOf(day);
    const spanned = days.length > 1;
    const isStart = part <= 0;
    const el = document.createElement('div');
    el.className = 'task' + (spanned ? ' task-span' : '') + (spanned && !isStart ? ' task-cont' : '');
    el.draggable = isStart;
    el.dataset.id = t.id;
    el.style.setProperty('--emp-color', emp ? emp.color : '#00e5ff');
    if (!isStart) el.title = 'Продолжение задачи, начатой ' + D.long(t.day);

    if (t.completedEarly) el.classList.add('task-early');
    const hasEstimate = t.estimate !== null && t.estimate !== undefined;
    const hours = spanned ? fmtHours(taskHoursOn(t, Math.max(0, part))) : (hasEstimate || t.overtime || t.completedEarly ? fmtHours(taskTotal(t)) : '');
    const meta = [];
    if (t.release) meta.push(`<span class="badge rel" title="Релиз">${escapeHtml(t.release)}</span>`);
    if (spanned) meta.push(`<span class="badge span" title="Растянута на ${days.length} раб. дн., всего ${fmtHours(taskTotal(t))}">${part + 1}/${days.length}</span>`);
    if (t.overtime && !t.completedEarly) meta.push(`<span class="badge ot" title="Сверх оценки ${fmtHours(t.estimate || 0)}">+${fmtHours(t.overtime)}</span>`);
    if (t.completedEarly) meta.push(`<span class="badge early" title="Завершено досрочно: потрачено ${fmtHours(t.spent || 0)} из ${fmtHours(t.estimate || 0)}">✓ ${fmtHours(t.spent || 0)}/${fmtHours(t.estimate || 0)}</span>`);
    if (hours) meta.push(`<span class="est" title="${spanned ? 'Часов в этот день' : 'Часы задачи'}">${hours}</span>`);
    const epic = t.epic ? `<span class="badge epic" style="--epic-h:${epicHue(t.epic)}" title="Эпик">${escapeHtml(t.epic)}</span>` : '';

    el.innerHTML =
      `<div class="task-head"><span class="task-sq"></span><span class="task-short">${escapeHtml(shortId(t.title))}</span>` +
      `<span class="task-hours">${hours}</span></div>` +
      `<div class="task-body">${epic}<div class="task-title">${linkify(t.title, state.jiraBase)}</div>` +
      (meta.length ? `<div class="task-meta">${meta.join('')}</div>` : '') + '</div>';
    return el;
  }

  function absenceChip(a) {
    const meta = ABS[a.type] || ABS.DOWNTIME;
    const el = document.createElement('div');
    el.className = 'absence ' + meta.cls;
    el.dataset.abs = a.id;
    const hours = a.type === 'DOWNTIME' && a.hoursPerDay ? ' ' + fmtHours(a.hoursPerDay) : '';
    const period = a.startDay === a.endDay ? D.long(a.startDay) : `${D.short(a.startDay)} — ${D.short(a.endDay)}`;
    el.title = `${meta.label}${hours}: ${period}` + (a.note ? ` · ${a.note}` : '');
    el.innerHTML = `<span class="abs-label">${meta.label}${hours}</span>` +
      (a.note ? `<span class="abs-note">${escapeHtml(a.note)}</span>` : '');
    return el;
  }

  function renderCell(empId, day) {
    const el = els.cells.get(cellKey(empId, day));
    if (!el) return;
    const emp = employeeById(empId);
    const tasks = cellTasks(empId, day);
    const absences = cellAbsences(empId, day);

    el.querySelector('.absences').replaceChildren(...absences.map(absenceChip));
    el.querySelector('.tasks').replaceChildren(...tasks.map((t) => taskCard(t, emp, day)));

    el.classList.remove('abs-downtime', 'abs-rental', 'abs-vacation', 'has-absence');
    if (absences.length) {
      el.classList.add('has-absence', (ABS[absences[0].type] || ABS.DOWNTIME).cls);
    }

    // занятость дня — только рабочее время, т.е. часы задач; простой, отпуск и аренда в неё не входят
    let sum = tasks.reduce((s, t) => s + taskHoursOn(t, taskDays(t).indexOf(day)), 0);
    sum = Math.round(sum * 100) / 100;

    const norm = normFor(empId);
    const sumEl = el.querySelector('.sum');
    let cls = 'sum ';
    if (sum === 0) cls += 'zero';
    else if (sum < norm - 1e-9) cls += 'under';
    else if (Math.abs(sum - norm) < 1e-9) cls += 'ok';
    else cls += 'over';
    sumEl.className = cls;
    sumEl.textContent = tasks.length ? 'Σ ' + fmtHours(sum) : '';
    sumEl.title = 'Занятость за день: часы задач';
    el.classList.toggle('has-tasks', tasks.length > 0);
    scheduleTotals();
  }

  function renderRow(empId) {
    for (const day of D.range(state.start, state.end)) renderCell(empId, day);
  }

  /** Как только в выходном появляется задача — расширяем колонку, и наоборот. */
  function updateDayWidth(day) {
    if (!D.isWeekend(day)) return;
    const narrow = isNarrow(day);
    els.heads.get(day)?.classList.toggle('narrow', narrow);
    for (const e of state.employees) els.cells.get(cellKey(e.id, day))?.classList.toggle('narrow', narrow);
  }

  function renderDays(days) {
    for (const day of days) {
      for (const e of state.employees) renderCell(e.id, day);
      updateDayWidth(day);
    }
  }

  /** Перерисовать все дни задачи (в пределах загруженного периода). */
  function renderTaskDays(t) {
    for (const day of taskDays(t)) {
      if (day >= state.start && day <= state.end) {
        renderCell(t.employeeId, day);
        updateDayWidth(day);
      }
    }
  }

  function buildBoard() {
    for (const row of els.rows.values()) row.remove();
    els.rows.clear();
    els.cells.clear();
    els.heads.clear();
    hdrRow.querySelectorAll('.day-head').forEach((h) => h.remove());

    for (const day of D.range(state.start, state.end)) hdrRow.appendChild(makeHead(day));
    for (const emp of state.employees) inner.appendChild(makeRow(emp));
    renderDays(D.range(state.start, state.end));
    boardEmpty.hidden = state.employees.length > 0;
    updateRangeLabel();
  }

  function updateRangeLabel() {
    rangeLabel.textContent = `${D.long(state.start)} — ${D.long(state.end)}`;
  }

  /** Первый день, видимый слева от края доски (после закреплённой колонки имён). */
  function firstVisibleDay() {
    const cornerW = hdrRow.firstElementChild.offsetWidth;
    const x = board.scrollLeft + cornerW + 8;
    for (const head of hdrRow.children) {
      if (head.dataset.day && head.offsetLeft + head.offsetWidth > x) return head.dataset.day;
    }
    return null;
  }

  function updateMonthLabel() {
    const day = firstVisibleDay();
    if (!day) return;
    const d = D.parse(day);
    monthLabel.textContent = `${D.MONTHS[d.getMonth()]} ${d.getFullYear()}`;
    const month = day.slice(0, 7);
    if (month !== state.viewMonth) {
      state.viewMonth = month;
      scheduleTotals();
    }
  }

  /** Прокрутка на n дней относительно первого видимого дня (кнопки ◀ ▶). */
  function shiftDays(n) {
    const first = firstVisibleDay();
    if (!first) return;
    const head = els.heads.get(D.addDays(first, n));
    const cornerW = hdrRow.firstElementChild.offsetWidth;
    if (head) {
      board.scrollTo({ left: Math.max(0, head.offsetLeft - cornerW), behavior: 'smooth' });
    } else {
      board.scrollBy({ left: n * 150, behavior: 'smooth' });
    }
  }

  document.getElementById('btnPrevWeek').addEventListener('click', () => shiftDays(-7));
  document.getElementById('btnNextWeek').addEventListener('click', () => shiftDays(7));

  /* ---------- Бесконечная прокрутка ---------- */

  async function fetchRange(from, to) {
    const [tasks, absences] = await Promise.all([
      api('GET', `/api/tasks?from=${from}&to=${to}`),
      api('GET', `/api/absences?from=${from}&to=${to}`)
    ]);
    ingestTasks(tasks);
    ingestAbsences(absences);
  }

  async function extend(dir) {
    if (state.loading[dir]) return;
    state.loading[dir] = true;
    try {
      const from = dir === 'right' ? D.addDays(state.end, 1) : D.addDays(state.start, -CHUNK);
      const to = dir === 'right' ? D.addDays(state.end, CHUNK) : D.addDays(state.start, -1);
      // данные кладём в состояние до создания колонок, чтобы ширина выходных была верной сразу
      await fetchRange(from, to);
      const days = D.range(from, to);
      const prevWidth = board.scrollWidth;

      if (dir === 'right') {
        for (const day of days) hdrRow.appendChild(makeHead(day));
        for (const emp of state.employees) {
          const row = els.rows.get(emp.id);
          for (const day of days) row.appendChild(makeCell(emp, day));
        }
        state.end = to;
      } else {
        const headRef = hdrRow.children[1] || null;
        for (const day of days) hdrRow.insertBefore(makeHead(day), headRef);
        for (const emp of state.employees) {
          const row = els.rows.get(emp.id);
          const ref = row.children[1] || null;
          for (const day of days) row.insertBefore(makeCell(emp, day), ref);
        }
        state.start = from;
        // сохраняем визуальную позицию после вставки колонок слева
        board.scrollLeft += board.scrollWidth - prevWidth;
      }
      renderDays(days);
      updateRangeLabel();
      refreshReleaseList();
    } finally {
      state.loading[dir] = false;
    }
  }

  async function checkEdges() {
    for (let i = 0; i < 4; i++) {
      const { scrollLeft, clientWidth, scrollWidth } = board;
      if (scrollLeft + clientWidth > scrollWidth - EDGE && !state.loading.right) {
        await extend('right');
      } else if (scrollLeft < EDGE && !state.loading.left) {
        await extend('left');
      } else {
        break;
      }
    }
  }

  let ticking = false;
  board.addEventListener('scroll', () => {
    if (ticking) return;
    ticking = true;
    requestAnimationFrame(() => {
      ticking = false;
      updateMonthLabel();
      checkEdges();
    });
  }, { passive: true });

  function scrollToToday(smooth) {
    const head = els.heads.get(today);
    if (!head) return;
    const cornerW = hdrRow.firstElementChild.offsetWidth;
    const left = Math.max(0, head.offsetLeft - cornerW - 24);
    board.scrollTo({ left, behavior: smooth ? 'smooth' : 'auto' });
  }

  document.getElementById('btnToday').addEventListener('click', () => scrollToToday(true));

  /* ---------- Drag and drop ---------- */

  function setHover(el, cls) {
    if (state.hover && state.hover.el !== el) state.hover.el.classList.remove(state.hover.cls, 'drop-before', 'drop-after');
    if (el) {
      el.classList.add(cls);
      state.hover = { el, cls };
    } else {
      state.hover = null;
    }
  }

  board.addEventListener('dragstart', (e) => {
    const task = e.target.closest('.task');
    const empCell = e.target.closest('.emp-cell');
    if (task) {
      if (task.classList.contains('task-cont')) {
        e.preventDefault();
        return;
      }
      state.drag = { type: 'task', id: Number(task.dataset.id) };
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', 'task:' + task.dataset.id);
      requestAnimationFrame(() => task.classList.add('dragging'));
    } else if (empCell) {
      state.drag = { type: 'emp', id: Number(empCell.dataset.emp) };
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', 'emp:' + empCell.dataset.emp);
      requestAnimationFrame(() => empCell.closest('.emp-row').classList.add('dragging'));
    }
  });

  board.addEventListener('dragover', (e) => {
    if (!state.drag) return;
    if (state.drag.type === 'task') {
      const cell = e.target.closest('.day-cell');
      if (!cell) { setHover(null); return; }
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
      setHover(cell, 'drop-target');
    } else {
      const row = e.target.closest('.emp-row');
      if (!row || Number(row.dataset.emp) === state.drag.id) { setHover(null); return; }
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
      const r = row.getBoundingClientRect();
      const before = e.clientY < r.top + r.height / 2;
      setHover(row, before ? 'drop-before' : 'drop-after');
      row.classList.toggle('drop-before', before);
      row.classList.toggle('drop-after', !before);
    }
  });

  board.addEventListener('drop', async (e) => {
    if (!state.drag) return;
    e.preventDefault();
    const drag = state.drag;
    const hover = state.hover;
    cleanupDrag();
    if (!hover) return;

    if (drag.type === 'task') {
      const cell = hover.el;
      await moveTask(drag.id, Number(cell.dataset.emp), cell.dataset.day);
    } else {
      const row = hover.el;
      const before = hover.cls === 'drop-before';
      await reorderEmployee(drag.id, Number(row.dataset.emp), before);
    }
  });

  board.addEventListener('dragend', cleanupDrag);

  function cleanupDrag() {
    setHover(null);
    board.querySelectorAll('.dragging').forEach((el) => el.classList.remove('dragging'));
    state.drag = null;
  }

  async function moveTask(id, empId, day) {
    const t = state.tasks.get(id);
    if (!t || (t.employeeId === empId && t.day === day)) return;
    const saved = await api('PATCH', `/api/tasks/${id}/move`, { employeeId: empId, day });
    const old = removeTask(id);
    ingestTasks([saved]);
    renderTaskDays(old);
    renderTaskDays(saved);
  }

  async function reorderEmployee(dragId, targetId, before) {
    const list = state.employees.slice();
    const fromIdx = list.findIndex((e) => e.id === dragId);
    const dragged = list.splice(fromIdx, 1)[0];
    let toIdx = list.findIndex((e) => e.id === targetId);
    if (!before) toIdx += 1;
    list.splice(toIdx, 0, dragged);

    const dragRow = els.rows.get(dragId);
    const targetRow = els.rows.get(targetId);
    inner.insertBefore(dragRow, before ? targetRow : targetRow.nextSibling);
    state.employees = list;
    try {
      state.employees = await api('PUT', '/api/employees/order', { ids: list.map((e) => e.id) });
    } catch (_) {
      // сервер отклонил — вернём порядок с сервера
      state.employees = await api('GET', '/api/employees');
      for (const e of state.employees) inner.appendChild(els.rows.get(e.id));
    }
  }

  /* ---------- Клики по доске ---------- */

  board.addEventListener('click', (e) => {
    if (e.target.closest('a')) return; // ссылка в задаче — просто переход
    const addBtn = e.target.closest('.add-btn');
    if (addBtn) {
      const cell = addBtn.closest('.day-cell');
      openDialog({ kind: 'task', empId: Number(cell.dataset.emp), day: cell.dataset.day });
      return;
    }
    const abs = e.target.closest('.absence');
    if (abs) {
      const a = state.absences.get(Number(abs.dataset.abs));
      if (a) openDialog({ kind: 'absence', empId: a.employeeId, day: a.startDay, absence: a });
      return;
    }
    const task = e.target.closest('.task');
    if (task) {
      const t = state.tasks.get(Number(task.dataset.id));
      if (t) openDialog({ kind: 'task', empId: t.employeeId, day: t.day, task: t });
      return;
    }
    const empCell = e.target.closest('.emp-cell');
    if (empCell) {
      openEmployeeDialog(employeeById(Number(empCell.dataset.emp)));
    }
  });

  board.addEventListener('dblclick', (e) => {
    if (e.target.closest('.task') || e.target.closest('.absence') || e.target.closest('a') || e.target.closest('button')) return;
    const cell = e.target.closest('.day-cell');
    if (cell) openDialog({ kind: 'task', empId: Number(cell.dataset.emp), day: cell.dataset.day });
  });

  /* ---------- Диалог задачи / события ---------- */

  const dlg = document.getElementById('taskDialog');
  const form = document.getElementById('taskForm');
  const kindSwitch = document.getElementById('kindSwitch');
  const taskFields = document.getElementById('taskFields');
  const absenceFields = document.getElementById('absenceFields');
  const f = {
    title: document.getElementById('taskTitle'),
    release: document.getElementById('taskRelease'),
    epic: document.getElementById('taskEpic'),
    estimate: document.getElementById('taskEstimate'),
    overtime: document.getElementById('taskOvertime'),
    spanHint: document.getElementById('spanHint'),
    earlyBlock: document.getElementById('earlyBlock'),
    earlyToggle: document.getElementById('earlyToggle'),
    earlyField: document.getElementById('earlyField'),
    spent: document.getElementById('taskSpent'),
    absEmployee: document.getElementById('absEmployee'),
    absFrom: document.getElementById('absFrom'),
    absTo: document.getElementById('absTo'),
    absHours: document.getElementById('absHours'),
    absHoursField: document.getElementById('absHoursField'),
    absNote: document.getElementById('absNote'),
    absNoteLabel: document.getElementById('absNoteLabel'),
    absHint: document.getElementById('absHint'),
    del: document.getElementById('taskDelete'),
    submit: document.getElementById('taskSubmit')
  };
  let ctx = null;   // { kind: 'task' | 'DOWNTIME' | 'RENTAL' | 'VACATION', empId, day, task?, absence? }

  function setKind(kind) {
    ctx.kind = kind;
    const isTask = kind === 'task';
    kindSwitch.querySelectorAll('.seg-btn').forEach((b) => b.classList.toggle('active', b.dataset.kind === kind));
    taskFields.hidden = !isTask;
    absenceFields.hidden = isTask;
    f.title.required = isTask;
    if (isTask) {
      document.getElementById('taskDialogTitle').textContent = ctx.task ? 'Задача' : 'Новая задача';
      updateSpanHint();
    } else {
      const meta = ABS[kind];
      document.getElementById('taskDialogTitle').textContent = (ctx.absence ? '' : 'Новое событие: ') + meta.label;
      f.absHoursField.hidden = kind !== 'DOWNTIME';
      f.absNoteLabel.textContent = meta.noteLabel;
      f.absNote.placeholder = meta.notePlaceholder;
      f.absHint.textContent = kind === 'DOWNTIME'
        ? 'Часы простоя учитываются в занятости дня и в аналитике. Пусто — весь день.'
        : (kind === 'RENTAL' ? 'Сотрудник занят в другой команде: дни не входят в ёмкость.' : 'Дни отпуска не входят в ёмкость сотрудника.');
    }
  }

  kindSwitch.addEventListener('click', (e) => {
    const btn = e.target.closest('.seg-btn');
    if (btn && ctx && !ctx.task && !ctx.absence) setKind(btn.dataset.kind);
  });

  function fillEmployeeSelect(selectedId) {
    f.absEmployee.replaceChildren(...state.employees.map((e) => {
      const o = document.createElement('option');
      o.value = e.id;
      o.textContent = e.name;
      o.selected = e.id === selectedId;
      return o;
    }));
  }

  function openDialog(c) {
    ctx = { ...c };
    const emp = employeeById(ctx.empId);
    const editing = Boolean(ctx.task || ctx.absence);
    kindSwitch.hidden = editing;
    document.getElementById('taskDialogSub').textContent = emp && ctx.kind === 'task' ? `${emp.name} · ${D.long(ctx.day)}` : '';
    f.del.hidden = !editing;

    // задача
    const t = ctx.task;
    f.title.value = t ? t.title : '';
    f.release.value = t && t.release ? t.release : '';
    f.epic.value = t && t.epic ? t.epic : '';
    f.estimate.value = t && t.estimate !== null && t.estimate !== undefined ? t.estimate : '';
    f.overtime.value = t && t.overtime ? t.overtime : '';
    setEarly(Boolean(t && t.completedEarly), t && t.spent !== null && t.spent !== undefined ? t.spent : '');
    f.earlyBlock.hidden = !t;   // досрочно завершить можно только существующую задачу

    // событие
    const a = ctx.absence;
    fillEmployeeSelect(a ? a.employeeId : ctx.empId);
    f.absFrom.value = a ? a.startDay : ctx.day;
    f.absTo.value = a ? a.endDay : ctx.day;
    f.absHours.value = a && a.hoursPerDay ? a.hoursPerDay : '';
    f.absNote.value = a && a.note ? a.note : '';

    refreshReleaseList();
    refreshEpicList();
    setKind(a ? a.type : (ctx.kind === 'absence' ? 'DOWNTIME' : 'task'));
    dlg.showModal();
    (ctx.kind === 'task' ? f.title : f.absFrom).focus();
  }

  let earlyOn = false;
  function setEarly(on, spent) {
    earlyOn = on;
    f.earlyField.hidden = !on;
    f.earlyToggle.classList.toggle('active', on);
    f.earlyToggle.textContent = on ? '✓ Завершено досрочно — отменить' : '✓ Завершено досрочно';
    if (on) {
      if (spent !== undefined) f.spent.value = spent;
      if (f.spent.value === '') f.spent.value = f.estimate.value;
      f.spent.focus();
    }
    f.overtime.disabled = on;
    updateSpanHint();
  }
  f.earlyToggle.addEventListener('click', () => setEarly(!earlyOn));

  function updateSpanHint() {
    const estimate = Number(f.estimate.value) || 0;
    const overtime = earlyOn ? 0 : (Number(f.overtime.value) || 0);
    const norm = ctx ? normFor(ctx.empId) : NORM;
    const total = earlyOn ? (Number(f.spent.value) || 0) : estimate + overtime;
    const parts = [];
    f.spanHint.classList.remove('warn');
    if (earlyOn) {
      parts.push(`завершена досрочно: занимает ${fmtHours(total)} вместо ${fmtHours(estimate)}`);
      if (estimate > total) parts.push(`сэкономлено ${fmtHours(estimate - total)}`);
    } else if (overtime > 0) {
      parts.push(`оценка ${fmtHours(estimate)} + ${fmtHours(overtime)} сверх = ${fmtHours(total)}`);
    }
    if (norm !== NORM) parts.push(`норма сотрудника ${fmtHours(norm)} в день`);
    if (total > norm) {
      const days = spanDays(total, norm);
      const split = [];
      for (let i = 0; i < days; i++) split.push(fmtHours(i === days - 1 ? total - norm * i : norm));
      parts.push(`растянется на ${days} раб. дн.: ${split.join(' + ')}`);
    } else if (total > 0) {
      parts.push('помещается в один день');
    }
    f.spanHint.textContent = parts.join(' · ');
  }
  [f.estimate, f.overtime, f.spent].forEach((el) => el.addEventListener('input', updateSpanHint));

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    f.submit.disabled = true;
    try {
      if (ctx.kind === 'task') await submitTask(); else await submitAbsence();
    } catch (_) { /* ошибка уже показана */ } finally {
      f.submit.disabled = false;
    }
  });

  async function submitTask() {
    const title = f.title.value.trim();
    if (!title) { f.title.focus(); return; }
    const body = {
      title,
      release: f.release.value.trim(),
      epic: f.epic.value.trim(),
      estimate: f.estimate.value === '' ? null : Number(f.estimate.value),
      overtime: earlyOn || f.overtime.value === '' ? null : Number(f.overtime.value),
      completedEarly: earlyOn,
      spent: earlyOn && f.spent.value !== '' ? Number(f.spent.value) : null
    };
    let saved;
    if (ctx.task) {
      saved = await api('PUT', `/api/tasks/${ctx.task.id}`, body);
      const old = removeTask(saved.id);
      ingestTasks([saved]);
      renderTaskDays(old);
    } else {
      saved = await api('POST', '/api/tasks', { employeeId: ctx.empId, day: ctx.day, ...body });
      ingestTasks([saved]);
    }
    renderTaskDays(saved);
    dlg.close();
    toast(ctx.task ? 'Задача обновлена' : 'Задача добавлена', 'ok');
  }

  async function submitAbsence() {
    const startDay = f.absFrom.value;
    const endDay = f.absTo.value;
    if (!startDay || !endDay) return;
    if (endDay < startDay) { toast('Дата окончания раньше даты начала', 'error'); return; }
    const body = {
      employeeId: Number(f.absEmployee.value),
      type: ctx.kind,
      startDay,
      endDay,
      hoursPerDay: ctx.kind === 'DOWNTIME' && f.absHours.value !== '' ? Number(f.absHours.value) : null,
      note: f.absNote.value.trim()
    };
    let saved;
    if (ctx.absence) {
      saved = await api('PUT', `/api/absences/${ctx.absence.id}`, body);
      const old = removeAbsence(saved.id);
      ingestAbsences([saved]);
      renderAbsenceDays(old);
    } else {
      saved = await api('POST', '/api/absences', body);
      ingestAbsences([saved]);
    }
    renderAbsenceDays(saved);
    dlg.close();
    toast(ctx.absence ? 'Событие обновлено' : `${ABS[saved.type].label}: добавлено`, 'ok');
  }

  function renderAbsenceDays(a) {
    for (const day of loadedRange(a.startDay, a.endDay)) renderCell(a.employeeId, day);
  }

  // Ctrl+Enter в тексте задачи — сохранить
  f.title.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      form.requestSubmit();
    }
  });

  f.del.addEventListener('click', async () => {
    if (ctx.task) {
      const t = ctx.task;
      const ok = await confirmDialog('Удалить задачу?', t.title.split('\n')[0]);
      if (!ok) return;
      await api('DELETE', `/api/tasks/${t.id}`);
      removeTask(t.id);
      renderTaskDays(t);
      dlg.close();
      toast('Задача удалена', 'ok');
    } else if (ctx.absence) {
      const a = ctx.absence;
      const ok = await confirmDialog(`Удалить событие «${ABS[a.type].label}»?`, `${D.long(a.startDay)} — ${D.long(a.endDay)}`);
      if (!ok) return;
      await api('DELETE', `/api/absences/${a.id}`);
      removeAbsence(a.id);
      renderAbsenceDays(a);
      dlg.close();
      toast('Событие удалено', 'ok');
    }
  });

  document.getElementById('btnAddAbsence').addEventListener('click', () => {
    if (!state.employees.length) { toast('Сначала добавьте сотрудника', 'error'); return; }
    openDialog({ kind: 'absence', empId: state.employees[0].id, day: today });
  });

  function fillDatalist(id, values) {
    document.getElementById(id).replaceChildren(...[...values].sort((a, b) => a.localeCompare(b, 'ru')).map((v) => {
      const o = document.createElement('option');
      o.value = v;
      return o;
    }));
  }

  function refreshReleaseList() {
    fillDatalist('releaseList', state.releases);
  }

  function refreshEpicList() {
    fillDatalist('epicList', state.epics);
  }

  /* ---------- Диалог сотрудника ---------- */

  const PALETTE = ['#00e5ff', '#ff2fd6', '#ffd166', '#b388ff', '#39ff14', '#ff6a00', '#ff3864', '#7df9ff'];
  const employeeDialog = document.getElementById('employeeDialog');
  const employeeForm = document.getElementById('employeeForm');
  const employeeName = document.getElementById('employeeName');
  const employeeRate = document.getElementById('employeeRate');
  const employeeColor = document.getElementById('employeeColor');
  employeeRate.addEventListener('input', () => {
    const r = Number(employeeRate.value) || 0;
    document.getElementById('rateHint').textContent = r > 0 ? `${r} = ${fmtHours(NORM * r)} в день` : '1 = 8ч в день';
  });
  const employeeDelete = document.getElementById('employeeDelete');
  const palette = document.getElementById('palette');
  let employeeCtx = null;

  palette.replaceChildren(...PALETTE.map((c) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'swatch';
    b.style.setProperty('--c', c);
    b.dataset.color = c;
    b.title = c;
    return b;
  }));
  palette.addEventListener('click', (e) => {
    const b = e.target.closest('.swatch');
    if (!b) return;
    employeeColor.value = b.dataset.color;
    markSwatch();
  });
  employeeColor.addEventListener('input', markSwatch);

  function markSwatch() {
    palette.querySelectorAll('.swatch').forEach((s) => s.classList.toggle('active', s.dataset.color === employeeColor.value.toLowerCase()));
  }

  function openEmployeeDialog(emp) {
    employeeCtx = emp || null;
    document.getElementById('employeeDialogTitle').textContent = emp ? 'Сотрудник' : 'Новый сотрудник';
    employeeName.value = emp ? emp.name : '';
    employeeRate.value = emp && emp.rate ? emp.rate : 1;
    employeeRate.dispatchEvent(new Event('input'));
    employeeColor.value = emp ? emp.color : PALETTE[state.employees.length % PALETTE.length];
    employeeDelete.hidden = !emp;
    markSwatch();
    employeeDialog.showModal();
    employeeName.focus();
  }

  document.getElementById('btnAddEmployee').addEventListener('click', () => openEmployeeDialog(null));
  document.getElementById('btnAddEmployeeCorner').addEventListener('click', () => openEmployeeDialog(null));

  employeeForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const name = employeeName.value.trim();
    if (!name) { employeeName.focus(); return; }
    const body = { name, color: employeeColor.value, rate: Number(employeeRate.value) || 1 };
    try {
      if (employeeCtx) {
        const rateChanged = Number(employeeCtx.rate || 1) !== body.rate;
        const saved = await api('PUT', `/api/employees/${employeeCtx.id}`, body);
        Object.assign(employeeCtx, saved);
        const row = els.rows.get(saved.id);
        row.replaceChild(makeEmpCell(saved), row.firstElementChild);
        // при смене ставки меняется растяжка задач — перечитываем их с сервера
        if (rateChanged) await reloadTasks(); else renderRow(saved.id);
        toast('Сотрудник обновлён', 'ok');
      } else {
        const saved = await api('POST', '/api/employees', body);
        state.employees.push(saved);
        inner.appendChild(makeRow(saved));
        renderRow(saved.id);
        boardEmpty.hidden = true;
        toast('Сотрудник добавлен', 'ok');
      }
      employeeDialog.close();
    } catch (_) { /* ошибка уже показана */ }
  });

  employeeDelete.addEventListener('click', async () => {
    const emp = employeeCtx;
    if (!emp) return;
    const count = [...state.tasks.values()].filter((t) => t.employeeId === emp.id).length;
    const ok = await confirmDialog(`Удалить сотрудника ${emp.name}?`,
      count ? `Вместе с ним будут удалены все его задачи и события (в загруженном периоде задач: ${count}).` : 'Вместе с ним будут удалены его задачи и события.');
    if (!ok) return;
    await api('DELETE', `/api/employees/${emp.id}`);
    for (const t of [...state.tasks.values()]) if (t.employeeId === emp.id) removeTask(t.id);
    for (const a of [...state.absences.values()]) if (a.employeeId === emp.id) removeAbsence(a.id);
    els.rows.get(emp.id)?.remove();
    els.rows.delete(emp.id);
    state.employees = state.employees.filter((e) => e.id !== emp.id);
    for (const day of D.range(state.start, state.end)) updateDayWidth(day);
    boardEmpty.hidden = state.employees.length > 0;
    employeeDialog.close();
    toast('Сотрудник удалён', 'ok');
  });

  /* ---------- Подтверждение ---------- */

  const confirmEl = document.getElementById('confirmDialog');
  function confirmDialog(title, text) {
    document.getElementById('confirmTitle').textContent = title;
    document.getElementById('confirmText').textContent = text || '';
    return new Promise((resolve) => {
      const onClose = () => {
        confirmEl.removeEventListener('close', onClose);
        resolve(confirmEl.returnValue === 'ok');
      };
      confirmEl.addEventListener('close', onClose);
      confirmEl.returnValue = '';
      confirmEl.showModal();
    });
  }
  document.getElementById('confirmOk').value = 'ok';

  /* ---------- Настройки Jira ---------- */

  const saveJira = debounce(async () => {
    const saved = await api('PUT', '/api/settings', { jiraBaseUrl: jiraInput.value });
    state.jiraBase = saved.jiraBaseUrl;
    renderDays(D.range(state.start, state.end));
  }, 600);
  jiraInput.addEventListener('input', saveJira);

  /* ---------- Старт ---------- */

  async function init() {
    // начальный период: три недели назад (с понедельника) и восемь недель вперёд
    let start = D.addDays(today, -21);
    const dow = D.parse(start).getDay();
    start = D.addDays(start, dow === 0 ? -6 : 1 - dow);
    state.start = start;
    state.end = D.addDays(start, 7 * 8 - 1);

    const [employees, settings] = await Promise.all([
      api('GET', '/api/employees'),
      api('GET', '/api/settings'),
      fetchRange(state.start, state.end)
    ]);
    state.employees = employees;
    state.jiraBase = settings.jiraBaseUrl || '';
    jiraInput.value = state.jiraBase;
    buildBoard();
    refreshReleaseList();
    scrollToToday(false);
    updateMonthLabel();
  }

  init().catch((e) => console.error(e));
})();
