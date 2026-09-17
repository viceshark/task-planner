package ru.planner.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.planner.api.dto.EmployeeDto;
import ru.planner.api.dto.EmployeeRequest;
import ru.planner.domain.Employee;
import ru.planner.repo.EmployeeRepository;
import ru.planner.repo.TaskRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private static final String DEFAULT_COLOR = "#00e5ff";

    private final EmployeeRepository employees;
    private final TaskRepository tasks;

    @Transactional(readOnly = true)
    public List<EmployeeDto> list() {
        return employees.findAllByOrderByPositionAscIdAsc().stream().map(EmployeeDto::from).toList();
    }

    @Transactional
    public EmployeeDto create(EmployeeRequest request) {
        Employee e = new Employee();
        e.setName(request.name().trim());
        e.setColor(colorOrDefault(request.color()));
        e.setPosition(employees.maxPosition() + 1);
        return EmployeeDto.from(employees.save(e));
    }

    @Transactional
    public EmployeeDto update(Long id, EmployeeRequest request) {
        Employee e = get(id);
        e.setName(request.name().trim());
        e.setColor(colorOrDefault(request.color()));
        return EmployeeDto.from(employees.save(e));
    }

    /** Удаляет сотрудника вместе со всеми его задачами. */
    @Transactional
    public void delete(Long id) {
        Employee e = get(id);
        tasks.deleteByEmployeeId(e.getId());
        employees.delete(e);
    }

    /** Переставляет сотрудников в порядке переданных id; не упомянутые остаются в конце. */
    @Transactional
    public List<EmployeeDto> reorder(List<Long> ids) {
        List<Employee> all = employees.findAllByOrderByPositionAscIdAsc();
        Map<Long, Employee> byId = new HashMap<>();
        all.forEach(e -> byId.put(e.getId(), e));

        int position = 0;
        for (Long id : ids) {
            Employee e = byId.remove(id);
            if (e != null) {
                e.setPosition(position++);
            }
        }
        for (Employee e : all) {
            if (byId.containsKey(e.getId())) {
                e.setPosition(position++);
            }
        }
        employees.saveAll(all);
        return list();
    }

    Employee get(Long id) {
        return employees.findById(id).orElseThrow(() -> new NotFoundException("Сотрудник не найден: " + id));
    }

    private static String colorOrDefault(String color) {
        return color == null || color.isBlank() ? DEFAULT_COLOR : color.toLowerCase();
    }
}
