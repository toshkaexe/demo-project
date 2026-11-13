package demo.lavka.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Order statuses and the only allowed transitions between them:
 * <pre>
 * CREATED ──► PROCESSING ──► READY ──► COMPLETED
 *    │
 *    └──► CANCELLED
 * </pre>
 * An order can be cancelled only before assembling starts. COMPLETED and CANCELLED are final.
 */
public enum OrderStatus {
    /** Заказ оформлен */
    CREATED,
    /** Заказ собирается */
    PROCESSING,
    /** Заказ собран */
    READY,
    /** Заказ выполнен / доставлен */
    COMPLETED,
    /** Заказ отменён */
    CANCELLED;

    /**
     * Statuses this one may change to. A switch and not a field: an enum constructor can't refer to constants
     * declared below it.
     */
    public Set<OrderStatus> nextStatuses() {
        return switch (this) {
            case CREATED -> EnumSet.of(PROCESSING, CANCELLED);
            case PROCESSING -> EnumSet.of(READY);
            case READY -> EnumSet.of(COMPLETED);
            case COMPLETED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canChangeTo(OrderStatus next) {
        return nextStatuses().contains(next);
    }

}
