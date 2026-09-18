package ru.planner.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.planner.api.dto.TaskCreateRequest;
import ru.planner.api.dto.TaskDto;
import ru.planner.api.dto.TaskMoveRequest;
import ru.planner.api.dto.TaskUpdateRequest;
import ru.planner.domain.Task;
import ru.planner.repo.EmployeeRepository;
import ru.planner.repo.TaskRepository;

import java.time.LocalDate;
import java.util.List;

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
        return findIntersecting(from, to).stream().map(TaskDto::from).toList();
    }

    List<Task> findIntersecting(LocalDate from, LocalDate to) {
        return tasks.findByDayBetweenOrderByDayAscPositionAscIdAsc(from.minusDays(WorkDays.SPAN_LOOKBACK), to).stream()
            .filter(t -> WorkDays.taskDays(t).stream().anyMatch(d -> !d.isBefore(from) && !d.isAfter(to)))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<String> releases() {
        return tasks.findDistinctReleases();
    }

    @Transactional
    public TaskDto create(TaskCreateRequest request) {
        requireEmployee(request.employeeId());
        Task t = new Task();
        t.setEmployeeId(request.employeeId());
        t.setDay(request.day());
        t.setTitle(request.title().trim());
        t.setRelease(normalize(request.release()));
        t.setEstimate(request.estimate());
        t.setOvertime(zeroToNull(request.overtime()));
        t.setDays(span(request.days(), request.estimate(), request.overtime()));
        t.setPosition(tasks.maxPosition(request.employeeId(), request.day()) + 1);
        return TaskDto.from(tasks.save(t));
    }

    @Transactional
    public TaskDto update(Long id, TaskUpdateRequest request) {
        Task t = get(id);
        t.setTitle(request.title().trim());
        t.setRelease(normalize(request.release()));
        t.setEstimate(request.estimate());
        t.setOvertime(zeroToNull(request.overtime()));
        t.setDays(span(request.days(), request.estimate(), request.overtime()));
        return TaskDto.from(tasks.save(t));
    }

    /** Перенос в другую ячейку: релиз, оценка, растяжка и овертайм остаются у задачи, меняются сотрудник и день начала. */
    @Transactional
    public TaskDto move(Long id, TaskMoveRequest request) {
        Task t = get(id);
        requireEmployee(request.employeeId());
        boolean sameCell = t.getEmployeeId().equals(request.employeeId()) && t.getDay().equals(request.day());
        if (!sameCell) {
            t.setEmployeeId(request.employeeId());
            t.setDay(request.day());
            t.setPosition(tasks.maxPosition(request.employeeId(), request.day()) + 1);
        }
        return TaskDto.from(tasks.save(t));
    }

    @Transactional
    public void delete(Long id) {
        tasks.delete(get(id));
    }

    /** Пролонгация разрешена только задачам, у которых часов больше дневной нормы. */
    private static int span(Integer days, Double estimate, Double overtime) {
        int d = days == null ? 1 : days;
        if (d > 1 && WorkDays.nz(estimate) + WorkDays.nz(overtime) <= WorkDays.DAY_NORM) {
            throw new IllegalArgumentException("Растянуть на несколько дней можно задачу с оценкой больше 8 часов");
        }
        return d;
    }

    private Task get(Long id) {
        return tasks.findById(id).orElseThrow(() -> new NotFoundException("Задача не найдена: " + id));
    }

    private void requireEmployee(Long employeeId) {
        if (!employees.existsById(employeeId)) {
            throw new NotFoundException("Сотрудник не найден: " + employeeId);
        }
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
}
