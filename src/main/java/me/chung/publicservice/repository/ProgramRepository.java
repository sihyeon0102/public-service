package me.chung.publicservice.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import me.chung.publicservice.domain.Program;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProgramRepository extends JpaRepository<Program, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Program p where p.id = :id")
    Optional<Program> findByIdForUpdate(@Param("id") Long id);
}
