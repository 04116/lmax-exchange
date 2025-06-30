package com.lmax.exchange.core;

import com.lmax.exchange.domain.Order;
import com.lmax.exchange.domain.Trade;
import java.util.ArrayList;
import java.util.List;

/**
 * Contains the result of processing an order through the matching engine.
 */
public class MatchingResult {
    private final Order originalOrder;
    private Order remainingOrder;
    private final List<Trade> trades;
    
    public MatchingResult(Order originalOrder) {
        this.originalOrder = originalOrder;
        this.remainingOrder = originalOrder;
        this.trades = new ArrayList<>();
    }
    
    public void addTrade(Trade trade) {
        trades.add(trade);
    }
    
    public void setRemainingOrder(Order remainingOrder) {
        this.remainingOrder = remainingOrder;
    }
    
    public Order getOriginalOrder() {
        return originalOrder;
    }
    
    public Order getRemainingOrder() {
        return remainingOrder;
    }
    
    public List<Trade> getTrades() {
        return new ArrayList<>(trades);
    }
    
    public boolean isFullyFilled() {
        return remainingOrder.getRemainingQuantity() == 0;
    }
    
    public boolean hasPartialFill() {
        return !trades.isEmpty() && !isFullyFilled();
    }
    
    @Override
    public String toString() {
        return String.format("MatchingResult{originalOrder=%d, trades=%d, remaining=%d}",
                originalOrder.getOrderId(), trades.size(), remainingOrder.getRemainingQuantity());
    }
} 