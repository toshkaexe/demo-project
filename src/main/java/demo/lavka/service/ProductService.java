package demo.lavka.service;

import java.math.BigDecimal;
import java.util.List;

import demo.lavka.entity.Products;
import demo.lavka.model.ProductRequest;
import demo.lavka.model.ProductResponse;
import demo.lavka.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    // VARCHAR(255) of products.name
    static final int MAX_NAME_LENGTH = 255;

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> findAll() {
        return productRepository.findAll(Sort.by("id")).stream()
                .map(ProductResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse findById(Integer id) {
        return ProductResponse.from(getOrThrow(id));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        String name = validate(request);
        Products product = productRepository.save(Products.builder()
                .name(name)
                .quantity(request.quantity())
                .price(request.price())
                .build());
        log.info("Product created: id={} name={}", product.getId(), product.getName());
        return ProductResponse.from(product);
    }

    /**
     * Pessimistic lock: the row is read {@code FOR UPDATE} and stays locked until commit. If an order or a cancel
     * of this product is in progress, the edit waits for it; orders placed meanwhile wait for the edit.
     * Lock order is the same as in orders (one product row), so there is no deadlock.
     */
    @Transactional
    public ProductResponse update(Integer id, ProductRequest request) {
        String name = validate(request);
        Products product = productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product " + id + " not found"));
        // replaces all fields at once (PUT semantics)
        product.setName(name);
        product.setQuantity(request.quantity());
        product.setPrice(request.price());
        log.info("Product updated: id={}", id);
        // no save(): the product is managed inside this transaction, dirty checking writes the changes on commit
        return ProductResponse.from(product);
    }

    @Transactional
    public void delete(Integer id) {
        Products product = getOrThrow(id);
        try {
            productRepository.delete(product);
            productRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // order_items.product_id is ON DELETE RESTRICT: old orders must keep their products
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Product " + id + " is used in orders and can't be deleted");
        }
        log.info("Product deleted: id={}", id);
    }

    /**
     * Same rules for create and update, the same as Bean Validation on {@link ProductRequest} and the columns
     * of {@code products}. The controller has already checked the body, this guards calls that skip it.
     * Every check runs before the value is used (no {@code trim()} or unboxing of a null), and a bad value is
     * a 400 with the reason, not an exception that the client sees as 500.
     *
     * @return the trimmed name
     */
    private static String validate(ProductRequest request) {
        String name = request.name();
        if (name == null || name.isBlank()) {
            throw badRequest("name must not be blank");
        }
        name = name.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            throw badRequest("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        Integer quantity = request.quantity();
        if (quantity == null || quantity < 0) {
            throw badRequest("quantity must be >= 0, got " + quantity);
        }
        BigDecimal price = request.price();
        if (price == null || price.signum() < 0) {
            throw badRequest("price must be >= 0, got " + price);
        }
        // NUMERIC(10, 2): at most 8 digits before the point and 2 after
        if (price.scale() > 2 || price.precision() - price.scale() > 8) {
            throw badRequest("price must have at most 8 digits before the point and 2 after, got " + price);
        }
        return name;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private Products getOrThrow(Integer id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product " + id + " not found"));
    }

}
