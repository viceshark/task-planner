package ru.planner.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.planner.api.dto.AnalyticsDto;
import ru.planner.domain.Absence;
import ru.planner.domain.Employee;
import ru.planner.domain.Task;
import ru.planner.repo.AbsenceRepository;
import ru.planner.repo.EmployeeRepository;
import ru.planner.repo.TaskRepository;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    public static final double DAY_NORM = WorkDays.DAY_NORM;
    private static final int MAX_RANGE_DAYS = 400;
    private static final String NO_RELEASE = "Без релиза";
    private static final String[] WEEKDAY_LABELS = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
    private static final Pattern JIRA_KEY = Pattern.compile("(?<![\\p{L}\\d-])(\\p{Lu}[\\p{Lu}\\d]{1,14}-\\d+)(?![\\p{L}\\d])");

    private final TaskRepository tasks;
    private final AbsenceRepository absences;
    private final EmployeeRepository employees;
    private final TaskService taskService;

    /** Накопители по одному сотруднику за период. */
    private static final class Acc {
        final Map<LocalDate, Double> hours = new HashMap<>();
        final Map<LocalDate, Double> downtime = new HashMap<>();
        final Set<LocalDate> vacation = new HashSet<>();
        final Set<LocalDate> rental = new HashSet<>();
        double overtime;
        int tasks;
    }

    @Transactional(readOnly = true)
    public AnalyticsDto build(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Дата окончания раньше даты начала");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("Период не может быть длиннее " + MAX_RANGE_DAYS + " дней");
        }

        List<Employee> employeeList = employees.findAllByOrderByPositionAscIdAsc();
        List<Task> taskList = taskService.findIntersecting(from, to);
        List<Absence> absenceList = absences.findOverlapping(from, to);

        List<LocalDate> days = new ArrayList<>();
        int workdays = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.add(d);
            if (WorkDays.isWorkday(d)) {
                workdays++;
            }
        }

        Map<Long, Acc> byEmployee = new HashMap<>();
        for (Employee e : employeeList) {
            byEmployee.put(e.getId(), new Acc());
        }
        Map<String, double[]> byRelease = new LinkedHashMap<>();   // [hours, count]
        double[] byWeekday = new double[7];
        double totalHours = 0;

        // Задачи: часы распределяем равномерно по дням растяжки, в период попадают только дни внутри [from, to]
        for (Task t : taskList) {
            Acc acc = byEmployee.get(t.getEmployeeId());
            double perDay = WorkDays.hoursPerDay(t);
            double overtimePerDay = WorkDays.overtimePerDay(t);
            boolean counted = false;
            for (LocalDate d : WorkDays.taskDays(t)) {
                if (d.isBefore(from) || d.isAfter(to)) {
                    continue;
                }
                counted = true;
                totalHours += perDay;
                byWeekday[d.getDayOfWeek().getValue() - 1] += perDay;
                if (acc != null) {
                    acc.hours.merge(d, perDay, Double::sum);
                    acc.overtime += overtimePerDay;
                }
            }
            if (counted) {
                if (acc != null) {
                    acc.tasks++;
                }
                String release = t.getRelease() == null || t.getRelease().isBlank() ? NO_RELEASE : t.getRelease();
                double[] r = byRelease.computeIfAbsent(release, k -> new double[2]);
                r[0] += WorkDays.totalHours(t);
                r[1] += 1;
            }
        }

        // События: простой — часы, отпуск и аренда — целые рабочие дни
        for (Absence a : absenceList) {
            Acc acc = byEmployee.get(a.getEmployeeId());
            if (acc == null) {
                continue;
            }
            LocalDate start = a.getStartDay().isBefore(from) ? from : a.getStartDay();
            LocalDate end = a.getEndDay().isAfter(to) ? to : a.getEndDay();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                if (!WorkDays.isWorkday(d)) {
                    continue;
                }
                switch (a.getType()) {
                    case DOWNTIME -> acc.downtime.merge(d, a.getHoursPerDay() == null ? DAY_NORM : a.getHoursPerDay(), Double::sum);
                    case VACATION -> acc.vacation.add(d);
                    case RENTAL -> acc.rental.add(d);
                }
            }
        }

        List<AnalyticsDto.EmployeeStat> employeeStats = new ArrayList<>();
        List<AnalyticsDto.Series> series = new ArrayList<>();
        double[] dailyDowntime = new double[days.size()];
        double[] dailyCapacity = new double[days.size()];
        double totalCapacity = 0;
        double totalOvertime = 0;
        double totalDowntime = 0;
        int overtimeEmployees = 0;
        int downtimeDays = 0;
        int vacationDays = 0;
        int rentalDays = 0;
        int totalTasks = 0;

        for (Employee e : employeeList) {
            Acc acc = byEmployee.get(e.getId());
            double hours = 0;
            double maxDay = 0;
            double capacity = 0;
            double downtime = 0;
            int overloaded = 0;
            int idle = 0;
            List<Double> data = new ArrayList<>(days.size());
            for (int i = 0; i < days.size(); i++) {
                LocalDate d = days.get(i);
                double h = acc.hours.getOrDefault(d, 0.0);
                double dt = acc.downtime.getOrDefault(d, 0.0);
                boolean away = acc.vacation.contains(d) || acc.rental.contains(d);
                data.add(round(h));
                hours += h;
                downtime += dt;
                maxDay = Math.max(maxDay, h);
                dailyDowntime[i] += dt;
                if (WorkDays.isWorkday(d) && !away) {
                    capacity += DAY_NORM;
                    dailyCapacity[i] += DAY_NORM;
                    if (h == 0 && dt == 0) {
                        idle++;
                    }
                }
                if (h > DAY_NORM) {
                    overloaded++;
                }
            }
            double utilization = capacity == 0 ? 0 : hours / capacity * 100;
            employeeStats.add(new AnalyticsDto.EmployeeStat(e.getId(), e.getName(), e.getColor(), round(hours),
                acc.tasks, capacity, round(utilization), overloaded, idle, round(maxDay),
                round(acc.overtime), round(downtime), acc.vacation.size(), acc.rental.size()));
            series.add(new AnalyticsDto.Series(e.getId(), e.getName(), e.getColor(), data));

            totalCapacity += capacity;
            totalOvertime += acc.overtime;
            totalDowntime += downtime;
            if (acc.overtime > 0) {
                overtimeEmployees++;
            }
            downtimeDays += acc.downtime.size();
            vacationDays += acc.vacation.size();
            rentalDays += acc.rental.size();
            totalTasks += acc.tasks;
        }

        Map<String, LocalDate> readiness = releaseLastDays();
        List<AnalyticsDto.ReleaseStat> releases = byRelease.entrySet().stream()
            .map(en -> {
                LocalDate last = readiness.get(en.getKey());
                return new AnalyticsDto.ReleaseStat(en.getKey(), round(en.getValue()[0]), (int) en.getValue()[1],
                    last, last == null ? null : last.plusDays(1));
            })
            .sorted(Comparator.comparingDouble(AnalyticsDto.ReleaseStat::hours).reversed())
            .toList();

        List<AnalyticsDto.WeekdayStat> weekdays = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            int occurrences = countWeekday(days, i + 1);
            double avg = occurrences == 0 ? 0 : byWeekday[i] / occurrences;
            weekdays.add(new AnalyticsDto.WeekdayStat(i + 1, WEEKDAY_LABELS[i], round(byWeekday[i]), round(avg)));
        }

        int releaseCount = (int) byRelease.keySet().stream().filter(r -> !NO_RELEASE.equals(r)).count();
        AnalyticsDto.Totals totals = new AnalyticsDto.Totals(
            totalTasks, round(totalHours), employeeList.size(), releaseCount, totalCapacity,
            round(totalCapacity == 0 ? 0 : totalHours / totalCapacity * 100),
            round(totalOvertime), overtimeEmployees, round(totalDowntime), downtimeDays, vacationDays, rentalDays);

        return new AnalyticsDto(from, to, workdays, totals, employeeStats,
            days.stream().map(LocalDate::toString).toList(), series,
            roundAll(dailyDowntime), roundAll(dailyCapacity), releases, weekdays);
    }

    /** Последний день последней задачи каждого релиза — по всем задачам, независимо от периода. */
    private Map<String, LocalDate> releaseLastDays() {
        Map<String, LocalDate> out = new HashMap<>();
        for (Task t : tasks.findByReleaseIsNotNull()) {
            if (t.getRelease().isBlank()) {
                continue;
            }
            LocalDate last = WorkDays.lastDay(t);
            out.merge(t.getRelease(), last, (a, b) -> a.isAfter(b) ? a : b);
        }
        return out;
    }

    /** Группируем задачи по Jira-ключу, если он есть, иначе по первой строке текста. */
    static String normalizeTitle(String title) {
        String firstLine = title.strip().lines().findFirst().orElse("").strip();
        Matcher m = JIRA_KEY.matcher(firstLine);
        if (m.find()) {
            return m.group(1);
        }
        return firstLine.isEmpty() ? "(без названия)" : firstLine;
    }

    private static int countWeekday(List<LocalDate> days, int isoWeekday) {
        int n = 0;
        for (LocalDate d : days) {
            if (d.getDayOfWeek().getValue() == isoWeekday) {
                n++;
            }
        }
        return n;
    }

    private static List<Double> roundAll(double[] values) {
        List<Double> out = new ArrayList<>(values.length);
        for (double v : values) {
            out.add(round(v));
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
