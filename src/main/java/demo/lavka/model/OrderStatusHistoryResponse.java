package demo.lavka.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import demo.lavka.entity.OrderStatus;
import demo.lavka.entity.OrderStatusHistory;

public record OrderStatusHistoryResponse(
        @JsonProperty("from_status") OrderStatus fromStatus,
        @JsonProperty("to_status") OrderStatus toStatus,
        @JsonProperty("changed_at") Instant changedAt,
        @JsonProperty("changed_by") Long changedBy) {

    public static OrderStatusHistoryResponse from(OrderStatusHistory entry) {
        // getId() on a lazy proxy doesn't hit the database
        Long changedBy = entry.getChangedBy() == null ? null : entry.getChangedBy().getId();
        return new OrderStatusHistoryResponse(entry.getFromStatus(), entry.getToStatus(), entry.getChangedAt(), changedBy);
    }

}
