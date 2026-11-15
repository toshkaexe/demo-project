package demo.lavka.repository;

import java.util.List;
import java.util.Optional;

import demo.lavka.entity.UserAddress;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {

    // oldest first: the order in which the user added them
    List<UserAddress> findByUserIdOrderByIdAsc(Long userId);

    // an address of another user is "not found": the client can't tell whether it exists
    Optional<UserAddress> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    Optional<UserAddress> findByUserIdAndActiveTrue(Long userId);

    // takes over when the active address is deleted
    Optional<UserAddress> findFirstByUserIdOrderByIdAsc(Long userId);

}
