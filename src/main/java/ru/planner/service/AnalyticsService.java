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

    private static final int MAX_RANGE_DAYS = 400;
    private static final String NO_RELEASE = "Без релиза";
    private static final String NO_EPIC = "Без эпика";
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
        int overtimeTasks;
        int tasks;
        int earlyTasks;
        double savedHours;
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
        Map<Long, Double> norms = taskService.norms();
        List<Task> taskList = taskService.findIntersecting(from, to, norms);
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
        Map<String, double[]> byEpic = new LinkedHashMap<>();
        double[] byWeekday = new double[7];
        double totalHours = 0;

        // Задачи: по норме сотрудника в день, остаток на последний; в период попадают только дни внутри [from, to]
        for (Task t : taskList) {
            Acc acc = byEmployee.get(t.getEmployeeId());
            double norm = TaskService.normOf(norms, t.getEmployeeId());
            List<LocalDate> taskDays = WorkDays.taskDays(t, norm);
            boolean counted = false;
            double overtimeInRange = 0;
            for (int i = 0; i < taskDays.size(); i++) {
                LocalDate d = taskDays.get(i);
                if (d.isBefore(from) || d.isAfter(to)) {
                    continue;
                }
                counted = true;
                double h = WorkDays.hoursOn(t, i, norm);
                totalHours += h;
                byWeekday[d.getDayOfWeek().getValue() - 1] += h;
                overtimeInRange += WorkDays.overtimeOn(t, i, norm);
                if (acc != null) {
                    acc.hours.merge(d, h, Double::sum);
                }
            }
            if (!counted) {
                continue;
            }
            double effective = WorkDays.effectiveHours(t);
            if (acc != null) {
                acc.tasks++;
                acc.overtime += overtimeInRange;
                if (overtimeInRange > 0) {
                    acc.overtimeTasks++;
                }
                if (t.isCompletedEarly()) {
                    acc.earlyTasks++;
                    acc.savedHours += Math.max(0, WorkDays.nz(t.getEstimate()) - effective);
                }
            }
            accumulate(byRelease, blankTo(t.getRelease(), NO_RELEASE), effective);
            accumulate(byEpic, blankTo(t.getEpic(), NO_EPIC), effective);
        }

        // События: простой — часы, отпуск и аренда — целые рабочие дни
        for (Absence a : absenceList) {
            Acc acc = byEmployee.get(a.getEmployeeId());
            if (acc == null) {
                continue;
            }
            double norm = TaskService.normOf(norms, a.getEmployeeId());
            LocalDate start = a.getStartDay().isBefore(from) ? from : a.getStartDay();
            LocalDate end = a.getEndDay().isAfter(to) ? to : a.getEndDay();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                if (!WorkDays.isWorkday(d)) {
                    continue;
                }
                switch (a.getType()) {
                    case DOWNTIME -> acc.downtime.merge(d, a.getHoursPerDay() == null ? norm : a.getHoursPerDay(), Double::sum);
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
        double totalSaved = 0;
        int overtimeEmployees = 0;
        int overtimeTasks = 0;
        int earlyTasks = 0;
        int downtimeDays = 0;
        int vacationDays = 0;
        int rentalDays = 0;
        int totalTasks = 0;

        for (Employee e : employeeList) {
            Acc acc = byEmployee.get(e.getId());
            double norm = e.dayNorm();
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
                // простой — не рабочее время: ёмкость не уменьшает, а день без задач считается пустым
                if (WorkDays.isWorkday(d) && !away) {
                    capacity += norm;
                    dailyCapacity[i] += norm;
                    if (h == 0) {
                        idle++;
                    }
                }
                if (h > norm + 1e-9) {
                    overloaded++;
                }
            }
            double utilization = capacity == 0 ? 0 : hours / capacity * 100;
            employeeStats.add(new AnalyticsDto.EmployeeStat(e.getId(), e.getName(), e.getColor(), e.getRate(),
                round(hours), acc.tasks, round(capacity), round(utilization), overloaded, idle, round(maxDay),
                round(acc.overtime), acc.overtimeTasks, round(downtime), acc.vacation.size(), acc.rental.size(),
                acc.earlyTasks, round(acc.savedHours)));
            series.add(new AnalyticsDto.Series(e.getId(), e.getName(), e.getColor(), data));

            totalCapacity += capacity;
            totalOvertime += acc.overtime;
            totalDowntime += downtime;
            totalSaved += acc.savedHours;
            if (acc.overtime > 0) {
                overtimeEmployees++;
            }
            overtimeTasks += acc.overtimeTasks;
            earlyTasks += acc.earlyTasks;
            downtimeDays += acc.downtime.size();
            vacationDays += acc.vacation.size();
            rentalDays += acc.rental.size();
            totalTasks += acc.tasks;
        }

        Map<String, LocalDate> readiness = releaseLastDays(norms);
        List<AnalyticsDto.ReleaseStat> releases = byRelease.entrySet().stream()
            .map(en -> {
                LocalDate last = readiness.get(en.getKey());
                return new AnalyticsDto.ReleaseStat(en.getKey(), round(en.getValue()[0]), (int) en.getValue()[1],
                    last, last == null ? null : WorkDays.nextWorkday(last));
            })
            .sorted(Comparator.comparingDouble(AnalyticsDto.ReleaseStat::hours).reversed())
            .toList();

        List<AnalyticsDto.GroupStat> epics = byEpic.entrySet().stream()
            .map(en -> new AnalyticsDto.GroupStat(en.getKey(), round(en.getValue()[0]), (int) en.getValue()[1]))
            .sorted(Comparator.comparingDouble(AnalyticsDto.GroupStat::hours).reversed())
            .toList();

        List<AnalyticsDto.WeekdayStat> weekdays = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            int occurrences = countWeekday(days, i + 1);
            double avg = occurrences == 0 ? 0 : byWeekday[i] / occurrences;
            weekdays.add(new AnalyticsDto.WeekdayStat(i + 1, WEEKDAY_LABELS[i], round(byWeekday[i]), round(avg)));
        }

        int releaseCount = (int) byRelease.keySet().stream().filter(r -> !NO_RELEASE.equals(r)).count();
        AnalyticsDto.Totals totals = new AnalyticsDto.Totals(
            totalTasks, round(totalHours), employeeList.size(), releaseCount, round(totalCapacity),
            round(totalCapacity == 0 ? 0 : totalHours / totalCapacity * 100),
            round(totalOvertime), overtimeTasks, overtimeEmployees, round(totalDowntime), downtimeDays, vacationDays,
            rentalDays, earlyTasks, round(totalSaved));

        return new AnalyticsDto(from, to, workdays, totals, employeeStats,
            days.stream().map(LocalDate::toString).toList(), series,
            roundAll(dailyDowntime), roundAll(dailyCapacity), releases, epics, weekdays);
    }

    /** Последний день последней задачи каждого релиза — по всем задачам, независимо от периода. */
    private Map<String, LocalDate> releaseLastDays(Map<Long, Double> norms) {
        Map<String, LocalDate> out = new HashMap<>();
        for (Task t : tasks.findByReleaseIsNotNull()) {
            if (t.getRelease().isBlank()) {
                continue;
            }
            LocalDate last = WorkDays.lastDay(t, TaskService.normOf(norms, t.getEmployeeId()));
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

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void accumulate(Map<String, double[]> map, String key, double hours) {
        double[] acc = map.computeIfAbsent(key, k -> new double[2]);
        acc[0] += hours;
        acc[1] += 1;
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
