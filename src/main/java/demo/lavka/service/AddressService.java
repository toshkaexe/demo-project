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
                required("postal_code", request.postalCode(), 20), required("country", request.country(), 100),
                required("city", request.city(), 100), required("street", request.street(), 300),
                required("house", request.house(), 20), optional("apartment", request.apartment(), 20),
                required("recipient_name", request.recipientName(), 200),
                optional("courier_note", request.courierNote(), 500));
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
                required("postal_code", request.postalCode(), 20), required("country", request.country(), 100),
                required("city", request.city(), 100), required("street", request.street(), 300),
                required("house", request.house(), 20), optional("apartment", request.apartment(), 20),
                required("recipient_name", request.recipientName(), 200),
                optional("courier_note", request.courierNote(), 500));
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
     * The request is already checked by Bean Validation in the controller, this guards calls that skip it.
     * The limits are the columns of {@code user_addresses}: without the check the table rejects the value anyway,
     * but the client gets a 500 instead of a 400 with the field name.
     */
    private static String required(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " must not be blank");
        }
        return checkLength(field, value.trim(), maxLength);
    }

    private static String optional(String field, String value, int maxLength) {
        return value == null || value.isBlank() ? null : checkLength(field, value.trim(), maxLength);
    }

    private static String checkLength(String field, String value, int maxLength) {
        if (value.length() > maxLength) {
            throw badRequest(field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

}
