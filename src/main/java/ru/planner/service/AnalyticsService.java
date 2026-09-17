package ru.planner.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.planner.api.dto.AnalyticsDto;
import ru.planner.domain.Employee;
import ru.planner.domain.Task;
import ru.planner.repo.EmployeeRepository;
import ru.planner.repo.TaskRepository;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    /** Норма рабочего дня в часах — та же "восьмёрка", что подсвечивалась в исходном планировщике. */
    public static final double DAY_NORM = 8.0;
    private static final int MAX_RANGE_DAYS = 400;
    private static final String NO_RELEASE = "Без релиза";
    private static final String[] WEEKDAY_LABELS = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
    private static final Pattern JIRA_KEY = Pattern.compile("(?<![\\p{L}\\d-])(\\p{Lu}[\\p{Lu}\\d]{1,14}-\\d+)(?![\\p{L}\\d])");

    private final TaskRepository tasks;
    private final EmployeeRepository employees;

    @Transactional(readOnly = true)
    public AnalyticsDto build(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Дата окончания раньше даты начала");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("Период не может быть длиннее " + MAX_RANGE_DAYS + " дней");
        }

        List<Employee> employeeList = employees.findAllByOrderByPositionAscIdAsc();
        List<Task> taskList = tasks.findByDayBetweenOrderByDayAscPositionAscIdAsc(from, to);

        List<LocalDate> days = new ArrayList<>();
        int workdays = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.add(d);
            if (isWorkday(d)) {
                workdays++;
            }
        }

        // employeeId -> day -> часы
        Map<Long, Map<LocalDate, Double>> hoursByEmployeeDay = new HashMap<>();
        Map<Long, Integer> tasksByEmployee = new HashMap<>();
        Map<String, double[]> byRelease = new LinkedHashMap<>();   // [hours, count]
        Map<String, double[]> byTitle = new HashMap<>();           // [hours, count]
        double[] byWeekday = new double[7];
        double totalHours = 0;

        for (Task t : taskList) {
            double h = t.getEstimate() == null ? 0 : t.getEstimate();
            totalHours += h;
            hoursByEmployeeDay.computeIfAbsent(t.getEmployeeId(), k -> new HashMap<>()).merge(t.getDay(), h, Double::sum);
            tasksByEmployee.merge(t.getEmployeeId(), 1, Integer::sum);

            String release = t.getRelease() == null || t.getRelease().isBlank() ? NO_RELEASE : t.getRelease();
            accumulate(byRelease, release, h);
            accumulate(byTitle, normalizeTitle(t.getTitle()), h);
            byWeekday[t.getDay().getDayOfWeek().getValue() - 1] += h;
        }

        double capacityPerEmployee = workdays * DAY_NORM;
        List<AnalyticsDto.EmployeeStat> employeeStats = new ArrayList<>();
        List<AnalyticsDto.Series> series = new ArrayList<>();
        for (Employee e : employeeList) {
            Map<LocalDate, Double> perDay = hoursByEmployeeDay.getOrDefault(e.getId(), Map.of());
            double hours = 0;
            double maxDay = 0;
            int overloaded = 0;
            int idle = 0;
            List<Double> data = new ArrayList<>(days.size());
            for (LocalDate d : days) {
                double h = perDay.getOrDefault(d, 0.0);
                data.add(h);
                hours += h;
                maxDay = Math.max(maxDay, h);
                if (h > DAY_NORM) {
                    overloaded++;
                }
                if (h == 0 && isWorkday(d)) {
                    idle++;
                }
            }
            double utilization = capacityPerEmployee == 0 ? 0 : hours / capacityPerEmployee * 100;
            employeeStats.add(new AnalyticsDto.EmployeeStat(e.getId(), e.getName(), e.getColor(), round(hours),
                tasksByEmployee.getOrDefault(e.getId(), 0), capacityPerEmployee, round(utilization), overloaded, idle,
                round(maxDay)));
            series.add(new AnalyticsDto.Series(e.getId(), e.getName(), e.getColor(), data));
        }

        List<AnalyticsDto.ReleaseStat> releases = byRelease.entrySet().stream()
            .map(en -> new AnalyticsDto.ReleaseStat(en.getKey(), round(en.getValue()[0]), (int) en.getValue()[1]))
            .sorted(Comparator.comparingDouble(AnalyticsDto.ReleaseStat::hours).reversed())
            .toList();

        List<AnalyticsDto.TaskStat> topTasks = byTitle.entrySet().stream()
            .map(en -> new AnalyticsDto.TaskStat(en.getKey(), round(en.getValue()[0]), (int) en.getValue()[1]))
            .sorted(Comparator.comparingDouble(AnalyticsDto.TaskStat::hours).reversed()
                .thenComparing(AnalyticsDto.TaskStat::count, Comparator.reverseOrder()))
            .limit(10)
            .toList();

        List<AnalyticsDto.WeekdayStat> weekdays = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            int occurrences = countWeekday(days, i + 1);
            double avg = occurrences == 0 ? 0 : byWeekday[i] / occurrences;
            weekdays.add(new AnalyticsDto.WeekdayStat(i + 1, WEEKDAY_LABELS[i], round(byWeekday[i]), round(avg)));
        }

        double totalCapacity = capacityPerEmployee * employeeList.size();
        int releaseCount = (int) byRelease.keySet().stream().filter(r -> !NO_RELEASE.equals(r)).count();
        AnalyticsDto.Totals totals = new AnalyticsDto.Totals(
            taskList.size(),
            round(totalHours),
            employeeList.size(),
            releaseCount,
            totalCapacity,
            round(totalCapacity == 0 ? 0 : totalHours / totalCapacity * 100),
            round(workdays == 0 || employeeList.isEmpty() ? 0 : totalHours / workdays / employeeList.size()));

        return new AnalyticsDto(from, to, workdays, totals, employeeStats,
            days.stream().map(LocalDate::toString).toList(), series, releases, topTasks, weekdays);
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

    private static boolean isWorkday(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
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

    private static void accumulate(Map<String, double[]> map, String key, double hours) {
        double[] acc = map.computeIfAbsent(key, k -> new double[2]);
        acc[0] += hours;
        acc[1] += 1;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
