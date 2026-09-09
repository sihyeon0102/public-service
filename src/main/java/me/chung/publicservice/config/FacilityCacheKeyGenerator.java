package me.chung.publicservice.config;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Collectors;
import me.chung.publicservice.domain.FacilityType;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component("facilitySearchKeyGenerator")
public class FacilityCacheKeyGenerator implements KeyGenerator {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    @Override
    public Object generate(Object target, Method method, Object... params) {
        if (params.length != 4 || !(params[3] instanceof Pageable pageable)) {
            throw new IllegalArgumentException("Facility search cache key requires its four search arguments");
        }

        return "v1"
                + "|region=" + encode((String) params[0])
                + "|district=" + encode((String) params[1])
                + "|type=" + encodeType((FacilityType) params[2])
                + "|paged=" + pageable.isPaged()
                + "|page=" + (pageable.isPaged() ? pageable.getPageNumber() : 0)
                + "|size=" + (pageable.isPaged() ? pageable.getPageSize() : 0)
                + "|sort=" + encodeSort(pageable.getSort());
    }

    private String encodeSort(Sort sort) {
        if (sort.isUnsorted()) {
            return "~";
        }
        return sort.stream()
                .map(order -> encode(order.getProperty())
                        + ":" + order.getDirection()
                        + ":" + order.getNullHandling()
                        + ":" + order.isIgnoreCase())
                .collect(Collectors.joining(","));
    }

    private String encodeType(FacilityType type) {
        return type == null ? "~" : encode(type.name());
    }

    private String encode(String value) {
        return value == null ? "~"
                : ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
