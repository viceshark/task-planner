package ru.planner.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.planner.domain.Employee;

import java.util.List;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    List<Employee> findAllByOrderByPositionAscIdAsc();

    @Query("select coalesce(max(e.position), -1) from Employee e")
    int maxPosition();
}
