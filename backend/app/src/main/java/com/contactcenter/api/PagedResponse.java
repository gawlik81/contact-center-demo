package com.contactcenter.api;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Generyczny wrapper paginowanej odpowiedzi REST API.
 *
 * <p>Zapewnia pełne metadane paginacji wraz z zawartością strony.
 * Używany zamiast {@code Page.getContent()} który odrzuca metadane takie jak
 * {@code totalElements}, {@code totalPages} itp.
 *
 * <p>Format JSON:
 * <pre>
 * {
 *   "content": [...],
 *   "page": 0,
 *   "size": 20,
 *   "totalElements": 150,
 *   "totalPages": 8,
 *   "first": true,
 *   "last": false
 * }
 * </pre>
 *
 * @param <T> typ elementów strony
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    /**
     * Tworzy {@code PagedResponse} ze Spring Data {@link Page}.
     *
     * @param page strona wynikowa z repozytorium
     * @param <T>  typ elementów
     * @return opakowana odpowiedź z metadanymi paginacji
     */
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }
}
