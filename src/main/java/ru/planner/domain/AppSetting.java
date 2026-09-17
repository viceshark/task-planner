package ru.planner.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Настройки приложения в виде пар ключ/значение. */
@Entity
@Table(name = "settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppSetting {

    public static final String JIRA_BASE_URL = "jira.base.url";

    @Id
    @Column(name = "key")
    private String key;

    @Column(name = "value")
    private String value;
}
