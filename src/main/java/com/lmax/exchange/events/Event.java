package com.lmax.exchange.events;

import java.time.Instant;

/**
 * Base interface for all events in the exchange system.
 * Events are immutable and represent things that have happened.
 */
public interface Event {
    long getSequenceId();
    Instant getTimestamp();
    String getEventType();
} 