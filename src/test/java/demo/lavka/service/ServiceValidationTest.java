package demo.lavka.service;

import java.math.BigDecimal;
import java.util.Optional;

import demo.lavka.entity.User;
import demo.lavka.model.AddressRequest;
import demo.lavka.model.ProductRequest;
import demo.lavka.repository.ProductRepository;
import demo.lavka.repository.UserAddressRepository;
import demo.lavka.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Calls that skip the controller's Bean Validation: a bad value must be a 400 with the reason,
 * not an NPE or IllegalArgumentException that the client sees as 500, and nothing is saved.
 */
@DisplayName("Проверки в сервисах без контроллера: 400 с причиной, а не 500")
class ServiceValidationTest {

    private static final BigDecimal PRICE = new BigDecimal("89.90");

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductService productService = new ProductService(productRepository);

    @Test
    @DisplayName("Товар: null в name и quantity не роняет trim() и распаковку")
    void productNullsAreBadRequest() {
        assertBadRequest(() -> productService.create(new ProductRequest(null, 1, PRICE)), "name must not be blank");
        assertBadRequest(() -> productService.create(new ProductRequest("  ", 1, PRICE)), "name must not be blank");
        assertBadRequest(() -> productService.create(new ProductRequest("Milk", null, PRICE)), "quantity");
        assertBadRequest(() -> productService.create(new ProductRequest("Milk", 1, null)), "price");
        assertBadRequest(() -> productService.update(1, new ProductRequest(null, 1, PRICE)), "name must not be blank");
        verify(productRepository, never()).save(any());
        verify(productRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("Товар: значения за пределами колонок — 400, а не ошибка базы")
    void productOutOfRangeIsBadRequest() {
        assertBadRequest(() -> productService.create(new ProductRequest("Milk", -1, PRICE)), "quantity must be >= 0");
        assertBadRequest(() -> productService.create(new ProductRequest("Milk", 1, new BigDecimal("-0.01"))),
                "price must be >= 0");
        assertBadRequest(() -> productService.create(new ProductRequest("Milk", 1, new BigDecimal("1.999"))),
                "2 after");
        assertBadRequest(() -> productService.create(new ProductRequest("Milk", 1, new BigDecimal("123456789"))),
                "8 digits");
        assertBadRequest(() -> productService.create(new ProductRequest("x".repeat(256), 1, PRICE)),
                "at most 255");
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("Адрес: пустое или длинное поле — 400 с именем поля")
    void addressFieldsAreBadRequest() {
        UserRepository userRepository = mock(UserRepository.class);
        UserAddressRepository addressRepository = mock(UserAddressRepository.class);
        User user = new User("anton@example.com", "hash");
        when(userRepository.findByIdForUpdate(any())).thenReturn(Optional.of(user));
        AddressService addressService = new AddressService(addressRepository, userRepository);

        assertBadRequest(() -> addressService.create(user, address(null, "Тверская")), "city must not be blank");
        assertBadRequest(() -> addressService.create(user, address("Москва", "x".repeat(301))),
                "street must be at most 300");
        verify(addressRepository, never()).save(any());
    }

    private static AddressRequest address(String city, String street) {
        return new AddressRequest("101000", "Россия", city, street, "12", null, "Антон Иванов", null);
    }

    private static void assertBadRequest(Executable call, String reason) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call);
        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getReason()).contains(reason);
    }

}
