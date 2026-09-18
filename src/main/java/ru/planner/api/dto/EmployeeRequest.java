package ru.planner.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EmployeeRequest(
    @NotBlank(message = "Имя обязательно") @Size(max = 100, message = "Имя не длиннее 100 символов") String name,
    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Цвет должен быть в формате #rrggbb") String color,
    @DecimalMin(value = "0.1", message = "Ставка не меньше 0.1") @DecimalMax(value = "2", message = "Ставка не больше 2") Double rate) {
}
