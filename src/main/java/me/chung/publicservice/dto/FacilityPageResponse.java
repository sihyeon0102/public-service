package me.chung.publicservice.dto;

import java.io.Serializable;
import java.util.List;
import org.springframework.data.domain.Page;

public record FacilityPageResponse(
        List<FacilityResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) implements Serializable {
    public static FacilityPageResponse from(Page<FacilityResponse> facilities) {
        return new FacilityPageResponse(
                facilities.getContent(),
                facilities.getNumber(),
                facilities.getSize(),
                facilities.getTotalElements(),
                facilities.getTotalPages()
        );
    }
}
