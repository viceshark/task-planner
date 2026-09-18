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
    List<WeekdayStat> weekdays) {

    public record Totals(int tasks, double hours, int employees, int releases, double capacity, double utilization,
                         double overtime, int overtimeEmployees, double downtimeHours, int downtimeDays,
                         int vacationDays, int rentalDays) {
    }

    public record EmployeeStat(Long id, String name, String color, double hours, int tasks, double capacity,
                               double utilization, int overloadedDays, int idleWorkdays, double maxDayHours,
                               double overtime, double downtimeHours, int vacationDays, int rentalDays) {
    }

    /** Ряд для графика по дням: значения соответствуют списку {@link AnalyticsDto#days()}. */
    public record Series(Long employeeId, String name, String color, List<Double> data) {
    }

    /**
     * Релиз: часы и задачи за период, а также дата готовности — следующий день после последнего дня
     * последней задачи релиза (по всем задачам, не только за период).
     */
    public record ReleaseStat(String release, double hours, int tasks, LocalDate lastTaskDay, LocalDate readyDay) {
    }

    public record WeekdayStat(int weekday, String label, double hours, double avg) {
    }
}
