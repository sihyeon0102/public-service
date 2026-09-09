package me.chung.publicservice.service;

import me.chung.publicservice.domain.Facility;
import me.chung.publicservice.domain.FacilityType;
import me.chung.publicservice.dto.FacilityPageResponse;
import me.chung.publicservice.repository.FacilityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
class FacilityCacheIntegrationTests {

    @Autowired
    private FacilityService facilityService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private FacilityRepository facilityRepository;

    private final Pageable firstPage = PageRequest.of(0, 20, Sort.by("id"));

    @BeforeEach
    void clearCacheAndInteractions() {
        facilitySearchCache().clear();
        clearInvocations(facilityRepository);
    }

    @AfterEach
    void clearCache() {
        facilitySearchCache().clear();
    }

    @Test
    void repeatedSearchUsesRedisAfterTheFirstDatabaseLookup() {
        FacilityPageResponse first = facilityService.getFacilities(
                "SEOUL", null, FacilityType.LIBRARY, firstPage);
        assertThat(cacheManager).isInstanceOf(RedisCacheManager.class);
        Set<String> keys = redisTemplate.keys("facilitySearch::*");
        assertThat(keys).hasSize(1);
        Long ttlSeconds = redisTemplate.getExpire(keys.iterator().next(), TimeUnit.SECONDS);
        assertThat(ttlSeconds).isPositive().isLessThanOrEqualTo(300);
        FacilityPageResponse second = facilityService.getFacilities(
                "SEOUL", null, FacilityType.LIBRARY, firstPage);

        assertThat(first).isEqualTo(second);
        assertThat(first.totalElements()).isEqualTo(50_000);
        verify(facilityRepository, times(1)).findAll(
                org.mockito.ArgumentMatchers.<Specification<Facility>>any(), any(Pageable.class));
    }

    @Test
    void differentSearchesUseDifferentEntries() {
        FacilityPageResponse seoul = facilityService.getFacilities(
                "SEOUL", null, FacilityType.LIBRARY, firstPage);
        FacilityPageResponse gwangju = facilityService.getFacilities(
                "GWANGJU", null, null, firstPage);
        FacilityPageResponse seoulHit = facilityService.getFacilities(
                "SEOUL", null, FacilityType.LIBRARY, firstPage);
        FacilityPageResponse gwangjuHit = facilityService.getFacilities(
                "GWANGJU", null, null, firstPage);

        assertThat(seoul.totalElements()).isEqualTo(50_000);
        assertThat(gwangju.totalElements()).isEqualTo(40_000);
        assertThat(seoulHit).isEqualTo(seoul);
        assertThat(gwangjuHit).isEqualTo(gwangju);
        assertThat(redisTemplate.keys("facilitySearch::*")).hasSize(2);
        verify(facilityRepository, times(2)).findAll(
                org.mockito.ArgumentMatchers.<Specification<Facility>>any(), any(Pageable.class));
    }

    private Cache facilitySearchCache() {
        Cache cache = cacheManager.getCache("facilitySearch");
        assertThat(cache).isNotNull();
        return cache;
    }
}
