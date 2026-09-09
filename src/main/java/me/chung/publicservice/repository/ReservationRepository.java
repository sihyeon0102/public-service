package me.chung.publicservice.repository;

import me.chung.publicservice.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    long countByProgramId(Long programId);
}
