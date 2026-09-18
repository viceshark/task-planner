package ru.planner.api.dto;

import ru.planner.domain.Task;

import java.time.LocalDate;

public record TaskDto(Long id, Long employeeId, LocalDate day, String title, String release, Double estimate,
                      int position, int days, Double overtime) {

    public static TaskDto from(Task t) {
        return new TaskDto(t.getId(), t.getEmployeeId(), t.getDay(), t.getTitle(), t.getRelease(), t.getEstimate(),
            t.getPosition(), t.getDays(), t.getOvertime());
    }
}
