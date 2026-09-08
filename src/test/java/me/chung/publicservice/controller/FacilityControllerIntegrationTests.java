package me.chung.publicservice.controller;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import me.chung.publicservice.domain.Facility;
import me.chung.publicservice.domain.FacilityType;
import me.chung.publicservice.domain.OperatingStatus;
import me.chung.publicservice.repository.FacilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class FacilityControllerIntegrationTests {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private FacilityRepository facilityRepository;

    @Autowired
    private EntityManager entityManager;

    private MockMvc mockMvc;
    private String region;
    private String district;
    private Facility library;
    private Facility secondLibrary;
    private Facility park;
    private Facility otherRegionLibrary;
    private long totalFacilities;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        String suffix = UUID.randomUUID().toString();
        region = "TEST_REGION_" + suffix;
        district = "TEST_DISTRICT_" + suffix;
        library = saveFacility("Library", region, district, FacilityType.LIBRARY);
        secondLibrary = saveFacility("Second Library", region, "OTHER_" + district,
                FacilityType.LIBRARY);
        park = saveFacility("Park", region, district, FacilityType.PARK);
        otherRegionLibrary = saveFacility("Other Region Library", "OTHER_" + region,
                district, FacilityType.LIBRARY);
        totalFacilities = facilityRepository.count();
        entityManager.clear();
    }

    @Test
    void getFacilityReturnsAllFieldsFromDatabase() throws Exception {
        Facility facility = saveFacility("Central Library", FacilityType.LIBRARY);
        entityManager.clear();

        mockMvc.perform(get("/api/facilities/{id}", facility.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(facility.getId()))
                .andExpect(jsonPath("$.name").value("Central Library"))
                .andExpect(jsonPath("$.region").value("Seoul"))
                .andExpect(jsonPath("$.district").value("Jongno-gu"))
                .andExpect(jsonPath("$.type").value("LIBRARY"))
                .andExpect(jsonPath("$.address").value("1 Test Road"))
                .andExpect(jsonPath("$.latitude").value(37.5729))
                .andExpect(jsonPath("$.longitude").value(126.9794))
                .andExpect(jsonPath("$.operatingStatus").value("OPERATING"));
    }

    @Test
    void getMissingFacilityReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/facilities/{id}", -1L))
                .andExpect(status().isNotFound());
    }

    @Test
    void getFacilitiesWithoutFiltersReturnsDefaultPage() throws Exception {
        mockMvc.perform(get("/api/facilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(Math.min(20L, totalFacilities)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(totalFacilities))
                .andExpect(jsonPath("$.totalPages").value((totalFacilities + 19) / 20));
    }

    @Test
    void getFacilitiesFiltersByRegion() throws Exception {
        mockMvc.perform(get("/api/facilities").param("region", region))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(
                        library.getId().intValue(), secondLibrary.getId().intValue(),
                        park.getId().intValue())))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void getFacilitiesFiltersByRegionAndType() throws Exception {
        mockMvc.perform(get("/api/facilities")
                        .param("region", region).param("type", "LIBRARY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(
                        library.getId().intValue(), secondLibrary.getId().intValue())))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void getFacilitiesFiltersByRegionDistrictAndType() throws Exception {
        mockMvc.perform(get("/api/facilities")
                        .param("region", region).param("district", district)
                        .param("type", "LIBRARY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(library.getId().intValue())))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getFacilitiesFiltersByDistrictOnly() throws Exception {
        mockMvc.perform(get("/api/facilities").param("district", district))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(
                        library.getId().intValue(), park.getId().intValue(),
                        otherRegionLibrary.getId().intValue())))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void getFacilitiesFiltersByTypeOnly() throws Exception {
        mockMvc.perform(get("/api/facilities").param("type", "LIBRARY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isNotEmpty())
                .andExpect(jsonPath("$.content[*].type", everyItem(is("LIBRARY"))));
    }

    @Test
    void getFacilitiesFiltersByRegionAndDistrict() throws Exception {
        mockMvc.perform(get("/api/facilities")
                        .param("region", region).param("district", district))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(
                        library.getId().intValue(), park.getId().intValue())))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void getFacilitiesFiltersByDistrictAndType() throws Exception {
        mockMvc.perform(get("/api/facilities")
                        .param("district", district).param("type", "LIBRARY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(
                        library.getId().intValue(), otherRegionLibrary.getId().intValue())))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void getFacilitiesReturnsConsecutivePagesWithMatchingTotals() throws Exception {
        mockMvc.perform(get("/api/facilities")
                        .param("region", region).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(
                        library.getId().intValue(), secondLibrary.getId().intValue())))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/facilities")
                        .param("region", region).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(park.getId().intValue())))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void getFacilitiesReturnsEmptyPageWhenNoFiltersMatch() throws Exception {
        mockMvc.perform(get("/api/facilities")
                        .param("region", region).param("type", "SPORTS_CENTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void getFacilitiesReturnsEmptyContentBeyondLastPage() throws Exception {
        mockMvc.perform(get("/api/facilities")
                        .param("region", region).param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void getFacilitiesRejectsUnknownFacilityType() throws Exception {
        mockMvc.perform(get("/api/facilities").param("type", "UNKNOWN"))
                .andExpect(status().isBadRequest());
    }

    private Facility saveFacility(String name, FacilityType type) {
        return saveFacility(name, "Seoul", "Jongno-gu", type);
    }

    private Facility saveFacility(String name, String facilityRegion, String facilityDistrict,
                                  FacilityType type) {
        return facilityRepository.saveAndFlush(new Facility(
                name, facilityRegion, facilityDistrict, type,
                "1 Test Road", 37.5729, 126.9794, OperatingStatus.OPERATING));
    }
}
