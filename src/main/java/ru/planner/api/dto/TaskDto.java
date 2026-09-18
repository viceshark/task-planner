package ru.planner.api.dto;

import ru.planner.domain.Task;

import java.time.LocalDate;

/**
 * Задача для клиента. {@code days} — производное: сколько рабочих дней она занимает
 * при норме сотрудника; {@code hours} — часы, которые задача реально занимает в календаре.
 */
public record TaskDto(Long id, Long employeeId, LocalDate day, String title, String release, String epic,
                      Double estimate, Double overtime, boolean completedEarly, Double spent,
                      int position, int days, double hours) {

    public static TaskDto from(Task t, int days, double hours) {
        return new TaskDto(t.getId(), t.getEmployeeId(), t.getDay(), t.getTitle(), t.getRelease(), t.getEpic(),
            t.getEstimate(), t.getOvertime(), t.isCompletedEarly(), t.getSpent(), t.getPosition(), days, hours);
    }
}
