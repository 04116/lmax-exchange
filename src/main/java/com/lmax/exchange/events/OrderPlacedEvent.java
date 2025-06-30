package com.lmax.exchange.events;

import com.lmax.exchange.domain.Order;
import java.time.Instant;

/**
 * Event fired when an order is placed in the exchange.
 */
public class OrderPlacedEvent implements Event {
    private final long sequenceId;
    private final Instant timestamp;
    private final Order order;

    public OrderPlacedEvent(long sequenceId, Order order) {
        this.sequenceId = sequenceId;
        this.timestamp = Instant.now();
        this.order = order;
    }

    @Override
    public long getSequenceId() {
        return sequenceId;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String getEventType() {
        return "ORDER_PLACED";
    }

    public Order getOrder() {
        return order;
    }

    @Override
    public String toString() {
        return String.format("OrderPlacedEvent{seq=%d, order=%s, time=%s}", 
                           sequenceId, order, timestamp);
    }
} 