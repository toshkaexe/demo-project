package demo.lavka.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import demo.lavka.entity.Orders;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Orders, Long> {

    List<Orders> findByUserId(Long userId);

    /*
     * A page of the order list takes two queries. The first one pages over ids only, so LIMIT/OFFSET and the count
     * run in SQL. A fetch join of collections (items) can't be paged in SQL: Hibernate would load every order
     * and cut the page in memory (warning HHH90003004). The second query loads just these orders with their
     * customer, items and products in one go, otherwise each order costs extra queries (N+1).
     */

    // a customer's orders; sorting (newest first) comes with the Pageable
    @Query("select o.id from Orders o where o.user.id = :userId")
    Page<Long> findIdsByUserId(@Param("userId") Long userId, Pageable pageable);

    // for the warehouse: orders of every customer
    @Query("select o.id from Orders o")
    Page<Long> findAllIds(Pageable pageable);

    // the orders of one page, in no particular order: the caller puts them in the order of the ids
    @EntityGraph(attributePaths = {"user", "items", "items.product"})
    @Query("select o from Orders o where o.id in :ids")
    List<Orders> findWithItemsByIdIn(@Param("ids") Collection<Long> ids);

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

    // an order placed earlier with this Idempotency-Key, with items for the response; keys are per user
    @EntityGraph(attributePaths = {"user", "items", "items.product"})
    Optional<Orders> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    // an order of another user is "not found": the client can't tell whether it exists
    @EntityGraph(attributePaths = {"user", "items", "items.product"})
    Optional<Orders> findByIdAndUserId(Long id, Long userId);

}
