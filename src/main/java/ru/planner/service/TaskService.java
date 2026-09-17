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

    @Transactional(readOnly = true)
    public List<TaskDto> list(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Дата окончания раньше даты начала");
        }
        return tasks.findByDayBetweenOrderByDayAscPositionAscIdAsc(from, to).stream().map(TaskDto::from).toList();
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
        t.setPosition(tasks.maxPosition(request.employeeId(), request.day()) + 1);
        return TaskDto.from(tasks.save(t));
    }

    @Transactional
    public TaskDto update(Long id, TaskUpdateRequest request) {
        Task t = get(id);
        t.setTitle(request.title().trim());
        t.setRelease(normalize(request.release()));
        t.setEstimate(request.estimate());
        return TaskDto.from(tasks.save(t));
    }

    /** Перенос в другую ячейку: релиз и оценка остаются у задачи, меняются только сотрудник и день. */
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
}
