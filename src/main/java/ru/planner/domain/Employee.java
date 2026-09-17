package ru.planner.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "employees")
@Getter
@Setter
@NoArgsConstructor
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Цвет в формате #rrggbb, используется для маркера сотрудника и графиков. */
    @Column(nullable = false)
    private String color;

    /** Порядок в списке сотрудников (меняется drag-and-drop'ом). */
    @Column(nullable = false)
    private int position;
}
