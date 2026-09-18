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

    /** Полные часы задачи: оценка плюс овертайм. */
    public static double totalHours(Task t) {
        return nz(t.getEstimate()) + nz(t.getOvertime());
    }

    /** Часы задачи, приходящиеся на один её день (равномерное распределение). */
    public static double hoursPerDay(Task t) {
        return totalHours(t) / Math.max(1, t.getDays());
    }

    /** Овертайм задачи, приходящийся на один её день. */
    public static double overtimePerDay(Task t) {
        return nz(t.getOvertime()) / Math.max(1, t.getDays());
    }

    public static double nz(Double v) {
        return v == null ? 0 : v;
    }
}
