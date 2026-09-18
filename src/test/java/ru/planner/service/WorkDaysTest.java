package ru.planner.service;

import org.junit.jupiter.api.Test;
import ru.planner.domain.Task;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class WorkDaysTest {

    private static final double FULL = 8.0;

    private static Task task(double estimate, Double overtime) {
        Task t = new Task();
        t.setDay(LocalDate.of(2026, 9, 14));
        t.setEstimate(estimate);
        t.setOvertime(overtime);
        return t;
    }

    @Test
    void spanSkipsWeekends() {
        // четверг 17.09.2026, 4 рабочих дня: Чт, Пт, Пн, Вт
        assertThat(WorkDays.taskDays(LocalDate.of(2026, 9, 17), 4)).containsExactly(
            LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 22));
    }

    @Test
    void nextWorkdaySkipsWeekend() {
        assertThat(WorkDays.nextWorkday(LocalDate.of(2026, 9, 18))).isEqualTo(LocalDate.of(2026, 9, 21)); // Пт → Пн
        assertThat(WorkDays.nextWorkday(LocalDate.of(2026, 9, 19))).isEqualTo(LocalDate.of(2026, 9, 21)); // Сб → Пн
        assertThat(WorkDays.nextWorkday(LocalDate.of(2026, 9, 21))).isEqualTo(LocalDate.of(2026, 9, 22)); // Пн → Вт
    }

    @Test
    void hoursFillNormPerDayWithRemainderOnLastDay() {
        Task t = task(12, null);
        assertThat(WorkDays.spanDays(t, FULL)).isEqualTo(2);
        assertThat(WorkDays.hoursOn(t, 0, FULL)).isEqualTo(8.0);
        assertThat(WorkDays.hoursOn(t, 1, FULL)).isEqualTo(4.0);
        assertThat(WorkDays.hoursOn(t, 2, FULL)).isEqualTo(0.0);
        assertThat(WorkDays.lastDay(t, FULL)).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    void halfRateEmployeeHasFourHourDays() {
        Task t = task(12, null);
        double half = 4.0;
        assertThat(WorkDays.spanDays(t, half)).isEqualTo(3);
        assertThat(WorkDays.hoursOn(t, 0, half)).isEqualTo(4.0);
        assertThat(WorkDays.hoursOn(t, 2, half)).isEqualTo(4.0);
        // 6ч при ставке 0.5 — два дня: 4 + 2
        Task six = task(6, null);
        assertThat(WorkDays.spanDays(six, half)).isEqualTo(2);
        assertThat(WorkDays.hoursOn(six, 1, half)).isEqualTo(2.0);
        // при полной ставке та же задача помещается в один день
        assertThat(WorkDays.spanDays(six, FULL)).isEqualTo(1);
    }

    @Test
    void spanDaysByNorm() {
        assertThat(WorkDays.spanDays(8, FULL)).isEqualTo(1);
        assertThat(WorkDays.spanDays(8.5, FULL)).isEqualTo(2);
        assertThat(WorkDays.spanDays(24, FULL)).isEqualTo(3);
        assertThat(WorkDays.spanDays(10000, FULL)).isEqualTo(WorkDays.MAX_SPAN);
        assertThat(WorkDays.spanDays(0, FULL)).isEqualTo(1);
    }

    @Test
    void overtimeIsWhatExceedsEstimateAndLandsOnLastDays() {
        // оценка 10ч, реально ушло 20ч (10 сверх): дни 8 / 8 / 4 — оценка кончается на втором дне
        Task t = task(10, 10.0);
        assertThat(WorkDays.spanDays(t, FULL)).isEqualTo(3);
        assertThat(WorkDays.overtimeOn(t, 0, FULL)).isEqualTo(0.0);
        assertThat(WorkDays.overtimeOn(t, 1, FULL)).isEqualTo(6.0);
        assertThat(WorkDays.overtimeOn(t, 2, FULL)).isEqualTo(4.0);
        assertThat(WorkDays.effectiveHours(t)).isEqualTo(20.0);
    }

    @Test
    void completedEarlyTaskOccupiesSpentHoursOnly() {
        Task t = task(20, 4.0);
        t.setCompletedEarly(true);
        t.setSpent(6.0);
        assertThat(WorkDays.effectiveHours(t)).isEqualTo(6.0);
        assertThat(WorkDays.effectiveOvertime(t)).isEqualTo(0.0);
        assertThat(WorkDays.spanDays(t, FULL)).isEqualTo(1);
        assertThat(WorkDays.hoursOn(t, 0, FULL)).isEqualTo(6.0);
        assertThat(WorkDays.overtimeOn(t, 0, FULL)).isEqualTo(0.0);
    }
}
