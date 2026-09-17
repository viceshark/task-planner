package ru.planner.api.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Новый порядок сотрудников: список идентификаторов сверху вниз. */
public record ReorderRequest(@NotEmpty(message = "Список пуст") List<Long> ids) {
}
