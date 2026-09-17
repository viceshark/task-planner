package ru.planner.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record TaskUpdateRequest(
    @NotBlank(message = "Текст задачи обязателен") @Size(max = 2000, message = "Текст не длиннее 2000 символов") String title,
    @Size(max = 100, message = "Релиз не длиннее 100 символов") String release,
    @PositiveOrZero(message = "Оценка не может быть отрицательной") @DecimalMax(value = "24", message = "Оценка не больше 24 часов") Double estimate) {
}
