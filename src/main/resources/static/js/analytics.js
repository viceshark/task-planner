/* Страница аналитики: KPI, графики Chart.js и сводная таблица за выбранный период. */
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
    renderEmployees(data);
    renderUtil(data);
    renderReleases(data);
    renderWeekdays(data);
    renderTop(data);
    renderTable(data);
  }

  function renderKpis(data) {
    const t = data.totals;
    document.getElementById('kpiHours').textContent = fmtHours(t.hours);
    document.getElementById('kpiCapacity').textContent = `ёмкость команды ${fmtHours(t.capacity)}`;
    const util = document.getElementById('kpiUtil');
    util.textContent = t.utilization + '%';
    util.className = 'kpi-value ' + (t.utilization > 100 ? 'over' : t.utilization >= 80 ? 'ok' : 'under');
    document.getElementById('kpiTasks').textContent = t.tasks;
    document.getElementById('kpiTasksSub').textContent = `у ${t.employees} сотрудников`;
    document.getElementById('kpiReleases').textContent = t.releases;
    document.getElementById('kpiAvg').textContent = `в среднем ${fmtHours(t.avgPerWorkday)} на человека в день`;
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
    const norm = data.daily.length * 8;
    datasets.push({
      type: 'line',
      label: 'Норма команды',
      data: data.days.map((d) => (D.isWeekend(d) ? null : norm)),
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
            callbacks: {
              title: (items) => items.length ? D.long(data.days[items[0].dataIndex]) : '',
              label: (item) => item.raw === null ? null : ` ${item.dataset.label}: ${fmtHours(item.raw)}`
            }
          }
        }
      }
    });
  }

  function renderEmployees(data) {
    destroy('employees');
    const p = palette();
    const labels = data.employees.map((e) => e.name);
    charts.employees = new Chart(document.getElementById('chartEmployees'), {
      type: 'bar',
      data: {
        labels,
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
      options: {
        responsive: true,
        maintainAspectRatio: false,
        scales: { x: axis({ grid: { display: false } }), y: axis({ beginAtZero: true }) },
        plugins: { tooltip: { callbacks: { label: (i) => ` ${i.dataset.label}: ${fmtHours(i.raw)}` } } }
      }
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
          backgroundColor: data.employees.map((e) => (is90s() ? colorFor(e.utilization) : hexToRgba(colorFor(e.utilization), 0.5))),
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

  function renderReleases(data) {
    destroy('releases');
    const items = data.releases;
    charts.releases = new Chart(document.getElementById('chartReleases'), {
      type: 'doughnut',
      data: {
        labels: items.map((r) => r.release),
        datasets: [{
          data: items.map((r) => r.hours),
          backgroundColor: items.map((_, i) => (is90s() ? seriesColor(i) : hexToRgba(seriesColor(i), 0.6))),
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

  function renderWeekdays(data) {
    destroy('weekdays');
    charts.weekdays = new Chart(document.getElementById('chartWeekdays'), {
      type: 'radar',
      data: {
        labels: data.weekdays.map((w) => w.label),
        datasets: [{
          label: 'Средняя нагрузка команды, ч',
          data: data.weekdays.map((w) => w.avg),
          backgroundColor: palette().radarFill,
          borderColor: palette().radarLine,
          pointBackgroundColor: palette().radarPoint,
          pointBorderColor: palette().radarPoint,
          borderWidth: 2
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        scales: {
          r: {
            beginAtZero: true,
            grid: { color: palette().grid },
            angleLines: { color: palette().grid },
            pointLabels: { color: palette().text, font: palette().font },
            ticks: { color: palette().text, backdropColor: 'transparent', showLabelBackdrop: false }
          }
        },
        plugins: { tooltip: { callbacks: { label: (i) => ` ${fmtHours(i.raw)} в среднем, всего ${fmtHours(data.weekdays[i.dataIndex].hours)}` } } }
      }
    });
  }

  function renderTop(data) {
    destroy('top');
    const items = data.topTasks;
    charts.top = new Chart(document.getElementById('chartTop'), {
      type: 'bar',
      data: {
        labels: items.map((t) => (t.title.length > 28 ? t.title.slice(0, 27) + '…' : t.title)),
        datasets: [{
          label: 'Часы',
          data: items.map((t) => t.hours),
          backgroundColor: items.map((_, i) => (is90s() ? seriesColor(i + 1) : hexToRgba(seriesColor(i + 1), 0.5))),
          borderColor: items.map((_, i) => stroke(seriesColor(i + 1))),
          borderWidth: 1.5,
          borderRadius: palette().borderRadius
        }]
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        maintainAspectRatio: false,
        scales: { x: axis({ beginAtZero: true }), y: axis({ grid: { display: false } }) },
        plugins: {
          legend: { display: false },
          tooltip: { callbacks: { title: (i) => items[i[0].dataIndex].title, label: (i) => ` ${fmtHours(i.raw)} · записей: ${items[i.dataIndex].count}` } }
        }
      }
    });
    if (!items.length) emptyNote('chartTop', 'Нет задач за период');
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
    tbody.innerHTML = data.employees.map((e) => {
      const cls = e.utilization > 100 ? 'over' : e.utilization >= 80 ? 'ok' : 'under';
      return `<tr>
        <td><span class="dot" style="background:${escapeHtml(is90s() ? mixWithWhite(e.color, 0.45) : e.color)};box-shadow:0 0 8px ${escapeHtml(e.color)}"></span>${escapeHtml(e.name)}</td>
        <td class="num">${fmtHours(e.hours)}</td>
        <td class="num">${fmtHours(e.capacity)}</td>
        <td class="num"><span class="pill ${cls}">${e.utilization}%</span></td>
        <td class="num">${e.tasks}</td>
        <td class="num">${e.overloadedDays}</td>
        <td class="num">${e.idleWorkdays}</td>
        <td class="num">${fmtHours(e.maxDayHours)}</td>
      </tr>`;
    }).join('') || '<tr><td colspan="8" class="muted">Нет сотрудников</td></tr>';
  }

  const [from, to] = presetRange('month');
  fromInput.value = from;
  toInput.value = to;
  load().catch((e) => console.error(e));
})();
