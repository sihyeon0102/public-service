package me.chung.publicservice.seed;

import me.chung.publicservice.domain.FacilityType;
import me.chung.publicservice.domain.OperatingStatus;

final class FacilitySeedData {

    private static final Region[] REGIONS = {
            new Region("SEOUL", 30, 37.56, 126.97),
            new Region("GYEONGGI", 30, 37.27, 127.01),
            new Region("BUSAN", 15, 35.18, 129.07),
            new Region("INCHEON", 8, 37.46, 126.70),
            new Region("DAEGU", 8, 35.87, 128.60),
            new Region("DAEJEON", 5, 36.35, 127.38),
            new Region("GWANGJU", 4, 35.16, 126.85)
    };
    private static final FacilityType[] TYPES = FacilityType.values();
    private static final OperatingStatus[] STATUSES = OperatingStatus.values();
    private static final int DISTRICTS_PER_REGION = 10;

    private FacilitySeedData() {
    }

    static void validateCount(int count) {
        if (count < 100 || count > 1_000_000 || count % 100 != 0) {
            throw new IllegalArgumentException(
                    "seed.facility.count must be a multiple of 100 between 100 and 1000000");
        }
    }

    // index is zero-based; generated names are independent of the auto-increment PK.
    static Row row(int index, int count) {
        if (index < 0 || index >= count) {
            throw new IllegalArgumentException("Seed row index out of range: " + index);
        }
        int localIndex = index;
        for (Region region : REGIONS) {
            int regionCount = count * region.percent() / 100;
            if (localIndex < regionCount) {
                String district = region.name() + "-DISTRICT-"
                        + (localIndex % DISTRICTS_PER_REGION + 1);
                // Each district receives every type, and each district/type receives every status.
                FacilityType type = TYPES[(localIndex / DISTRICTS_PER_REGION) % TYPES.length];
                OperatingStatus status = STATUSES[
                        (localIndex / (DISTRICTS_PER_REGION * TYPES.length)) % STATUSES.length];
                return new Row(
                        "Facility-" + (index + 1), region.name(), district, type,
                        district + " Test Road " + (localIndex + 1),
                        region.latitude() + (localIndex % 1000) * 0.00001,
                        region.longitude() + ((localIndex / 1000) % 1000) * 0.00001,
                        status
                );
            }
            localIndex -= regionCount;
        }
        throw new IllegalArgumentException("Invalid seed count: " + count);
    }

    record Row(String name, String region, String district, FacilityType type,
               String address, double latitude, double longitude, OperatingStatus status) {
    }

    private record Region(String name, int percent, double latitude, double longitude) {
    }
}
