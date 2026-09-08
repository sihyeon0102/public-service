package me.chung.publicservice.seed;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import me.chung.publicservice.domain.FacilityType;
import me.chung.publicservice.domain.OperatingStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FacilitySeedDataTests {

    @Test
    void generatesExactRegionProportionsForSupportedExperimentSizes() {
        Map<String, Integer> percentages = Map.of(
                "SEOUL", 30, "GYEONGGI", 30, "BUSAN", 15, "INCHEON", 8,
                "DAEGU", 8, "DAEJEON", 5, "GWANGJU", 4);
        for (int count : new int[]{100_000, 500_000, 1_000_000}) {
            FacilitySeedData.validateCount(count);
            Map<String, Integer> actual = new HashMap<>();
            for (int index = 0; index < count; index++) {
                actual.merge(FacilitySeedData.row(index, count).region(), 1, Integer::sum);
            }
            assertEquals(percentages.keySet(), actual.keySet());
            percentages.forEach((region, percent) ->
                    assertEquals(count * percent / 100, actual.get(region)));
        }
    }

    @Test
    void mixesEveryTypeAndStatusWithinEachDistrictDeterministically() {
        Map<String, EnumSet<FacilityType>> typesByDistrict = new HashMap<>();
        Map<String, EnumSet<OperatingStatus>> statusesByDistrictAndType = new HashMap<>();
        for (int index = 0; index < 100_000; index++) {
            FacilitySeedData.Row row = FacilitySeedData.row(index, 100_000);
            assertEquals("Facility-" + (index + 1), row.name());
            assertEquals(row, FacilitySeedData.row(index, 100_000));
            assertTrue(row.latitude() >= -90 && row.latitude() <= 90);
            assertTrue(row.longitude() >= -180 && row.longitude() <= 180);
            assertTrue(row.address().length() <= 255);
            typesByDistrict.computeIfAbsent(row.district(), key -> EnumSet.noneOf(FacilityType.class))
                    .add(row.type());
            statusesByDistrictAndType.computeIfAbsent(row.district() + ":" + row.type(),
                    key -> EnumSet.noneOf(OperatingStatus.class)).add(row.status());
        }
        assertEquals(70, typesByDistrict.size());
        typesByDistrict.values().forEach(types -> assertEquals(EnumSet.allOf(FacilityType.class), types));
        assertEquals(70 * FacilityType.values().length, statusesByDistrictAndType.size());
        statusesByDistrictAndType.values().forEach(statuses ->
                assertEquals(EnumSet.allOf(OperatingStatus.class), statuses));
    }

    @Test
    void rejectsCountsThatCannotPreserveProportionsOrExceedTheLimit() {
        for (int count : new int[]{-100, 0, 99, 101, 1_000_100}) {
            assertThrows(IllegalArgumentException.class, () -> FacilitySeedData.validateCount(count));
        }
    }
}
