package demo.lavka.entity;

public enum OrderStatus {
    /** Заказ оформлен */
    CREATED,
    /** Заказ собирается */
    PROCESSING,
    /** Заказ собран */
    READY,
    /** Заказ выполнен / доставлен */
    COMPLETED,
    /** Заказ отменён */
    CANCELLED
}
