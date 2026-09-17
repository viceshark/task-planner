package ru.planner.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.planner.api.dto.TaskCreateRequest;
import ru.planner.api.dto.TaskDto;
import ru.planner.api.dto.TaskMoveRequest;
import ru.planner.api.dto.TaskUpdateRequest;
import ru.planner.service.TaskService;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService tasks;

    /** Задачи всех сотрудников за период [from, to] — календарь подгружает их порциями при прокрутке. */
    @GetMapping
    public List<TaskDto> list(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return tasks.list(from, to);
    }

    @GetMapping("/releases")
    public List<String> releases() {
        return tasks.releases();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDto create(@Valid @RequestBody TaskCreateRequest request) {
        return tasks.create(request);
    }

    @PutMapping("/{id}")
    public TaskDto update(@PathVariable Long id, @Valid @RequestBody TaskUpdateRequest request) {
        return tasks.update(id, request);
    }

    @PatchMapping("/{id}/move")
    public TaskDto move(@PathVariable Long id, @Valid @RequestBody TaskMoveRequest request) {
        return tasks.move(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        tasks.delete(id);
    }
}
