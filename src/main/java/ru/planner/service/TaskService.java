package ru.planner.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.planner.api.dto.TaskCreateRequest;
import ru.planner.api.dto.TaskDto;
import ru.planner.api.dto.TaskMoveRequest;
import ru.planner.api.dto.TaskUpdateRequest;
import ru.planner.domain.Employee;
import ru.planner.domain.Task;
import ru.planner.repo.EmployeeRepository;
import ru.planner.repo.TaskRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository tasks;
    private final EmployeeRepository employees;

    /** Задачи, хотя бы один день которых попадает в [from, to] — с учётом растянутых на несколько дней. */
    @Transactional(readOnly = true)
    public List<TaskDto> list(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Дата окончания раньше даты начала");
        }
        Map<Long, Double> norms = norms();
        return findIntersecting(from, to, norms).stream().map(t -> toDto(t, norms)).toList();
    }

    /** Норма дня каждого сотрудника (8ч × ставка). */
    Map<Long, Double> norms() {
        return employees.findAll().stream().collect(Collectors.toMap(Employee::getId, Employee::dayNorm));
    }

    static double normOf(Map<Long, Double> norms, Long employeeId) {
        return norms.getOrDefault(employeeId, WorkDays.DAY_NORM);
    }

    List<Task> findIntersecting(LocalDate from, LocalDate to, Map<Long, Double> norms) {
        return tasks.findByDayBetweenOrderByDayAscPositionAscIdAsc(from.minusDays(WorkDays.SPAN_LOOKBACK), to).stream()
            .filter(t -> WorkDays.taskDays(t, normOf(norms, t.getEmployeeId())).stream()
                .anyMatch(d -> !d.isBefore(from) && !d.isAfter(to)))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<String> releases() {
        return tasks.findDistinctReleases();
    }

    @Transactional(readOnly = true)
    public List<String> epics() {
        return tasks.findDistinctEpics();
    }

    @Transactional
    public TaskDto create(TaskCreateRequest request) {
        Employee e = requireEmployee(request.employeeId());
        Task t = new Task();
        t.setEmployeeId(request.employeeId());
        t.setDay(request.day());
        t.setTitle(request.title().trim());
        t.setRelease(normalize(request.release()));
        t.setEpic(normalize(request.epic()));
        t.setEstimate(request.estimate());
        t.setOvertime(zeroToNull(request.overtime()));
        applyCompletion(t, request.completedEarly(), request.spent());
        t.setDays(WorkDays.spanDays(t, e.dayNorm()));
        t.setPosition(tasks.maxPosition(request.employeeId(), request.day()) + 1);
        return toDto(tasks.save(t), e.dayNorm());
    }

    @Transactional
    public TaskDto update(Long id, TaskUpdateRequest request) {
        Task t = get(id);
        Employee e = requireEmployee(t.getEmployeeId());
        t.setTitle(request.title().trim());
        t.setRelease(normalize(request.release()));
        t.setEpic(normalize(request.epic()));
        t.setEstimate(request.estimate());
        t.setOvertime(zeroToNull(request.overtime()));
        applyCompletion(t, request.completedEarly(), request.spent());
        t.setDays(WorkDays.spanDays(t, e.dayNorm()));
        return toDto(tasks.save(t), e.dayNorm());
    }

    /** Перенос в другую ячейку: все атрибуты остаются у задачи, меняются сотрудник и день начала. */
    @Transactional
    public TaskDto move(Long id, TaskMoveRequest request) {
        Task t = get(id);
        Employee e = requireEmployee(request.employeeId());
        boolean sameCell = t.getEmployeeId().equals(request.employeeId()) && t.getDay().equals(request.day());
        if (!sameCell) {
            t.setEmployeeId(request.employeeId());
            t.setDay(request.day());
            t.setPosition(tasks.maxPosition(request.employeeId(), request.day()) + 1);
        }
        t.setDays(WorkDays.spanDays(t, e.dayNorm()));
        return toDto(tasks.save(t), e.dayNorm());
    }

    @Transactional
    public void delete(Long id) {
        tasks.delete(get(id));
    }

    /**
     * Досрочное завершение: задача занимает фактически потраченные часы вместо оценки,
     * часы сверх оценки при этом теряют смысл и сбрасываются.
     */
    private static void applyCompletion(Task t, Boolean completedEarly, Double spent) {
        boolean early = Boolean.TRUE.equals(completedEarly);
        t.setCompletedEarly(early);
        if (early) {
            t.setSpent(spent == null ? WorkDays.nz(t.getEstimate()) : spent);
            t.setOvertime(null);
        } else {
            t.setSpent(null);
        }
    }

    private TaskDto toDto(Task t, Map<Long, Double> norms) {
        return toDto(t, normOf(norms, t.getEmployeeId()));
    }

    private static TaskDto toDto(Task t, double norm) {
        return TaskDto.from(t, WorkDays.spanDays(t, norm), WorkDays.effectiveHours(t));
    }

    private Task get(Long id) {
        return tasks.findById(id).orElseThrow(() -> new NotFoundException("Задача не найдена: " + id));
    }

    private Employee requireEmployee(Long employeeId) {
        return employees.findById(employeeId)
            .orElseThrow(() -> new NotFoundException("Сотрудник не найден: " + employeeId));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Double zeroToNull(Double value) {
        return value == null || value == 0 ? null : value;
    }

    /** Для аналитики: функция нормы по id сотрудника. */
    Function<Long, Double> normFunction() {
        Map<Long, Double> norms = norms();
        return id -> normOf(norms, id);
    }
}
