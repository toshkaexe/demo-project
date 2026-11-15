package demo.lavka.model;

import java.math.BigDecimal;

import demo.lavka.entity.Products;

public record ProductResponse(Integer id, String name, int quantity, BigDecimal price) {

    public static ProductResponse from(Products product) {
        return new ProductResponse(product.getId(), product.getName(), product.getQuantity(), product.getPrice());
    }

}
