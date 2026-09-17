package ru.planner.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ru.planner.domain.Employee;
import ru.planner.repo.EmployeeRepository;

import java.util.List;

/** При первом запуске заполняет пустую таблицу сотрудников демо-данными. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final EmployeeRepository employees;
    private final AppProperties props;

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        if (!props.seed().employees() || employees.count() > 0) {
            return;
        }
        List<String[]> seed = List.of(
            new String[]{"Никита", "#00e5ff"},
            new String[]{"Вадим", "#ff2fd6"},
            new String[]{"Сергей", "#ffd166"},
            new String[]{"Максим", "#b388ff"});
        int position = 0;
        for (String[] row : seed) {
            Employee e = new Employee();
            e.setName(row[0]);
            e.setColor(row[1]);
            e.setPosition(position++);
            employees.save(e);
        }
        log.info("Созданы демо-сотрудники: {}", seed.size());
    }
}
