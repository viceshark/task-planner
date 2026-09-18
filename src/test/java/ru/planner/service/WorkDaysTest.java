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
    void singleDayTaskOccupiesOnlyItsDayEvenOnWeekend() {
        assertThat(WorkDays.taskDays(LocalDate.of(2026, 9, 19), 1)).containsExactly(LocalDate.of(2026, 9, 19));
        assertThat(WorkDays.taskDays(LocalDate.of(2026, 9, 19), 0)).hasSize(1);
    }

    @Test
    void hoursAreSplitEvenlyAcrossDaysIncludingOvertime() {
        Task t = new Task();
        t.setDay(LocalDate.of(2026, 9, 14));
        t.setEstimate(20.0);
        t.setOvertime(4.0);
        t.setDays(3);
        assertThat(WorkDays.totalHours(t)).isEqualTo(24.0);
        assertThat(WorkDays.hoursPerDay(t)).isEqualTo(8.0);
        assertThat(WorkDays.overtimePerDay(t)).isCloseTo(1.333, org.assertj.core.data.Offset.offset(0.001));
        assertThat(WorkDays.lastDay(t)).isEqualTo(LocalDate.of(2026, 9, 16));
        List<LocalDate> days = WorkDays.taskDays(t);
        assertThat(days).hasSize(3);
    }
}
