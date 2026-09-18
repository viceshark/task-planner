package ru.planner.service;

import ru.planner.domain.Task;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Календарная арифметика для задач. Норма дня зависит от ставки сотрудника (8ч × ставка),
 * поэтому все расчёты принимают {@code norm} явно.
 */
public final class WorkDays {

    /** Норма полного рабочего дня при ставке 1. */
    public static final double DAY_NORM = 8.0;
    /** Максимальная растяжка задачи в рабочих днях. */
    public static final int MAX_SPAN = 60;
    /** Запас в календарных днях, чтобы захватить задачи, начавшиеся до периода и растянутые в него. */
    public static final int SPAN_LOOKBACK = 100;

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
     * Часы, которые задача реально занимает в календаре: досрочно завершённая — фактически
     * потраченные, иначе оценка плюс часы сверх оценки.
     */
    public static double effectiveHours(Task t) {
        if (t.isCompletedEarly() && t.getSpent() != null) {
            return t.getSpent();
        }
        return nz(t.getEstimate()) + nz(t.getOvertime());
    }

    /** Часы сверх оценки, которые учитываются (у досрочно завершённой задачи их нет). */
    public static double effectiveOvertime(Task t) {
        return t.isCompletedEarly() ? 0 : nz(t.getOvertime());
    }

    /** Сколько рабочих дней нужно задаче: по норме в день, остаток на последний (12ч при норме 8 → 2 дня). */
    public static int spanDays(double totalHours, double norm) {
        if (norm <= 0) {
            return 1;
        }
        return Math.max(1, Math.min(MAX_SPAN, (int) Math.ceil(totalHours / norm - 1e-9)));
    }

    public static int spanDays(Task t, double norm) {
        return spanDays(effectiveHours(t), norm);
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

    public static List<LocalDate> taskDays(Task t, double norm) {
        return taskDays(t.getDay(), spanDays(t, norm));
    }

    public static LocalDate lastDay(Task t, double norm) {
        List<LocalDate> days = taskDays(t, norm);
        return days.get(days.size() - 1);
    }

    /**
     * Часы задачи в её день с индексом {@code index}: каждый день заполняется по норме,
     * остаток попадает на последний день.
     */
    public static double hoursOn(Task t, int index, double norm) {
        double total = effectiveHours(t);
        int days = spanDays(total, norm);
        if (index < 0 || index >= days) {
            return 0;
        }
        if (index == days - 1) {
            return Math.max(0, total - norm * index);
        }
        return Math.max(0, Math.min(norm, total - norm * index));
    }

    /**
     * Часы сверх оценки в день с индексом {@code index}: сначала по дням «расходуется» оценка,
     * а перерасход ложится на последние дни задачи.
     */
    public static double overtimeOn(Task t, int index, double norm) {
        if (effectiveOvertime(t) == 0) {
            return 0;
        }
        double hours = hoursOn(t, index, norm);
        double estimateLeft = nz(t.getEstimate()) - norm * index;
        double estimatePart = Math.max(0, Math.min(hours, estimateLeft));
        return hours - estimatePart;
    }

    public static double nz(Double v) {
        return v == null ? 0 : v;
    }
}
