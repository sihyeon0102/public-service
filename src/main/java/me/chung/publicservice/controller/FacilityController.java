package me.chung.publicservice.controller;

import lombok.RequiredArgsConstructor;
import me.chung.publicservice.domain.FacilityType;
import me.chung.publicservice.dto.FacilityPageResponse;
import me.chung.publicservice.dto.FacilityResponse;
import me.chung.publicservice.service.FacilityService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/facilities")
@RequiredArgsConstructor
public class FacilityController {

    private final FacilityService facilityService;

    @GetMapping("/{id}")
    public FacilityResponse getFacility(@PathVariable("id") Long id) {
        return facilityService.getFacility(id);
    }

    @GetMapping
    public FacilityPageResponse getFacilities(
            @RequestParam(name = "region", required = false) String region,
            @RequestParam(name = "district", required = false) String district,
            @RequestParam(name = "type", required = false) FacilityType type,
            @PageableDefault(page = 0, size = 20, sort = "id") Pageable pageable
    ) {
        return facilityService.getFacilities(region, district, type, pageable);
    }
}
