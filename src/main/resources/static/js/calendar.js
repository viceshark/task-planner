/* Календарь: бесконечная горизонтальная прокрутка по дням, строки — сотрудники,
   задачи в ячейках, drag-and-drop задач между днями и сотрудников в списке. */
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
  const NORM = 8;         // норма часов в день

  const state = {
    employees: [],
    tasks: new Map(),     // id -> task
    cells: new Map(),     // "empId|day" -> [taskId]
    start: null,
    end: null,
    jiraBase: '',
    loading: { left: false, right: false },
    drag: null,           // { type: 'task' | 'emp', id }
    hover: null,          // подсвеченная цель drop
    releases: new Set(),
    viewMonth: null       // месяц (yyyy-MM), который сейчас виден слева
  };
  const els = { heads: new Map(), rows: new Map(), cells: new Map() };

  const cellKey = (empId, day) => empId + '|' + day;
  const today = D.today();

  /* ---------- Данные ---------- */

  function ingestTasks(list) {
    for (const t of list) {
      if (state.tasks.has(t.id)) continue;
      state.tasks.set(t.id, t);
      const k = cellKey(t.employeeId, t.day);
      if (!state.cells.has(k)) state.cells.set(k, []);
      state.cells.get(k).push(t.id);
      if (t.release) state.releases.add(t.release);
    }
  }

  function removeTask(id) {
    const t = state.tasks.get(id);
    if (!t) return null;
    state.tasks.delete(id);
    const list = state.cells.get(cellKey(t.employeeId, t.day));
    if (list) {
      const i = list.indexOf(id);
      if (i >= 0) list.splice(i, 1);
    }
    return t;
  }

  function cellTasks(empId, day) {
    const ids = state.cells.get(cellKey(empId, day)) || [];
    return ids.map((id) => state.tasks.get(id)).filter(Boolean)
      .sort((a, b) => a.position - b.position || a.id - b.id);
  }

  function sumFor(empId, day) {
    return cellTasks(empId, day).reduce((s, t) => s + (Number(t.estimate) || 0), 0);
  }

  function employeeById(id) {
    return state.employees.find((e) => e.id === id);
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
    el.innerHTML = '<div class="tasks"></div>' +
      '<div class="cell-foot"><span class="sum"></span>' +
      '<button class="add-btn" type="button" title="Добавить задачу">+</button></div>';
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
    el.innerHTML = `<span class="grip" aria-hidden="true"></span>` +
      `<span class="dot"></span>` +
      `<span class="emp-name">${escapeHtml(emp.name)}</span>` +
      `<span class="emp-total" title="Часы за месяц в поле зрения"></span>`;
    return el;
  }

  /** Сумма оценок каждого сотрудника за месяц, который сейчас виден в календаре. */
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
        if (t.day.startsWith(month)) sums.set(t.employeeId, (sums.get(t.employeeId) || 0) + (Number(t.estimate) || 0));
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

  function taskCard(t, emp) {
    const el = document.createElement('div');
    el.className = 'task';
    el.draggable = true;
    el.dataset.id = t.id;
    el.style.setProperty('--emp-color', emp ? emp.color : '#00e5ff');
    const hasEstimate = t.estimate !== null && t.estimate !== undefined;
    const meta = [];
    if (t.release) meta.push(`<span class="badge rel" title="Релиз">${escapeHtml(t.release)}</span>`);
    if (hasEstimate) meta.push(`<span class="est" title="Оценка">${fmtHours(t.estimate)}</span>`);
    el.innerHTML =
      `<div class="task-head"><span class="task-sq"></span><span class="task-short">${escapeHtml(shortId(t.title))}</span>` +
      `<span class="task-hours">${hasEstimate ? fmtHours(t.estimate) : ''}</span></div>` +
      `<div class="task-body"><div class="task-title">${linkify(t.title, state.jiraBase)}</div>` +
      (meta.length ? `<div class="task-meta">${meta.join('')}</div>` : '') + '</div>';
    return el;
  }

  function renderCell(empId, day) {
    const el = els.cells.get(cellKey(empId, day));
    if (!el) return;
    const emp = employeeById(empId);
    const tasks = cellTasks(empId, day);
    const list = el.querySelector('.tasks');
    list.replaceChildren(...tasks.map((t) => taskCard(t, emp)));

    const sum = tasks.reduce((s, t) => s + (Number(t.estimate) || 0), 0);
    const sumEl = el.querySelector('.sum');
    let cls = 'sum ';
    if (sum === 0) cls += 'zero';
    else if (sum < NORM) cls += 'under';
    else if (sum === NORM) cls += 'ok';
    else cls += 'over';
    sumEl.className = cls;
    sumEl.textContent = tasks.length ? 'Σ ' + fmtHours(sum) : '';
    sumEl.title = 'Занятость за день: сумма оценок';
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

  async function extend(dir) {
    if (state.loading[dir]) return;
    state.loading[dir] = true;
    try {
      const from = dir === 'right' ? D.addDays(state.end, 1) : D.addDays(state.start, -CHUNK);
      const to = dir === 'right' ? D.addDays(state.end, CHUNK) : D.addDays(state.start, -1);
      const tasks = await api('GET', `/api/tasks?from=${from}&to=${to}`);
      const days = D.range(from, to);
      // задачи кладём в состояние до создания колонок, чтобы ширина выходных была верной сразу
      ingestTasks(tasks);
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
    const from = { empId: t.employeeId, day: t.day };
    const saved = await api('PATCH', `/api/tasks/${id}/move`, { employeeId: empId, day });
    removeTask(id);
    ingestTasks([saved]);
    renderCell(from.empId, from.day);
    renderCell(saved.employeeId, saved.day);
    updateDayWidth(from.day);
    updateDayWidth(saved.day);
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
      openTaskDialog({ empId: Number(cell.dataset.emp), day: cell.dataset.day });
      return;
    }
    const task = e.target.closest('.task');
    if (task) {
      const t = state.tasks.get(Number(task.dataset.id));
      if (t) openTaskDialog({ empId: t.employeeId, day: t.day, task: t });
      return;
    }
    const empCell = e.target.closest('.emp-cell');
    if (empCell) {
      openEmployeeDialog(employeeById(Number(empCell.dataset.emp)));
    }
  });

  board.addEventListener('dblclick', (e) => {
    if (e.target.closest('.task') || e.target.closest('a') || e.target.closest('button')) return;
    const cell = e.target.closest('.day-cell');
    if (cell) openTaskDialog({ empId: Number(cell.dataset.emp), day: cell.dataset.day });
  });

  /* ---------- Диалог задачи ---------- */

  const taskDialog = document.getElementById('taskDialog');
  const taskForm = document.getElementById('taskForm');
  const taskTitle = document.getElementById('taskTitle');
  const taskRelease = document.getElementById('taskRelease');
  const taskEstimate = document.getElementById('taskEstimate');
  const taskDelete = document.getElementById('taskDelete');
  let taskCtx = null;

  function openTaskDialog(ctx) {
    taskCtx = ctx;
    const emp = employeeById(ctx.empId);
    document.getElementById('taskDialogTitle').textContent = ctx.task ? 'Задача' : 'Новая задача';
    document.getElementById('taskDialogSub').textContent = `${emp ? emp.name : ''} · ${D.long(ctx.day)}`;
    taskTitle.value = ctx.task ? ctx.task.title : '';
    taskRelease.value = ctx.task && ctx.task.release ? ctx.task.release : '';
    taskEstimate.value = ctx.task && ctx.task.estimate !== null && ctx.task.estimate !== undefined ? ctx.task.estimate : '';
    taskDelete.hidden = !ctx.task;
    refreshReleaseList();
    taskDialog.showModal();
    taskTitle.focus();
  }

  taskForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const title = taskTitle.value.trim();
    if (!title) { taskTitle.focus(); return; }
    const release = taskRelease.value.trim();
    const estimate = taskEstimate.value === '' ? null : Number(taskEstimate.value);
    const submit = document.getElementById('taskSubmit');
    submit.disabled = true;
    try {
      let saved;
      if (taskCtx.task) {
        saved = await api('PUT', `/api/tasks/${taskCtx.task.id}`, { title, release, estimate });
        removeTask(saved.id);
      } else {
        saved = await api('POST', '/api/tasks', { employeeId: taskCtx.empId, day: taskCtx.day, title, release, estimate });
      }
      ingestTasks([saved]);
      renderCell(saved.employeeId, saved.day);
      updateDayWidth(saved.day);
      taskDialog.close();
      toast(taskCtx.task ? 'Задача обновлена' : 'Задача добавлена', 'ok');
    } catch (_) { /* ошибка уже показана */ } finally {
      submit.disabled = false;
    }
  });

  // Ctrl+Enter в тексте задачи — сохранить
  taskTitle.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      taskForm.requestSubmit();
    }
  });

  taskDelete.addEventListener('click', async () => {
    const t = taskCtx.task;
    if (!t) return;
    const ok = await confirmDialog('Удалить задачу?', t.title.split('\n')[0]);
    if (!ok) return;
    await api('DELETE', `/api/tasks/${t.id}`);
    removeTask(t.id);
    renderCell(t.employeeId, t.day);
    updateDayWidth(t.day);
    taskDialog.close();
    toast('Задача удалена', 'ok');
  });

  function refreshReleaseList() {
    const list = document.getElementById('releaseList');
    list.replaceChildren(...[...state.releases].sort().map((r) => {
      const o = document.createElement('option');
      o.value = r;
      return o;
    }));
  }

  /* ---------- Диалог сотрудника ---------- */

  const PALETTE = ['#00e5ff', '#ff2fd6', '#ffd166', '#b388ff', '#39ff14', '#ff6a00', '#ff3864', '#7df9ff'];
  const employeeDialog = document.getElementById('employeeDialog');
  const employeeForm = document.getElementById('employeeForm');
  const employeeName = document.getElementById('employeeName');
  const employeeColor = document.getElementById('employeeColor');
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
    const body = { name, color: employeeColor.value };
    try {
      if (employeeCtx) {
        const saved = await api('PUT', `/api/employees/${employeeCtx.id}`, body);
        Object.assign(employeeCtx, saved);
        const row = els.rows.get(saved.id);
        row.replaceChild(makeEmpCell(saved), row.firstElementChild);
        renderRow(saved.id);
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
      count ? `Вместе с ним будут удалены все его задачи (в загруженном периоде: ${count}).` : 'Действие нельзя отменить.');
    if (!ok) return;
    await api('DELETE', `/api/employees/${emp.id}`);
    for (const t of [...state.tasks.values()]) if (t.employeeId === emp.id) removeTask(t.id);
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

    const [employees, settings, tasks] = await Promise.all([
      api('GET', '/api/employees'),
      api('GET', '/api/settings'),
      api('GET', `/api/tasks?from=${state.start}&to=${state.end}`)
    ]);
    state.employees = employees;
    state.jiraBase = settings.jiraBaseUrl || '';
    jiraInput.value = state.jiraBase;
    ingestTasks(tasks);
    buildBoard();
    refreshReleaseList();
    scrollToToday(false);
    updateMonthLabel();
  }

  init().catch((e) => console.error(e));
})();
