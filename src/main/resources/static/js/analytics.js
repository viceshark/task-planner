/* Страница аналитики: KPI, графики Chart.js и сводные таблицы за выбранный период. */
(function () {
  'use strict';

  const { api, escapeHtml, fmtHours, D, theme } = window.App;

  const fromInput = document.getElementById('from');
  const toInput = document.getElementById('to');
  const rangeLabel = document.getElementById('rangeLabel');
  const charts = {};
  let lastData = null;

  /* Палитры графиков для каждой темы */
  const PALETTES = {
    retrowave: {
      series: ['#00e5ff', '#ff2fd6', '#ffd166', '#b388ff', '#39ff14', '#ff6a00', '#ff3864', '#7df9ff', '#f9f871', '#c77dff'],
      text: 'rgba(236, 233, 255, 0.85)',
      grid: 'rgba(255, 255, 255, 0.08)',
      border: 'rgba(255,255,255,0.15)',
      font: { family: "'Inter', 'Segoe UI', system-ui, sans-serif", size: 12 },
      tooltipBg: 'rgba(20, 10, 45, 0.92)',
      tooltipBorder: 'rgba(0, 229, 255, 0.4)',
      tooltipText: '#ece9ff',
      norm: '#ff3864',
      ok: '#39ff14', under: '#ffd166', over: '#ff3864',
      downtime: 'rgba(214, 217, 255, 0.35)', downtimeBorder: 'rgba(214, 217, 255, 0.8)',
      rental: '#ff6a00', vacation: '#7df9ff', overtime: '#ff3864',
      radarFill: 'rgba(0, 229, 255, 0.18)', radarLine: '#00e5ff', radarPoint: '#ff2fd6',
      capacityFill: 'rgba(255,255,255,0.05)', capacityBorder: 'rgba(255,255,255,0.35)',
      fillAlpha: 0.55, borderRadius: 6
    },
    '90s': {
      series: ['#a8d8e0', '#efb8cf', '#f0d79a', '#c9b6e4', '#b6dfb6', '#f5c9a3', '#e0a3a3', '#c7dff0', '#e9e2a8', '#d8bfe9'],
      text: '#000',
      grid: '#d0ccbf',
      border: '#000',
      font: { family: "Verdana, Geneva, 'DejaVu Sans', sans-serif", size: 10 },
      tooltipBg: '#fff',
      tooltipBorder: '#000',
      tooltipText: '#000',
      norm: '#000',
      ok: '#8fd08f', under: '#e8d27a', over: '#e08f8f',
      downtime: '#e3e0d4', downtimeBorder: '#000',
      rental: '#f5c9a3', vacation: '#c7dff0', overtime: '#e08f8f',
      radarFill: 'rgba(168, 216, 224, 0.5)', radarLine: '#000', radarPoint: '#7a0f6e',
      capacityFill: 'rgba(0,0,0,0.04)', capacityBorder: '#3a3a35',
      fillAlpha: 1, borderRadius: 0
    }
  };

  const is90s = () => theme.is90s();
  const palette = () => PALETTES[theme.get()] || PALETTES.retrowave;

  function hexToRgb(hex) {
    const h = hex.replace('#', '');
    const n = parseInt(h.length === 3 ? h.split('').map((c) => c + c).join('') : h, 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }

  function hexToRgba(hex, a) {
    const [r, g, b] = hexToRgb(hex);
    return `rgba(${r}, ${g}, ${b}, ${a})`;
  }

  /** Пастельный вариант цвета (как color-mix в CSS темы 90s): 45% цвета + белый. */
  function mixWithWhite(hex, ratio) {
    const [r, g, b] = hexToRgb(hex);
    const m = (c) => Math.round(c * ratio + 255 * (1 - ratio));
    return `rgb(${m(r)}, ${m(g)}, ${m(b)})`;
  }

  /** Заливка серии: полупрозрачный неон в retrowave, плотная пастель в 90s. */
  const fill = (hex, alpha) => (is90s() ? mixWithWhite(hex, 0.45) : hexToRgba(hex, alpha));
  /** Цвет обводки: в 90s всё обводится чёрным. */
  const stroke = (hex) => (is90s() ? '#000' : hex);
  const seriesColor = (i) => palette().series[i % palette().series.length];
  const solid = (hex, alpha) => (is90s() ? hex : hexToRgba(hex, alpha));

  function applyChartDefaults() {
    const p = palette();
    Chart.defaults.color = p.text;
    Chart.defaults.font = p.font;
    Chart.defaults.plugins.legend.labels.boxWidth = 10;
    Chart.defaults.plugins.legend.labels.boxHeight = 10;
    Chart.defaults.plugins.legend.labels.usePointStyle = !is90s();
    Chart.defaults.plugins.tooltip.backgroundColor = p.tooltipBg;
    Chart.defaults.plugins.tooltip.borderColor = p.tooltipBorder;
    Chart.defaults.plugins.tooltip.borderWidth = 1;
    Chart.defaults.plugins.tooltip.padding = 10;
    Chart.defaults.plugins.tooltip.titleColor = p.tooltipText;
    Chart.defaults.plugins.tooltip.bodyColor = p.tooltipText;
    Chart.defaults.plugins.tooltip.titleFont = { ...p.font, weight: '600' };
    Chart.defaults.plugins.tooltip.cornerRadius = is90s() ? 0 : 6;
  }
  applyChartDefaults();

  document.addEventListener('themechange', () => {
    applyChartDefaults();
    if (lastData) render(lastData);
  });

  function glowGradient(ctx, area, color) {
    if (is90s()) return mixWithWhite(color, 0.45);
    const g = ctx.createLinearGradient(0, area.top, 0, area.bottom);
    g.addColorStop(0, hexToRgba(color, 0.85));
    g.addColorStop(1, hexToRgba(color, 0.25));
    return g;
  }

  function destroy(name) {
    if (charts[name]) {
      charts[name].destroy();
      delete charts[name];
    }
  }

  const axis = (extra = {}) => ({
    grid: { color: palette().grid },
    border: { color: palette().border },
    ticks: { color: palette().text },
    ...extra
  });

  const barOpts = (extra = {}) => ({
    responsive: true,
    maintainAspectRatio: false,
    scales: { x: axis({ grid: { display: false } }), y: axis({ beginAtZero: true }) },
    ...extra
  });

  /* ---------- Пресеты периода ---------- */

  function presetRange(name) {
    const now = new Date();
    const y = now.getFullYear();
    const m = now.getMonth();
    switch (name) {
      case 'week': {
        const dow = now.getDay();
        const monday = new Date(y, m, now.getDate() + (dow === 0 ? -6 : 1 - dow));
        return [D.fmt(monday), D.addDays(D.fmt(monday), 6)];
      }
      case 'quarter': {
        const qm = Math.floor(m / 3) * 3;
        return [D.fmt(new Date(y, qm, 1)), D.fmt(new Date(y, qm + 3, 0))];
      }
      case '30':
        return [D.addDays(D.today(), -29), D.today()];
      case 'month':
      default:
        return [D.fmt(new Date(y, m, 1)), D.fmt(new Date(y, m + 1, 0))];
    }
  }

  document.getElementById('presets').addEventListener('click', (e) => {
    const btn = e.target.closest('.seg-btn');
    if (!btn) return;
    document.querySelectorAll('.seg-btn').forEach((b) => b.classList.toggle('active', b === btn));
    const [from, to] = presetRange(btn.dataset.preset);
    fromInput.value = from;
    toInput.value = to;
    load();
  });

  document.getElementById('btnApply').addEventListener('click', () => {
    document.querySelectorAll('.seg-btn').forEach((b) => b.classList.remove('active'));
    load();
  });

  /* ---------- Загрузка и отрисовка ---------- */

  async function load() {
    const from = fromInput.value;
    const to = toInput.value;
    if (!from || !to) return;
    if (to < from) {
      window.App.toast('Дата окончания раньше даты начала', 'error');
      return;
    }
    const data = await api('GET', `/api/analytics?from=${from}&to=${to}`);
    lastData = data;
    rangeLabel.textContent = `${D.long(data.from)} — ${D.long(data.to)} · рабочих дней: ${data.workdays}`;
    render(data);
  }

  function render(data) {
    renderKpis(data);
    renderDaily(data);
    renderReleaseTable(data);
    renderEmployees(data);
    renderUtil(data);
    renderOvertime(data);
    renderDowntime(data);
    renderAbsences(data);
    renderReleases(data);
    renderEpics(data);
    renderWeekdays(data);
    renderTable(data);
  }

  const plural = (n, one, few, many) => {
    const a = Math.abs(n) % 100;
    const b = a % 10;
    if (a > 10 && a < 20) return many;
    if (b > 1 && b < 5) return few;
    if (b === 1) return one;
    return many;
  };
  const daysWord = (n) => `${n} ${plural(n, 'день', 'дня', 'дней')}`;
  const utilClass = (u) => (u > 100 ? 'over' : u >= 80 ? 'ok' : 'under');

  function renderKpis(data) {
    const t = data.totals;
    document.getElementById('kpiHours').textContent = fmtHours(t.hours);
    document.getElementById('kpiCapacity').textContent = `ёмкость команды ${fmtHours(t.capacity)}`;
    const util = document.getElementById('kpiUtil');
    util.textContent = t.utilization + '%';
    util.className = 'kpi-value ' + utilClass(t.utilization);
    document.getElementById('kpiTasks').textContent = t.tasks;
    document.getElementById('kpiTasksSub').textContent = `у ${t.employees} сотрудников · релизов: ${t.releases}`;
    const ot = document.getElementById('kpiOvertime');
    ot.textContent = fmtHours(t.overtime);
    ot.className = 'kpi-value' + (t.overtime > 0 ? ' over' : '');
    document.getElementById('kpiOvertimeSub').textContent = t.overtime > 0
      ? `${t.overtimeTasks} ${plural(t.overtimeTasks, 'задача не уложилась', 'задачи не уложились', 'задач не уложились')} в оценку · у ${t.overtimeEmployees} ${plural(t.overtimeEmployees, 'сотрудника', 'сотрудников', 'сотрудников')}`
      : 'все задачи в рамках оценки';
    const dt = document.getElementById('kpiDowntime');
    dt.textContent = fmtHours(t.downtimeHours);
    dt.className = 'kpi-value' + (t.downtimeHours > 0 ? ' over' : '');
    document.getElementById('kpiDowntimeSub').textContent = t.downtimeDays ? `${daysWord(t.downtimeDays)} с простоем · не рабочее время` : 'простоев нет';
    document.getElementById('kpiAway').textContent = daysWord(t.vacationDays + t.rentalDays);
    document.getElementById('kpiAwaySub').textContent = `отпуск ${daysWord(t.vacationDays)} · аренда ${daysWord(t.rentalDays)}`;
    const early = document.getElementById('kpiEarly');
    early.textContent = `${t.earlyTasks} ${plural(t.earlyTasks, 'задача', 'задачи', 'задач')}`;
    early.className = 'kpi-value' + (t.earlyTasks > 0 ? ' ok' : '');
    document.getElementById('kpiEarlySub').textContent = t.earlyTasks > 0
      ? `завершены досрочно · сэкономлено ${fmtHours(t.savedHours)} против оценки` : 'досрочно завершённых нет';
  }

  function renderDaily(data) {
    destroy('daily');
    const labels = data.days.map((d) => D.short(d));
    const p = palette();
    const datasets = data.daily.map((s, i) => {
      const color = s.color || seriesColor(i);
      return {
        label: s.name,
        data: s.data,
        backgroundColor: (c) => c.chart.chartArea ? glowGradient(c.chart.ctx, c.chart.chartArea, color) : color,
        borderColor: stroke(color),
        borderWidth: 1,
        borderRadius: is90s() ? 0 : 3,
        stack: 'hours'
      };
    });
    datasets.push({
      type: 'line',
      label: 'Ёмкость команды',
      data: data.dailyCapacity.map((v, i) => (D.isWeekend(data.days[i]) ? null : v)),
      borderColor: p.norm,
      borderDash: [6, 4],
      borderWidth: 1.5,
      pointRadius: 0,
      spanGaps: false,
      order: -1
    });
    charts.daily = new Chart(document.getElementById('chartDaily'), {
      type: 'bar',
      data: { labels, datasets },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        scales: {
          x: axis({ stacked: true, grid: { display: false }, ticks: { color: p.text, maxRotation: 0, autoSkip: true, maxTicksLimit: 31 } }),
          y: axis({ stacked: true, beginAtZero: true, title: { display: true, text: 'часы', color: p.text } })
        },
        plugins: {
          tooltip: {
            filter: (item) => item.raw !== null && item.raw !== 0,
            callbacks: {
              title: (items) => items.length ? D.long(data.days[items[0].dataIndex]) : '',
              label: (item) => ` ${item.dataset.label}: ${fmtHours(item.raw)}`
            }
          }
        }
      }
    });
  }

  function renderReleaseTable(data) {
    const tbody = document.querySelector('#releaseTable tbody');
    const todayIso = D.today();
    tbody.innerHTML = data.releases.map((r) => {
      const ready = r.readyDay ? D.long(r.readyDay) : '—';
      const cls = r.readyDay && r.readyDay < todayIso ? 'ok' : (r.readyDay ? 'under' : '');
      return `<tr>
        <td><b>${escapeHtml(r.release)}</b></td>
        <td class="num">${r.tasks}</td>
        <td class="num">${fmtHours(r.hours)}</td>
        <td>${r.lastTaskDay ? D.long(r.lastTaskDay) : '—'}</td>
        <td>${r.readyDay ? `<span class="pill ${cls}">${ready}</span>` : '—'}</td>
      </tr>`;
    }).join('') || '<tr><td colspan="5" class="muted">Нет задач за период</td></tr>';
  }

  function renderEmployees(data) {
    destroy('employees');
    const p = palette();
    charts.employees = new Chart(document.getElementById('chartEmployees'), {
      type: 'bar',
      data: {
        labels: data.employees.map((e) => e.name),
        datasets: [
          {
            label: 'Часы',
            data: data.employees.map((e) => e.hours),
            backgroundColor: data.employees.map((e) => fill(e.color, p.fillAlpha)),
            borderColor: data.employees.map((e) => stroke(e.color)),
            borderWidth: 1.5,
            borderRadius: p.borderRadius
          },
          {
            label: 'Ёмкость',
            data: data.employees.map((e) => e.capacity),
            backgroundColor: p.capacityFill,
            borderColor: p.capacityBorder,
            borderWidth: 1,
            borderDash: [4, 3],
            borderRadius: p.borderRadius
          }
        ]
      },
      options: barOpts({ plugins: { tooltip: { callbacks: { label: (i) => ` ${i.dataset.label}: ${fmtHours(i.raw)}` } } } })
    });
  }

  function renderUtil(data) {
    destroy('util');
    const p = palette();
    const colorFor = (u) => (u > 100 ? p.over : u >= 80 ? p.ok : p.under);
    charts.util = new Chart(document.getElementById('chartUtil'), {
      type: 'bar',
      data: {
        labels: data.employees.map((e) => e.name),
        datasets: [{
          label: 'Загрузка, %',
          data: data.employees.map((e) => e.utilization),
          backgroundColor: data.employees.map((e) => solid(colorFor(e.utilization), 0.5)),
          borderColor: data.employees.map((e) => stroke(colorFor(e.utilization))),
          borderWidth: 1.5,
          borderRadius: p.borderRadius
        }]
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        maintainAspectRatio: false,
        scales: {
          x: axis({ beginAtZero: true, suggestedMax: 120, ticks: { color: p.text, callback: (v) => v + '%' } }),
          y: axis({ grid: { display: false } })
        },
        plugins: {
          legend: { display: false },
          tooltip: { callbacks: { label: (i) => ` ${i.raw}% от ${fmtHours(data.employees[i.dataIndex].capacity)}` } }
        }
      }
    });
  }

  function renderOvertime(data) {
    destroy('overtime');
    const p = palette();
    charts.overtime = new Chart(document.getElementById('chartOvertime'), {
      type: 'bar',
      data: {
        labels: data.employees.map((e) => e.name),
        datasets: [{
          label: 'Сверх оценки, ч',
          data: data.employees.map((e) => e.overtime),
          backgroundColor: solid(p.overtime, 0.5),
          borderColor: stroke(p.overtime),
          borderWidth: 1.5,
          borderRadius: p.borderRadius
        }]
      },
      options: barOpts({
        plugins: { legend: { display: false }, tooltip: { callbacks: { label: (i) => ` ${fmtHours(i.raw)} · задач сверх оценки: ${data.employees[i.dataIndex].overtimeTasks}` } } }
      })
    });
    if (!data.employees.some((e) => e.overtime > 0)) emptyNote('chartOvertime', 'Все задачи в рамках оценки');
  }

  function renderDowntime(data) {
    destroy('downtime');
    const p = palette();
    charts.downtime = new Chart(document.getElementById('chartDowntime'), {
      type: 'bar',
      data: {
        labels: data.employees.map((e) => e.name),
        datasets: [{
          label: 'Простой, ч',
          data: data.employees.map((e) => e.downtimeHours),
          backgroundColor: p.downtime,
          borderColor: p.downtimeBorder,
          borderWidth: 1.5,
          borderDash: [3, 2],
          borderRadius: p.borderRadius
        }]
      },
      options: barOpts({
        plugins: { legend: { display: false }, tooltip: { callbacks: { label: (i) => ` ${fmtHours(i.raw)}` } } }
      })
    });
    if (!data.employees.some((e) => e.downtimeHours > 0)) emptyNote('chartDowntime', 'Простоев за период нет');
  }

  function renderAbsences(data) {
    destroy('absences');
    const p = palette();
    const mk = (label, key, color, border) => ({
      label,
      data: data.employees.map((e) => e[key]),
      backgroundColor: color,
      borderColor: border,
      borderWidth: 1,
      borderRadius: 0,
      stack: 'abs'
    });
    charts.absences = new Chart(document.getElementById('chartAbsences'), {
      type: 'bar',
      data: {
        labels: data.employees.map((e) => e.name),
        datasets: [
          mk('Отпуск', 'vacationDays', solid(p.vacation, 0.5), stroke(p.vacation)),
          mk('Аренда', 'rentalDays', solid(p.rental, 0.5), stroke(p.rental))
        ]
      },
      options: barOpts({
        scales: { x: axis({ stacked: true, grid: { display: false } }), y: axis({ stacked: true, beginAtZero: true, ticks: { color: p.text, precision: 0 } }) },
        plugins: { tooltip: { filter: (i) => i.raw !== 0, callbacks: { label: (i) => ` ${i.dataset.label}: ${daysWord(i.raw)}` } } }
      })
    });
    if (!data.employees.some((e) => e.vacationDays > 0 || e.rentalDays > 0)) {
      emptyNote('chartAbsences', 'Отпусков и аренды за период нет');
    }
  }

  function renderReleases(data) {
    destroy('releases');
    const items = data.releases;
    charts.releases = new Chart(document.getElementById('chartReleases'), {
      type: 'doughnut',
      data: {
        labels: items.map((r) => r.release),
        datasets: [{
          data: items.map((r) => r.hours),
          backgroundColor: items.map((_, i) => solid(seriesColor(i), 0.6)),
          borderColor: items.map((_, i) => stroke(seriesColor(i))),
          borderWidth: 1.5,
          hoverOffset: 8
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '62%',
        plugins: {
          legend: { position: 'right' },
          tooltip: { callbacks: { label: (i) => ` ${fmtHours(i.raw)} · задач: ${items[i.dataIndex].tasks}` } }
        }
      }
    });
    if (!items.length) emptyNote('chartReleases', 'Нет задач за период');
  }

  function renderEpics(data) {
    destroy('epics');
    const items = data.epics;
    charts.epics = new Chart(document.getElementById('chartEpics'), {
      type: 'doughnut',
      data: {
        labels: items.map((r) => r.name),
        datasets: [{
          data: items.map((r) => r.hours),
          backgroundColor: items.map((_, i) => solid(seriesColor(i + 3), 0.6)),
          borderColor: items.map((_, i) => stroke(seriesColor(i + 3))),
          borderWidth: 1.5,
          hoverOffset: 8
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '62%',
        plugins: {
          legend: { position: 'right' },
          tooltip: { callbacks: { label: (i) => ` ${fmtHours(i.raw)} · задач: ${items[i.dataIndex].tasks}` } }
        }
      }
    });
    if (!items.length) emptyNote('chartEpics', 'Нет задач за период');
  }

  function renderWeekdays(data) {
    destroy('weekdays');
    const p = palette();
    charts.weekdays = new Chart(document.getElementById('chartWeekdays'), {
      type: 'radar',
      data: {
        labels: data.weekdays.map((w) => w.label),
        datasets: [{
          label: 'Средняя нагрузка команды, ч',
          data: data.weekdays.map((w) => w.avg),
          backgroundColor: p.radarFill,
          borderColor: p.radarLine,
          pointBackgroundColor: p.radarPoint,
          pointBorderColor: p.radarPoint,
          borderWidth: 2
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        scales: {
          r: {
            beginAtZero: true,
            grid: { color: p.grid },
            angleLines: { color: p.grid },
            pointLabels: { color: p.text, font: p.font },
            ticks: { color: p.text, backdropColor: 'transparent', showLabelBackdrop: false }
          }
        },
        plugins: { tooltip: { callbacks: { label: (i) => ` ${fmtHours(i.raw)} в среднем, всего ${fmtHours(data.weekdays[i.dataIndex].hours)}` } } }
      }
    });
  }

  function emptyNote(canvasId, text) {
    const box = document.getElementById(canvasId).parentElement;
    let note = box.querySelector('.empty-note');
    if (!note) {
      note = document.createElement('div');
      note.className = 'empty-note';
      box.appendChild(note);
    }
    note.textContent = text;
    setTimeout(() => note.remove(), 4000);
  }

  function renderTable(data) {
    const tbody = document.querySelector('#employeeTable tbody');
    tbody.innerHTML = data.employees.map((e) => `<tr>
        <td><span class="dot" style="background:${escapeHtml(is90s() ? mixWithWhite(e.color, 0.45) : e.color)};box-shadow:0 0 8px ${escapeHtml(e.color)}"></span>${escapeHtml(e.name)}</td>
        <td class="num">${e.rate === 1 ? '1' : e.rate}</td>
        <td class="num">${fmtHours(e.hours)}</td>
        <td class="num">${fmtHours(e.capacity)}</td>
        <td class="num"><span class="pill ${utilClass(e.utilization)}">${e.utilization}%</span></td>
        <td class="num">${e.tasks}</td>
        <td class="num">${e.overtime ? `<span class="pill over" title="задач сверх оценки: ${e.overtimeTasks}">${fmtHours(e.overtime)}</span>` : '—'}</td>
        <td class="num">${e.downtimeHours ? `<span class="pill over">${fmtHours(e.downtimeHours)}</span>` : '—'}</td>
        <td class="num">${e.vacationDays ? daysWord(e.vacationDays) : '—'}</td>
        <td class="num">${e.rentalDays ? daysWord(e.rentalDays) : '—'}</td>
        <td class="num">${e.earlyTasks ? `<span class="pill ok" title="сэкономлено против оценки">${e.earlyTasks} · −${fmtHours(e.savedHours)}</span>` : '—'}</td>
        <td class="num">${e.overloadedDays}</td>
        <td class="num">${e.idleWorkdays}</td>
        <td class="num">${fmtHours(e.maxDayHours)}</td>
      </tr>`).join('') || '<tr><td colspan="14" class="muted">Нет сотрудников</td></tr>';
  }

  const [from, to] = presetRange('month');
  fromInput.value = from;
  toInput.value = to;
  load().catch((e) => console.error(e));
})();
