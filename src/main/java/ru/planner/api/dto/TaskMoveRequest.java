package ru.planner.api.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Перенос задачи (drag-and-drop) в другую ячейку: сотрудник + день. */
public record TaskMoveRequest(
    @NotNull(message = "Не указан сотрудник") Long employeeId,
    @NotNull(message = "Не указан день") LocalDate day) {
}
