package demo.lavka.repository;

import java.util.List;
import java.util.Optional;

import demo.lavka.entity.Orders;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Orders, Long> {

    List<Orders> findByUserId(Long userId);

    // newest first; items and their products in the same query, otherwise each order costs extra queries (N+1)
    @EntityGraph(attributePaths = {"items", "items.product"})
    List<Orders> findByUserIdOrderByIdDesc(Long userId);

    // for the warehouse: all orders, newest first
    @EntityGraph(attributePaths = {"items", "items.product"})
    List<Orders> findAllByOrderByIdDesc();

    // for the warehouse: any order
    @EntityGraph(attributePaths = {"items", "items.product"})
    Optional<Orders> findWithItemsById(Long id);

    // an order of another user is "not found": the client can't tell whether it exists
    @EntityGraph(attributePaths = {"items", "items.product"})
    Optional<Orders> findByIdAndUserId(Long id, Long userId);

}
