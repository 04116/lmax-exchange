package com.lmax.exchange.events;

import com.lmax.exchange.domain.Trade;
import java.time.Instant;

/**
 * Event fired when a trade is executed between two orders.
 */
public class TradeExecutedEvent implements Event {
    private final long sequenceId;
    private final Instant timestamp;
    private final Trade trade;

    public TradeExecutedEvent(long sequenceId, Trade trade) {
        this.sequenceId = sequenceId;
        this.timestamp = Instant.now();
        this.trade = trade;
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
        return "TRADE_EXECUTED";
    }

    public Trade getTrade() {
        return trade;
    }

    @Override
    public String toString() {
        return String.format("TradeExecutedEvent{seq=%d, trade=%s, time=%s}", 
                           sequenceId, trade, timestamp);
    }
} 