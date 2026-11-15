package demo.lavka.entity;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transition table without a database: a pure check of {@link OrderStatus#canChangeTo}.
 */
@DisplayName("OrderStatus: разрешённые переходы")
class OrderStatusTest {

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource({
            // the table from the task
            "CREATED,    PROCESSING, true",
            "CREATED,    CANCELLED,  true",
            "PROCESSING, READY,      true",
            "PROCESSING, CANCELLED,  false",
            "READY,      COMPLETED,  true",
            "READY,      CANCELLED,  false",
            "COMPLETED,  CREATED,    false",
            "CANCELLED,  PROCESSING, false",
            // skipping a step and going back are not allowed either
            "CREATED,    READY,      false",
            "CREATED,    COMPLETED,  false",
            "PROCESSING, COMPLETED,  false",
            "PROCESSING, CREATED,    false",
            "READY,      PROCESSING, false",
    })
    void transitionTable(OrderStatus current, OrderStatus next, boolean allowed) {
        assertThat(current.canChangeTo(next)).isEqualTo(allowed);
    }

    // all 25 pairs: exactly these four are allowed, every other pair, including "the same status", is not
    @Test
    void onlyFourTransitionsAreAllowed() {
        Set<String> allowed = Set.of(
                "CREATED->PROCESSING", "CREATED->CANCELLED", "PROCESSING->READY", "READY->COMPLETED");

        for (OrderStatus current : OrderStatus.values()) {
            for (OrderStatus next : OrderStatus.values()) {
                String pair = current + "->" + next;
                assertThat(current.canChangeTo(next)).as(pair).isEqualTo(allowed.contains(pair));
            }
        }
    }

    @Test
    void completedAndCancelledAreFinal() {
        assertThat(OrderStatus.COMPLETED.nextStatuses()).isEmpty();
        assertThat(OrderStatus.CANCELLED.nextStatuses()).isEmpty();
    }

    @Test
    void orderCanBeCancelledOnlyBeforeAssemblingStarts() {
        Set<OrderStatus> cancellableFrom = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            if (status.canChangeTo(OrderStatus.CANCELLED)) {
                cancellableFrom.add(status);
            }
        }
        assertThat(cancellableFrom).containsExactly(OrderStatus.CREATED);
    }

}
