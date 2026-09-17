package ru.planner.api.dto;

import ru.planner.domain.Employee;

public record EmployeeDto(Long id, String name, String color, int position) {

    public static EmployeeDto from(Employee e) {
        return new EmployeeDto(e.getId(), e.getName(), e.getColor(), e.getPosition());
    }
}
