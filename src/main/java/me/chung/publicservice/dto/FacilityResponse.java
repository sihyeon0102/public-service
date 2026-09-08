package me.chung.publicservice.dto;

import me.chung.publicservice.domain.Facility;
import me.chung.publicservice.domain.FacilityType;
import me.chung.publicservice.domain.OperatingStatus;

public record FacilityResponse(
        Long id,
        String name,
        String region,
        String district,
        FacilityType type,
        String address,
        Double latitude,
        Double longitude,
        OperatingStatus operatingStatus
) {
    public static FacilityResponse from(Facility facility) {
        return new FacilityResponse(
                facility.getId(),
                facility.getName(),
                facility.getRegion(),
                facility.getDistrict(),
                facility.getType(),
                facility.getAddress(),
                facility.getLatitude(),
                facility.getLongitude(),
                facility.getOperatingStatus()
        );
    }
}
