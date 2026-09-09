package me.chung.publicservice.config;

import java.lang.reflect.Method;
import me.chung.publicservice.domain.FacilityType;
import me.chung.publicservice.service.FacilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

class FacilityCacheKeyGeneratorTests {

    private FacilityCacheKeyGenerator keyGenerator;
    private Method searchMethod;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        keyGenerator = new FacilityCacheKeyGenerator();
        searchMethod = FacilityService.class.getMethod("getFacilities",
                String.class, String.class, FacilityType.class,
                org.springframework.data.domain.Pageable.class);
    }

    @Test
    void equalSearchesGenerateTheSameKey() {
        Object first = generate("SEOUL", null, FacilityType.LIBRARY,
                PageRequest.of(0, 20, Sort.by("id")));
        Object second = generate("SEOUL", null, FacilityType.LIBRARY,
                PageRequest.of(0, 20, Sort.by("id")));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void searchConditionsDoNotCollide() {
        Object seoulLibrary = generate("SEOUL", null, FacilityType.LIBRARY,
                PageRequest.of(0, 20, Sort.by("id")));
        Object gwangju = generate("GWANGJU", null, null,
                PageRequest.of(0, 20, Sort.by("id")));
        Object delimiterValue = generate("SEOUL|district=GWANGJU", null, null,
                PageRequest.of(0, 20, Sort.by("id")));

        assertThat(seoulLibrary).isNotEqualTo(gwangju).isNotEqualTo(delimiterValue);
        assertThat(gwangju).isNotEqualTo(delimiterValue);
    }

    @Test
    void pageSizeAndSortArePartOfTheKey() {
        Object base = generate("SEOUL", null, FacilityType.LIBRARY,
                PageRequest.of(0, 20, Sort.by("id")));
        Object nextPage = generate("SEOUL", null, FacilityType.LIBRARY,
                PageRequest.of(1, 20, Sort.by("id")));
        Object differentSize = generate("SEOUL", null, FacilityType.LIBRARY,
                PageRequest.of(0, 50, Sort.by("id")));
        Object descending = generate("SEOUL", null, FacilityType.LIBRARY,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id")));

        assertThat(base).isNotEqualTo(nextPage)
                .isNotEqualTo(differentSize)
                .isNotEqualTo(descending);
    }

    private Object generate(String region, String district, FacilityType type,
                            org.springframework.data.domain.Pageable pageable) {
        return keyGenerator.generate(this, searchMethod, region, district, type, pageable);
    }
}
