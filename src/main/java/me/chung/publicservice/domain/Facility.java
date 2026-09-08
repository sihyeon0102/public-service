package me.chung.publicservice.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Facility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String region;
    private String district;

    @Enumerated(EnumType.STRING)
    private FacilityType type;

    private String address;
    private Double latitude;
    private Double longitude;

    @Enumerated(EnumType.STRING)
    private OperatingStatus operatingStatus;

    public Facility(String name, String region, String district, FacilityType type,
                    String address, Double latitude, Double longitude,
                    OperatingStatus operatingStatus) {
        this.name = name;
        this.region = region;
        this.district = district;
        this.type = type;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.operatingStatus = operatingStatus;
    }
}
