package ru.planner.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record TaskCreateRequest(
    @NotNull(message = "Не указан сотрудник") Long employeeId,
    @NotNull(message = "Не указан день") LocalDate day,
    @NotBlank(message = "Текст задачи обязателен") @Size(max = 2000, message = "Текст не длиннее 2000 символов") String title,
    @Size(max = 100, message = "Релиз не длиннее 100 символов") String release,
    @PositiveOrZero(message = "Оценка не может быть отрицательной") @DecimalMax(value = "720", message = "Оценка не больше 720 часов") Double estimate,
    @Min(value = 1, message = "Минимум 1 день") @Max(value = 30, message = "Растянуть можно максимум на 30 рабочих дней") Integer days,
    @PositiveOrZero(message = "Овертайм не может быть отрицательным") @DecimalMax(value = "240", message = "Овертайм не больше 240 часов") Double overtime) {
}
