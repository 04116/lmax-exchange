package com.lmax.exchange.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a completed trade between two orders.
 * Immutable for thread safety and event sourcing.
 */
public class Trade {
    private final long tradeId;
    private final long buyOrderId;
    private final long sellOrderId;
    private final String buyUserId;
    private final String sellUserId;
    private final String symbol;
    private final BigDecimal price;
    private final long quantity;
    private final Instant timestamp;

    public Trade(long tradeId, long buyOrderId, long sellOrderId, 
                 String buyUserId, String sellUserId, String symbol,
                 BigDecimal price, long quantity) {
        this.tradeId = tradeId;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.buyUserId = buyUserId;
        this.sellUserId = sellUserId;
        this.symbol = symbol;
        this.price = price;
        this.quantity = quantity;
        this.timestamp = Instant.now();
    }

    // Getters
    public long getTradeId() { return tradeId; }
    public long getBuyOrderId() { return buyOrderId; }
    public long getSellOrderId() { return sellOrderId; }
    public String getBuyUserId() { return buyUserId; }
    public String getSellUserId() { return sellUserId; }
    public String getSymbol() { return symbol; }
    public BigDecimal getPrice() { return price; }
    public long getQuantity() { return quantity; }
    public Instant getTimestamp() { return timestamp; }

    public BigDecimal getValue() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }

    @Override
    public String toString() {
        return String.format("Trade{id=%d, symbol=%s, price=%s, qty=%d, buyer=%s, seller=%s, time=%s}",
                tradeId, symbol, price, quantity, buyUserId, sellUserId, timestamp);
    }
} 