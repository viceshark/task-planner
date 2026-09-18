package ru.planner.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** Простой, аренда или отпуск сотрудника на период дней [startDay, endDay]. */
@Entity
@Table(name = "absences")
@Getter
@Setter
@NoArgsConstructor
public class Absence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AbsenceType type;

    @Column(name = "start_day", nullable = false)
    @Convert(converter = LocalDateStringConverter.class)
    private LocalDate startDay;

    @Column(name = "end_day", nullable = false)
    @Convert(converter = LocalDateStringConverter.class)
    private LocalDate endDay;

    /** Часов в день; null — весь день (норма 8ч). Актуально для простоя. */
    @Column(name = "hours_per_day")
    private Double hoursPerDay;

    /** Комментарий: причина простоя, куда арендован и т.п. */
    @Column
    private String note;
}
