package demo.lavka.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

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
        String name = request.name().trim();
        validate(name, request.quantity(), request.price());
        Products product = productRepository.save(Products.builder()
                .name(name)
                .quantity(request.quantity())
                .price(request.price())
                .build());
        log.info("Product created: id={} name={}", product.getId(), product.getName());
        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse update(Integer id, ProductRequest request) {
        String name = request.name().trim();
        validate(name, request.quantity(), request.price());
        Products product = getOrThrow(id);
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
     * Same rules for create and update. The request is already checked by Bean Validation in the controller,
     * this guards calls from other services that skip it.
     */
    private static void validate(String name, int quantity, BigDecimal price) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be >= 0, got " + quantity);
        }
        Objects.requireNonNull(price, "price must not be null");
        if (price.signum() < 0) {
            throw new IllegalArgumentException("price must be >= 0, got " + price);
        }
    }

    private Products getOrThrow(Integer id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product " + id + " not found"));
    }

}
