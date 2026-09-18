package ru.planner.api.dto;

import ru.planner.domain.Absence;
import ru.planner.domain.AbsenceType;

import java.time.LocalDate;

public record AbsenceDto(Long id, Long employeeId, AbsenceType type, LocalDate startDay, LocalDate endDay,
                         Double hoursPerDay, String note) {

    public static AbsenceDto from(Absence a) {
        return new AbsenceDto(a.getId(), a.getEmployeeId(), a.getType(), a.getStartDay(), a.getEndDay(),
            a.getHoursPerDay(), a.getNote());
    }
}
