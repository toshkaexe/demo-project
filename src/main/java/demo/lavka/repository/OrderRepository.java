package demo.lavka.repository;

import java.util.List;
import java.util.Optional;

import demo.lavka.entity.Orders;
import org.springframework.data.jpa.repository.EntityGraph;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Orders, Long> {

    List<Orders> findByUserId(Long userId);

    // newest first; the customer, items and their products in the same query, otherwise each order costs extra queries (N+1)
    @EntityGraph(attributePaths = {"user", "items", "items.product"})
    List<Orders> findByUserIdOrderByIdDesc(Long userId);

    // for the warehouse: all orders, newest first
    @EntityGraph(attributePaths = {"user", "items", "items.product"})
    List<Orders> findAllByOrderByIdDesc();

    // for the warehouse: any order
    @EntityGraph(attributePaths = {"user", "items", "items.product"})
    Optional<Orders> findWithItemsById(Long id);

    /**
     * {@code SELECT ... FOR UPDATE} on the order row, for status changes: a second change of the same order waits
     * until the first one commits and then reads the new status. Without it two cancels both see READY and both
     * return the items to stock.
     * <p>
     * No entity graph here: PostgreSQL can't lock the nullable side of the outer join that fetching items needs.
     * Items are loaded after the lock, in the same transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Orders o where o.id = :id")
    Optional<Orders> findByIdForUpdate(@Param("id") Long id);

    /**
     * Same lock, only on the customer's own order. The owner is part of the {@code WHERE}, so PostgreSQL never
     * locks someone else's row: a customer can't make the warehouse wait on a foreign order, and a foreign order
     * answers 404 at once, whether it is locked right now or not.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Orders o where o.id = :id and o.user.id = :userId")
    Optional<Orders> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    // an order of another user is "not found": the client can't tell whether it exists
    @EntityGraph(attributePaths = {"user", "items", "items.product"})
    Optional<Orders> findByIdAndUserId(Long id, Long userId);

}
