package com.suretyseven.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * A small, stable JSON shape for paginated results, instead of serializing
 * Spring Data's Page directly (which exposes a lot of internal Pageable/
 * Sort machinery that's not a contract we want to commit to for API
 * clients).
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    public static <S, T> PagedResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return from(page.map(mapper));
    }
}
