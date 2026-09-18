package ru.planner.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.planner.api.dto.AbsenceDto;
import ru.planner.api.dto.AbsenceRequest;
import ru.planner.domain.Absence;
import ru.planner.domain.AbsenceType;
import ru.planner.domain.Employee;
import ru.planner.repo.AbsenceRepository;
import ru.planner.repo.EmployeeRepository;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Простои, аренды и отпуска сотрудников. */
@Service
@RequiredArgsConstructor
public class AbsenceService {

    private static final int MAX_RANGE_DAYS = 366;

    private final AbsenceRepository absences;
    private final EmployeeRepository employees;

    @Transactional(readOnly = true)
    public List<AbsenceDto> list(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Дата окончания раньше даты начала");
        }
        return absences.findOverlapping(from, to).stream().map(AbsenceDto::from).toList();
    }

    @Transactional
    public AbsenceDto create(AbsenceRequest request) {
        Absence a = new Absence();
        apply(a, request);
        return AbsenceDto.from(absences.save(a));
    }

    @Transactional
    public AbsenceDto update(Long id, AbsenceRequest request) {
        Absence a = get(id);
        apply(a, request);
        return AbsenceDto.from(absences.save(a));
    }

    @Transactional
    public void delete(Long id) {
        absences.delete(get(id));
    }

    private void apply(Absence a, AbsenceRequest request) {
        Employee e = employees.findById(request.employeeId())
            .orElseThrow(() -> new NotFoundException("Сотрудник не найден: " + request.employeeId()));
        if (request.endDay().isBefore(request.startDay())) {
            throw new IllegalArgumentException("Дата окончания раньше даты начала");
        }
        if (ChronoUnit.DAYS.between(request.startDay(), request.endDay()) > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("Событие не может быть длиннее " + MAX_RANGE_DAYS + " дней");
        }
        a.setEmployeeId(request.employeeId());
        a.setType(request.type());
        a.setStartDay(request.startDay());
        a.setEndDay(request.endDay());
        // часы в день имеют смысл только для простоя; отпуск и аренда занимают весь день
        a.setHoursPerDay(request.type() == AbsenceType.DOWNTIME ? fullDayToNull(request.hoursPerDay(), e.dayNorm()) : null);
        String note = request.note() == null ? "" : request.note().trim();
        a.setNote(note.isEmpty() ? null : note);
    }

    private static Double fullDayToNull(Double hours, double norm) {
        return hours == null || hours >= norm ? null : hours;
    }

    private Absence get(Long id) {
        return absences.findById(id).orElseThrow(() -> new NotFoundException("Событие не найдено: " + id));
    }
}
