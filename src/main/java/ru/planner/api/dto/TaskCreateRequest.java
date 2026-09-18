package ru.planner.api.dto;

import jakarta.validation.constraints.DecimalMax;
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
    @Size(max = 100, message = "Эпик не длиннее 100 символов") String epic,
    @PositiveOrZero(message = "Оценка не может быть отрицательной") @DecimalMax(value = "720", message = "Оценка не больше 720 часов") Double estimate,
    @PositiveOrZero(message = "Часы сверх оценки не могут быть отрицательными") @DecimalMax(value = "240", message = "Не больше 240 часов сверх оценки") Double overtime,
    Boolean completedEarly,
    @PositiveOrZero(message = "Потраченные часы не могут быть отрицательными") @DecimalMax(value = "720", message = "Не больше 720 часов") Double spent) {
}
