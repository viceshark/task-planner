package ru.planner.service;

import ru.planner.domain.Task;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Календарная арифметика для задач, растянутых на несколько рабочих дней. */
public final class WorkDays {

    /** Норма рабочего дня в часах — та же «восьмёрка», что подсвечивалась в исходном планировщике. */
    public static final double DAY_NORM = 8.0;
    /** Максимальная растяжка задачи в рабочих днях. */
    public static final int MAX_SPAN = 30;
    /** Запас в календарных днях, чтобы захватить задачи, начавшиеся до периода и растянутые в него. */
    public static final int SPAN_LOOKBACK = 60;

    private WorkDays() {
    }

    public static boolean isWorkday(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /** Следующий рабочий день после указанного. */
    public static LocalDate nextWorkday(LocalDate d) {
        LocalDate cur = d.plusDays(1);
        while (!isWorkday(cur)) {
            cur = cur.plusDays(1);
        }
        return cur;
    }

    /**
     * Дни, которые занимает задача: первый — день начала (даже если это выходной),
     * дальше — следующие рабочие дни, всего {@code days} штук.
     */
    public static List<LocalDate> taskDays(LocalDate start, int days) {
        List<LocalDate> out = new ArrayList<>();
        out.add(start);
        LocalDate cur = start;
        while (out.size() < Math.max(1, days)) {
            cur = cur.plusDays(1);
            if (isWorkday(cur)) {
                out.add(cur);
            }
        }
        return out;
    }

    public static List<LocalDate> taskDays(Task t) {
        return taskDays(t.getDay(), t.getDays());
    }

    public static LocalDate lastDay(Task t) {
        List<LocalDate> days = taskDays(t);
        return days.get(days.size() - 1);
    }

    /** Полные часы задачи: оценка плюс часы сверх оценки. */
    public static double totalHours(Task t) {
        return nz(t.getEstimate()) + nz(t.getOvertime());
    }

    /** Сколько рабочих дней нужно задаче при растяжке по норме: 12ч → 2 дня (8 + 4). */
    public static int spanDays(double totalHours) {
        return Math.max(1, Math.min(MAX_SPAN, (int) Math.ceil(totalHours / DAY_NORM - 1e-9)));
    }

    /**
     * Часы задачи в её день с индексом {@code index}: каждый день заполняется по норме (8ч),
     * остаток попадает на последний день. Задача в один день несёт все свои часы.
     */
    public static double hoursOn(Task t, int index) {
        double total = totalHours(t);
        int days = Math.max(1, t.getDays());
        if (index < 0 || index >= days) {
            return 0;
        }
        if (index == days - 1) {
            return Math.max(0, total - DAY_NORM * index);
        }
        return Math.max(0, Math.min(DAY_NORM, total - DAY_NORM * index));
    }

    /**
     * Часы сверх оценки в день с индексом {@code index}: сначала по дням «расходуется» оценка,
     * а перерасход ложится на последние дни задачи.
     */
    public static double overtimeOn(Task t, int index) {
        double hours = hoursOn(t, index);
        double estimateLeft = nz(t.getEstimate()) - DAY_NORM * index;
        double estimatePart = Math.max(0, Math.min(hours, estimateLeft));
        return hours - estimatePart;
    }

    public static double nz(Double v) {
        return v == null ? 0 : v;
    }
}
