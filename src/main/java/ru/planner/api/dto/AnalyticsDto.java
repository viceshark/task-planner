package ru.planner.api.dto;

import java.time.LocalDate;
import java.util.List;

/** Агрегированные данные для страницы аналитики за выбранный период. */
public record AnalyticsDto(
    LocalDate from,
    LocalDate to,
    int workdays,
    Totals totals,
    List<EmployeeStat> employees,
    List<String> days,
    List<Series> daily,
    List<Double> dailyDowntime,
    List<Double> dailyCapacity,
    List<ReleaseStat> releases,
    List<GroupStat> epics,
    List<WeekdayStat> weekdays) {

    /**
     * overtime — часы сверх оценки (задачи, не уложившиеся в оценку); простой в рабочее время не входит;
     * earlyTasks / savedHours — задачи, завершённые досрочно, и сэкономленные против оценки часы.
     */
    public record Totals(int tasks, double hours, int employees, int releases, double capacity, double utilization,
                         double overtime, int overtimeTasks, int overtimeEmployees, double downtimeHours,
                         int downtimeDays, int vacationDays, int rentalDays, int earlyTasks, double savedHours) {
    }

    public record EmployeeStat(Long id, String name, String color, double rate, double hours, int tasks,
                               double capacity, double utilization, int overloadedDays, int idleWorkdays,
                               double maxDayHours, double overtime, int overtimeTasks, double downtimeHours,
                               int vacationDays, int rentalDays, int earlyTasks, double savedHours) {
    }

    /** Ряд для графика по дням: значения соответствуют списку {@link AnalyticsDto#days()}. */
    public record Series(Long employeeId, String name, String color, List<Double> data) {
    }

    /**
     * Релиз: часы и задачи за период, а также дата готовности — следующий рабочий день после последнего дня
     * последней задачи релиза (по всем задачам, не только за период).
     */
    public record ReleaseStat(String release, double hours, int tasks, LocalDate lastTaskDay, LocalDate readyDay) {
    }

    /** Группировка часов (по эпику). */
    public record GroupStat(String name, double hours, int tasks) {
    }

    public record WeekdayStat(int weekday, String label, double hours, double avg) {
    }
}
