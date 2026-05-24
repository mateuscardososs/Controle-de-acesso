package br.com.sport.accesscontrol.events;

import org.springframework.data.domain.Page;

import java.util.List;

public record AccessEventPageResponse(
        List<AccessEventResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    static AccessEventPageResponse from(Page<AccessEventResponse> page) {
        return new AccessEventPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
