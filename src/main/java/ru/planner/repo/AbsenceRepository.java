package ru.planner.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.planner.domain.Absence;

import java.time.LocalDate;
import java.util.List;

public interface AbsenceRepository extends JpaRepository<Absence, Long> {

    /** События, пересекающиеся с периодом [from, to]. */
    @Query("select a from Absence a where a.endDay >= :from and a.startDay <= :to order by a.startDay, a.id")
    List<Absence> findOverlapping(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Modifying
    @Query("delete from Absence a where a.employeeId = :employeeId")
    void deleteByEmployeeId(@Param("employeeId") Long employeeId);
}
