package com.lmax.exchange.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;

/**
 * Represents a trading market/instrument with its current state.
 * Immutable for thread safety and event sourcing.
 */
public class Market {
    private final String symbol;
    private final String name;
    private final MarketStatus status;
    private final BigDecimal lastPrice;
    private final BigDecimal bidPrice;
    private final BigDecimal askPrice;
    private final long bidQuantity;
    private final long askQuantity;
    private final BigDecimal dailyHigh;
    private final BigDecimal dailyLow;
    private final long dailyVolume;
    private final BigDecimal dailyTurnover;
    private final Instant lastUpdateTime;
    private final LocalTime openTime;
    private final LocalTime closeTime;
    private final BigDecimal tickSize;
    private final long minOrderSize;

    public Market(String symbol, String name, LocalTime openTime, LocalTime closeTime,
                  BigDecimal tickSize, long minOrderSize) {
        this.symbol = symbol;
        this.name = name;
        this.status = MarketStatus.CLOSED;
        this.lastPrice = BigDecimal.ZERO;
        this.bidPrice = BigDecimal.ZERO;
        this.askPrice = BigDecimal.ZERO;
        this.bidQuantity = 0;
        this.askQuantity = 0;
        this.dailyHigh = BigDecimal.ZERO;
        this.dailyLow = BigDecimal.ZERO;
        this.dailyVolume = 0;
        this.dailyTurnover = BigDecimal.ZERO;
        this.lastUpdateTime = Instant.now();
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.tickSize = tickSize;
        this.minOrderSize = minOrderSize;
    }

    private Market(String symbol, String name, MarketStatus status, BigDecimal lastPrice,
                   BigDecimal bidPrice, BigDecimal askPrice, long bidQuantity, long askQuantity,
                   BigDecimal dailyHigh, BigDecimal dailyLow, long dailyVolume, BigDecimal dailyTurnover,
                   Instant lastUpdateTime, LocalTime openTime, LocalTime closeTime,
                   BigDecimal tickSize, long minOrderSize) {
        this.symbol = symbol;
        this.name = name;
        this.status = status;
        this.lastPrice = lastPrice;
        this.bidPrice = bidPrice;
        this.askPrice = askPrice;
        this.bidQuantity = bidQuantity;
        this.askQuantity = askQuantity;
        this.dailyHigh = dailyHigh;
        this.dailyLow = dailyLow;
        this.dailyVolume = dailyVolume;
        this.dailyTurnover = dailyTurnover;
        this.lastUpdateTime = lastUpdateTime;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.tickSize = tickSize;
        this.minOrderSize = minOrderSize;
    }

    // Immutable update methods
    public Market withStatus(MarketStatus status) {
        return new Market(symbol, name, status, lastPrice, bidPrice, askPrice, bidQuantity, askQuantity,
                         dailyHigh, dailyLow, dailyVolume, dailyTurnover, Instant.now(), 
                         openTime, closeTime, tickSize, minOrderSize);
    }

    public Market updatePrices(BigDecimal newLastPrice, BigDecimal newBidPrice, BigDecimal newAskPrice,
                              long newBidQuantity, long newAskQuantity) {
        BigDecimal newHigh = dailyHigh.max(newLastPrice);
        BigDecimal newLow = dailyLow.equals(BigDecimal.ZERO) ? newLastPrice : dailyLow.min(newLastPrice);
        
        return new Market(symbol, name, status, newLastPrice, newBidPrice, newAskPrice, 
                         newBidQuantity, newAskQuantity, newHigh, newLow, dailyVolume, dailyTurnover,
                         Instant.now(), openTime, closeTime, tickSize, minOrderSize);
    }

    public Market addTrade(Trade trade) {
        long newVolume = dailyVolume + trade.getQuantity();
        BigDecimal newTurnover = dailyTurnover.add(trade.getValue());
        BigDecimal newHigh = dailyHigh.max(trade.getPrice());
        BigDecimal newLow = dailyLow.equals(BigDecimal.ZERO) ? trade.getPrice() : dailyLow.min(trade.getPrice());
        
        return new Market(symbol, name, status, trade.getPrice(), bidPrice, askPrice, 
                         bidQuantity, askQuantity, newHigh, newLow, newVolume, newTurnover,
                         Instant.now(), openTime, closeTime, tickSize, minOrderSize);
    }

    // Business logic methods
    public boolean isOpen() {
        return status == MarketStatus.OPEN;
    }

    public boolean isValidPrice(BigDecimal price) {
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        // Check if price is a multiple of tick size
        return price.remainder(tickSize).compareTo(BigDecimal.ZERO) == 0;
    }

    public boolean isValidOrderSize(long quantity) {
        return quantity >= minOrderSize;
    }

    public boolean isMarketOpen() {
        LocalTime now = LocalTime.now();
        return now.isAfter(openTime) && now.isBefore(closeTime) && status == MarketStatus.OPEN;
    }

    // Getters
    public String getSymbol() { return symbol; }
    public String getName() { return name; }
    public MarketStatus getStatus() { return status; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public BigDecimal getBidPrice() { return bidPrice; }
    public BigDecimal getAskPrice() { return askPrice; }
    public long getBidQuantity() { return bidQuantity; }
    public long getAskQuantity() { return askQuantity; }
    public BigDecimal getDailyHigh() { return dailyHigh; }
    public BigDecimal getDailyLow() { return dailyLow; }
    public long getDailyVolume() { return dailyVolume; }
    public BigDecimal getDailyTurnover() { return dailyTurnover; }
    public Instant getLastUpdateTime() { return lastUpdateTime; }
    public LocalTime getOpenTime() { return openTime; }
    public LocalTime getCloseTime() { return closeTime; }
    public BigDecimal getTickSize() { return tickSize; }
    public long getMinOrderSize() { return minOrderSize; }

    public BigDecimal getSpread() {
        if (bidPrice.equals(BigDecimal.ZERO) || askPrice.equals(BigDecimal.ZERO)) {
            return BigDecimal.ZERO;
        }
        return askPrice.subtract(bidPrice);
    }

    @Override
    public String toString() {
        return String.format("Market{symbol=%s, status=%s, last=%s, bid=%s, ask=%s, volume=%d}",
                symbol, status, lastPrice, bidPrice, askPrice, dailyVolume);
    }

    public enum MarketStatus {
        CLOSED, OPEN, SUSPENDED, PRE_OPEN, POST_CLOSE
    }
} 