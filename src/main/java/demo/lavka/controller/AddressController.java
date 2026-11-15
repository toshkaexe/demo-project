package demo.lavka.controller;

import java.net.URI;
import java.util.List;

import demo.lavka.auth.AuthService;
import demo.lavka.auth.BearerToken;
import demo.lavka.entity.User;
import demo.lavka.model.AddressRequest;
import demo.lavka.model.AddressResponse;
import demo.lavka.service.AddressService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Delivery addresses of the user the access token belongs to (see {@link AddressService}).
 */
@RestController
@RequestMapping("/users/me/addresses")
public class AddressController {

    private final AuthService authService;
    private final AddressService addressService;

    public AddressController(AuthService authService, AddressService addressService) {
        this.authService = authService;
        this.addressService = addressService;
    }

    @GetMapping
    public List<AddressResponse> findAll(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return addressService.findAll(user(authorization));
    }

    @GetMapping("/{id}")
    public AddressResponse findById(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                    @PathVariable Long id) {
        return addressService.findById(user(authorization), id);
    }

    /**
     * 201 Created with a {@code Location: /users/me/addresses/{id}} header and the created address in the body.
     */
    @PostMapping
    public ResponseEntity<AddressResponse> create(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                  @Valid @RequestBody AddressRequest request) {
        AddressResponse created = addressService.create(user(authorization), request);
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
    public AddressResponse update(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                  @PathVariable Long id,
                                  @Valid @RequestBody AddressRequest request) {
        return addressService.update(user(authorization), id, request);
    }

    /**
     * Makes the address active (orders go there); the previously active one stops being active. No body.
     */
    @PutMapping("/{id}/active")
    public AddressResponse activate(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                    @PathVariable Long id) {
        return addressService.activate(user(authorization), id);
    }

    /**
     * If the address was active, the oldest remaining one becomes active.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                       @PathVariable Long id) {
        addressService.delete(user(authorization), id);
    }

    private User user(String authorization) {
        return authService.authenticate(BearerToken.from(authorization));
    }

}
