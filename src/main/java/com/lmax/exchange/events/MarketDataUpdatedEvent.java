package com.lmax.exchange.events;

import com.lmax.exchange.domain.Market;
import java.time.Instant;

/**
 * Event fired when market data is updated (prices, volumes, etc.)
 */
public class MarketDataUpdatedEvent implements Event {
    private final long sequenceId;
    private final Instant timestamp;
    private final Market market;

    public MarketDataUpdatedEvent(long sequenceId, Market market) {
        this.sequenceId = sequenceId;
        this.timestamp = Instant.now();
        this.market = market;
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
        return "MARKET_DATA_UPDATED";
    }

    public Market getMarket() {
        return market;
    }

    @Override
    public String toString() {
        return String.format("MarketDataUpdatedEvent{seq=%d, market=%s, time=%s}", 
                           sequenceId, market.getSymbol(), timestamp);
    }
} 