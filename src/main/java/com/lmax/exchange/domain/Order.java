package com.lmax.exchange.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a trading order in the exchange.
 * Immutable to ensure thread safety and enable event sourcing.
 */
public class Order {
    private final long orderId;
    private final String userId;
    private final String symbol;
    private final OrderType type;
    private final OrderSide side;
    private final BigDecimal price;
    private final long quantity;
    private final long remainingQuantity;
    private final OrderStatus status;
    private final Instant timestamp;
    private final TimeInForce timeInForce;

    public Order(long orderId, String userId, String symbol, OrderType type, 
                 OrderSide side, BigDecimal price, long quantity, TimeInForce timeInForce) {
        this.orderId = orderId;
        this.userId = userId;
        this.symbol = symbol;
        this.type = type;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.remainingQuantity = quantity;
        this.status = OrderStatus.PENDING;
        this.timestamp = Instant.now();
        this.timeInForce = timeInForce;
    }

    private Order(long orderId, String userId, String symbol, OrderType type,
                  OrderSide side, BigDecimal price, long quantity, long remainingQuantity,
                  OrderStatus status, Instant timestamp, TimeInForce timeInForce) {
        this.orderId = orderId;
        this.userId = userId;
        this.symbol = symbol;
        this.type = type;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.remainingQuantity = remainingQuantity;
        this.status = status;
        this.timestamp = timestamp;
        this.timeInForce = timeInForce;
    }

    // Immutable update methods
    public Order withStatus(OrderStatus status) {
        return new Order(orderId, userId, symbol, type, side, price, quantity, 
                        remainingQuantity, status, timestamp, timeInForce);
    }

    public Order withRemainingQuantity(long remainingQuantity) {
        return new Order(orderId, userId, symbol, type, side, price, quantity, 
                        remainingQuantity, status, timestamp, timeInForce);
    }

    public Order fillQuantity(long fillQuantity) {
        long newRemaining = Math.max(0, remainingQuantity - fillQuantity);
        OrderStatus newStatus = newRemaining == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        return new Order(orderId, userId, symbol, type, side, price, quantity, 
                        newRemaining, newStatus, timestamp, timeInForce);
    }

    // Getters
    public long getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public String getSymbol() { return symbol; }
    public OrderType getType() { return type; }
    public OrderSide getSide() { return side; }
    public BigDecimal getPrice() { return price; }
    public long getQuantity() { return quantity; }
    public long getRemainingQuantity() { return remainingQuantity; }
    public OrderStatus getStatus() { return status; }
    public Instant getTimestamp() { return timestamp; }
    public TimeInForce getTimeInForce() { return timeInForce; }

    public boolean isActive() {
        return status == OrderStatus.PENDING || status == OrderStatus.PARTIALLY_FILLED;
    }

    public long getFilledQuantity() {
        return quantity - remainingQuantity;
    }

    @Override
    public String toString() {
        return String.format("Order{id=%d, user=%s, symbol=%s, type=%s, side=%s, price=%s, qty=%d, remaining=%d, status=%s}",
                orderId, userId, symbol, type, side, price, quantity, remainingQuantity, status);
    }

    public enum OrderType {
        MARKET, LIMIT, STOP, STOP_LIMIT
    }

    public enum OrderSide {
        BUY, SELL
    }

    public enum OrderStatus {
        PENDING, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED
    }

    public enum TimeInForce {
        GTC, // Good Till Cancelled
        IOC, // Immediate Or Cancel
        FOK  // Fill Or Kill
    }
} 