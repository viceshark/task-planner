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
    List<ReleaseStat> releases,
    List<TaskStat> topTasks,
    List<WeekdayStat> weekdays) {

    public record Totals(int tasks, double hours, int employees, int releases, double capacity,
                         double utilization, double avgPerWorkday) {
    }

    public record EmployeeStat(Long id, String name, String color, double hours, int tasks, double capacity,
                               double utilization, int overloadedDays, int idleWorkdays, double maxDayHours) {
    }

    /** Ряд для графика по дням: значения соответствуют списку {@link AnalyticsDto#days()}. */
    public record Series(Long employeeId, String name, String color, List<Double> data) {
    }

    public record ReleaseStat(String release, double hours, int tasks) {
    }

    public record TaskStat(String title, double hours, int count) {
    }

    public record WeekdayStat(int weekday, String label, double hours, double avg) {
    }
}
