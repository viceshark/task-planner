package ru.planner.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.planner.domain.AbsenceType;

import java.time.LocalDate;

public record AbsenceRequest(
    @NotNull(message = "Не указан сотрудник") Long employeeId,
    @NotNull(message = "Не указан тип события") AbsenceType type,
    @NotNull(message = "Не указана дата начала") LocalDate startDay,
    @NotNull(message = "Не указана дата окончания") LocalDate endDay,
    @DecimalMin(value = "0.5", message = "Минимум 0.5 часа") @DecimalMax(value = "24", message = "Не больше 24 часов в день") Double hoursPerDay,
    @Size(max = 500, message = "Комментарий не длиннее 500 символов") String note) {
}
