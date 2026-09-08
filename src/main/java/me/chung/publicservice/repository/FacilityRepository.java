package me.chung.publicservice.repository;

import me.chung.publicservice.domain.Facility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FacilityRepository extends JpaRepository<Facility, Long>,
        JpaSpecificationExecutor<Facility> {
}
