package demo.lavka.service;

import java.util.List;

import demo.lavka.entity.User;
import demo.lavka.entity.UserAddress;
import demo.lavka.model.AddressRequest;
import demo.lavka.model.AddressResponse;
import demo.lavka.repository.UserAddressRepository;
import demo.lavka.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Delivery addresses of the current user. Every user, whatever the role, works only with own addresses:
 * an address of another user is "not found" (404).
 * <p>
 * One address is <b>active</b>: the one orders go to. If the user has addresses, exactly one of them is active:
 * the first address becomes active by itself, and when the active address is deleted the oldest remaining one
 * takes over. Changes that affect this rule lock the user row, so parallel requests of one user run one by one.
 */
@Service
public class AddressService {

    /**
     * Enough for home, work and a couple of relatives; stops a client from filling the table.
     */
    public static final int MAX_ADDRESSES = 10;

    private static final Logger log = LoggerFactory.getLogger(AddressService.class);

    private final UserAddressRepository addressRepository;
    private final UserRepository userRepository;

    public AddressService(UserAddressRepository addressRepository, UserRepository userRepository) {
        this.addressRepository = addressRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> findAll(User actor) {
        return addressRepository.findByUserIdOrderByIdAsc(actor.getId()).stream()
                .map(AddressResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AddressResponse findById(User actor, Long id) {
        return AddressResponse.from(getOrThrow(actor, id));
    }

    @Transactional
    public AddressResponse create(User actor, AddressRequest request) {
        User owner = lockUser(actor);
        long count = addressRepository.countByUserId(owner.getId());
        if (count >= MAX_ADDRESSES) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Address limit reached: at most " + MAX_ADDRESSES + " addresses per user");
        }
        UserAddress address = new UserAddress(owner,
                required(request.postalCode()), required(request.country()), required(request.city()),
                required(request.street()), required(request.house()), optional(request.apartment()),
                required(request.recipientName()), optional(request.courierNote()));
        // the first address is the only one, so orders go there
        address.setActive(count == 0);
        addressRepository.save(address);
        log.info("Address created: id={} userId={} active={}", address.getId(), actor.getId(), address.isActive());
        return AddressResponse.from(address);
    }

    /**
     * Makes the address active; the previously active one stops being active. Repeating the call changes nothing.
     */
    @Transactional
    public AddressResponse activate(User actor, Long id) {
        lockUser(actor);
        UserAddress address = getOrThrow(actor, id);
        if (!address.isActive()) {
            addressRepository.findByUserIdAndActiveTrue(actor.getId()).ifPresent(current -> current.setActive(false));
            // the old one must reach the table first: the unique index allows one active address per user
            addressRepository.flush();
            address.setActive(true);
            // and the new one now, so the response carries updated_at set by @PreUpdate
            addressRepository.flush();
            log.info("Address activated: id={} userId={}", id, actor.getId());
        }
        return AddressResponse.from(address);
    }

    /**
     * Replaces all fields of the address (PUT semantics).
     */
    @Transactional
    public AddressResponse update(User actor, Long id, AddressRequest request) {
        UserAddress address = getOrThrow(actor, id);
        address.update(
                required(request.postalCode()), required(request.country()), required(request.city()),
                required(request.street()), required(request.house()), optional(request.apartment()),
                required(request.recipientName()), optional(request.courierNote()));
        // flush so the response carries the updated_at set by @PreUpdate
        addressRepository.flush();
        log.info("Address updated: id={} userId={}", id, actor.getId());
        return AddressResponse.from(address);
    }

    /**
     * If the deleted address was active, the oldest remaining one becomes active.
     */
    @Transactional
    public void delete(User actor, Long id) {
        lockUser(actor);
        UserAddress address = getOrThrow(actor, id);
        boolean wasActive = address.isActive();
        addressRepository.delete(address);
        addressRepository.flush();
        log.info("Address deleted: id={} userId={}", id, actor.getId());
        if (wasActive) {
            addressRepository.findFirstByUserIdOrderByIdAsc(actor.getId()).ifPresent(next -> {
                next.setActive(true);
                log.info("Address activated: id={} userId={} (active one was deleted)", next.getId(), actor.getId());
            });
        }
    }

    private User lockUser(User actor) {
        return userRepository.findByIdForUpdate(actor.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private UserAddress getOrThrow(User actor, Long id) {
        return addressRepository.findByIdAndUserId(id, actor.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address " + id + " not found"));
    }

    /**
     * The request is already checked by Bean Validation in the controller, this guards calls that skip it:
     * the table rejects blank values anyway, but with a 500.
     */
    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("required address field is blank");
        }
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

}
