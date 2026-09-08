package me.chung.publicservice.service;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import me.chung.publicservice.domain.Facility;
import me.chung.publicservice.domain.FacilityType;
import me.chung.publicservice.dto.FacilityPageResponse;
import me.chung.publicservice.dto.FacilityResponse;
import me.chung.publicservice.repository.FacilityRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FacilityService {

    private final FacilityRepository facilityRepository;

    public FacilityResponse getFacility(Long id) {
        Facility facility = facilityRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Facility not found: " + id));
        return FacilityResponse.from(facility);
    }

    public FacilityPageResponse getFacilities(String region, String district,
                                              FacilityType type, Pageable pageable) {
        Specification<Facility> specification = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (region != null) {
                predicates.add(criteriaBuilder.equal(root.get("region"), region));
            }
            if (district != null) {
                predicates.add(criteriaBuilder.equal(root.get("district"), district));
            }
            if (type != null) {
                predicates.add(criteriaBuilder.equal(root.get("type"), type));
            }
            // No predicate means no WHERE clause when all filters are omitted.
            return predicates.isEmpty() ? null
                    : criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };

        return FacilityPageResponse.from(
                facilityRepository.findAll(specification, pageable)
                        .map(FacilityResponse::from));
    }
}
