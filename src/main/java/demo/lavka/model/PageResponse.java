package demo.lavka.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.domain.Page;

/**
 * One page of a list: the rows and where this page is. {@code page} starts at 0.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        @JsonProperty("total_elements") long totalElements,
        @JsonProperty("total_pages") int totalPages) {

    /**
     * @param page    the page that was queried (of ids, for example)
     * @param content its rows, already converted to DTOs, in the same order
     */
    public static <T> PageResponse<T> of(Page<?> page, List<T> content) {
        return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

}
