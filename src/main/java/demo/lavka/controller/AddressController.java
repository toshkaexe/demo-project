package demo.lavka.controller;

import java.net.URI;
import java.util.List;

import demo.lavka.auth.Authenticated;
import demo.lavka.auth.CurrentUser;
import demo.lavka.entity.User;
import demo.lavka.model.AddressRequest;
import demo.lavka.model.AddressResponse;
import demo.lavka.service.AddressService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Delivery addresses of the user the access token belongs to (see {@link AddressService}).
 */
@RestController
@Authenticated
@RequestMapping("/users/me/addresses")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    public List<AddressResponse> findAll(@CurrentUser User user) {
        return addressService.findAll(user);
    }

    @GetMapping("/{id}")
    public AddressResponse findById(@CurrentUser User user, @PathVariable Long id) {
        return addressService.findById(user, id);
    }

    /**
     * 201 Created with a {@code Location: /users/me/addresses/{id}} header and the created address in the body.
     */
    @PostMapping
    public ResponseEntity<AddressResponse> create(@CurrentUser User user,
                                                  @Valid @RequestBody AddressRequest request) {
        AddressResponse created = addressService.create(user, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Replaces all fields of the address (PUT semantics).
     */
    @PutMapping("/{id}")
    public AddressResponse update(@CurrentUser User user,
                                  @PathVariable Long id,
                                  @Valid @RequestBody AddressRequest request) {
        return addressService.update(user, id, request);
    }

    /**
     * Makes the address active (orders go there); the previously active one stops being active. No body.
     */
    @PutMapping("/{id}/active")
    public AddressResponse activate(@CurrentUser User user, @PathVariable Long id) {
        return addressService.activate(user, id);
    }

    /**
     * If the address was active, the oldest remaining one becomes active.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser User user, @PathVariable Long id) {
        addressService.delete(user, id);
    }

}
