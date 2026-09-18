package ru.planner.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    /** День, на который запланирована задача. В SQLite хранится строкой yyyy-MM-dd. */
    @Column(name = "day", nullable = false)
    @Convert(converter = LocalDateStringConverter.class)
    private LocalDate day;

    /** Текст задачи: Jira-ключи и URL превращаются во фронтенде в ссылки. */
    @Column(nullable = false)
    private String title;

    /** Релиз, к которому относится задача. Переезжает вместе с задачей. */
    @Column(name = "release_name")
    private String release;

    /** Оценка в часах. Переезжает вместе с задачей, суммируется в занятость за день. */
    @Column
    private Double estimate;

    /** Порядок внутри ячейки (сотрудник + день). */
    @Column(nullable = false)
    private int position;

    /**
     * На сколько рабочих дней растянута задача. Значение производное (часы / норма сотрудника)
     * и пересчитывается при каждом сохранении; источник истины — {@code WorkDays.spanDays}.
     */
    @Column(nullable = false)
    private int days = 1;

    /** Часы сверх оценки: задача не уложилась в оценку, перерасход учитывается в аналитике отдельно. */
    @Column
    private Double overtime;

    /** Эпик (как в Jira): свободный текст, в карточке показывается цветной меткой. */
    @Column
    private String epic;

    /** Задача завершена досрочно: занимает {@link #spent} часов вместо оценки. */
    @Column(name = "completed_early", nullable = false)
    private boolean completedEarly;

    /** Фактически потраченные часы (для досрочно завершённой задачи). */
    @Column
    private Double spent;
}
