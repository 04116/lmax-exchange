package com.lmax.exchange.core;

import com.lmax.exchange.domain.Order;
import java.math.BigDecimal;
import java.util.*;

/**
 * Order book maintains buy and sell orders for a symbol.
 * Uses priority queues for efficient price-time priority matching.
 */
public class OrderBook {
    private final String symbol;
    private final PriorityQueue<Order> bids; // Buy orders - highest price first
    private final PriorityQueue<Order> asks; // Sell orders - lowest price first
    
    public OrderBook(String symbol) {
        this.symbol = symbol;
        // Bids: highest price first, then earliest time
        this.bids = new PriorityQueue<>((a, b) -> {
            int priceCompare = b.getPrice().compareTo(a.getPrice());
            return priceCompare != 0 ? priceCompare : a.getTimestamp().compareTo(b.getTimestamp());
        });
        // Asks: lowest price first, then earliest time  
        this.asks = new PriorityQueue<>((a, b) -> {
            int priceCompare = a.getPrice().compareTo(b.getPrice());
            return priceCompare != 0 ? priceCompare : a.getTimestamp().compareTo(b.getTimestamp());
        });
    }
    
    public void addOrder(Order order) {
        if (order.getSide() == Order.OrderSide.BUY) {
            bids.offer(order);
        } else {
            asks.offer(order);
        }
    }
    
    public PriorityQueue<Order> getBids() {
        return bids;
    }
    
    public PriorityQueue<Order> getAsks() {
        return asks;
    }
    
    public BigDecimal getBestBid() {
        return bids.isEmpty() ? BigDecimal.ZERO : bids.peek().getPrice();
    }
    
    public BigDecimal getBestAsk() {
        return asks.isEmpty() ? BigDecimal.ZERO : asks.peek().getPrice();
    }
    
    public long getBidQuantity() {
        return bids.isEmpty() ? 0 : bids.peek().getRemainingQuantity();
    }
    
    public long getAskQuantity() {
        return asks.isEmpty() ? 0 : asks.peek().getRemainingQuantity();
    }
    
    public String getSymbol() {
        return symbol;
    }
    
    @Override
    public String toString() {
        return String.format("OrderBook{symbol=%s, bestBid=%s, bestAsk=%s, bidDepth=%d, askDepth=%d}",
                symbol, getBestBid(), getBestAsk(), bids.size(), asks.size());
    }
} 