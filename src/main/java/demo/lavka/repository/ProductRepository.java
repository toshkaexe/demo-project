package demo.lavka.repository;

import java.util.Collection;
import java.util.List;

import demo.lavka.entity.Products;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Products, Integer> {

    /**
     * {@code SELECT ... FOR UPDATE}: other transactions wait until ours commits, so two orders can't sell
     * the same last item. Rows are locked in id order, so two orders with the same products can't deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Products p where p.id in :ids order by p.id")
    List<Products> findAllByIdForUpdate(@Param("ids") Collection<Integer> ids);

}
