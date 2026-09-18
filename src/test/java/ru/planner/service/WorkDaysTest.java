package ru.planner.service;

import org.junit.jupiter.api.Test;
import ru.planner.domain.Task;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkDaysTest {

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
    void singleDayTaskOccupiesOnlyItsDayEvenOnWeekend() {
        assertThat(WorkDays.taskDays(LocalDate.of(2026, 9, 19), 1)).containsExactly(LocalDate.of(2026, 9, 19));
        assertThat(WorkDays.taskDays(LocalDate.of(2026, 9, 19), 0)).hasSize(1);
    }

    @Test
    void hoursFillEightPerDayWithRemainderOnLastDay() {
        Task t = new Task();
        t.setDay(LocalDate.of(2026, 9, 14));
        t.setEstimate(12.0);
        t.setDays(WorkDays.spanDays(12));
        assertThat(t.getDays()).isEqualTo(2);
        assertThat(WorkDays.hoursOn(t, 0)).isEqualTo(8.0);
        assertThat(WorkDays.hoursOn(t, 1)).isEqualTo(4.0);
        assertThat(WorkDays.hoursOn(t, 2)).isEqualTo(0.0);
        assertThat(WorkDays.lastDay(t)).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    void spanDaysByNorm() {
        assertThat(WorkDays.spanDays(8)).isEqualTo(1);
        assertThat(WorkDays.spanDays(8.5)).isEqualTo(2);
        assertThat(WorkDays.spanDays(16)).isEqualTo(2);
        assertThat(WorkDays.spanDays(24)).isEqualTo(3);
        assertThat(WorkDays.spanDays(1000)).isEqualTo(WorkDays.MAX_SPAN);
    }

    @Test
    void overtimeIsWhatExceedsEstimateAndLandsOnLastDays() {
        // оценка 10ч, реально ушло 20ч (10 сверх): дни 8 / 8 / 4 — оценка кончается на втором дне
        Task t = new Task();
        t.setDay(LocalDate.of(2026, 9, 14));
        t.setEstimate(10.0);
        t.setOvertime(10.0);
        t.setDays(WorkDays.spanDays(20));
        assertThat(t.getDays()).isEqualTo(3);
        assertThat(WorkDays.overtimeOn(t, 0)).isEqualTo(0.0);
        assertThat(WorkDays.overtimeOn(t, 1)).isEqualTo(6.0);
        assertThat(WorkDays.overtimeOn(t, 2)).isEqualTo(4.0);
        assertThat(WorkDays.totalHours(t)).isEqualTo(20.0);

        // без растяжки всё в одном дне: 12ч, из них 4 сверх оценки
        Task single = new Task();
        single.setDay(LocalDate.of(2026, 9, 14));
        single.setEstimate(8.0);
        single.setOvertime(4.0);
        single.setDays(1);
        assertThat(WorkDays.hoursOn(single, 0)).isEqualTo(12.0);
        assertThat(WorkDays.overtimeOn(single, 0)).isEqualTo(4.0);
    }
}
