package com.lmax.exchange.disruptor;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.exchange.events.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.ArrayList;

/**
 * Output Disruptor handles publishing events from the Business Logic Processor
 * to external systems (market data feeds, audit trails, client notifications).
 * This provides parallel processing of output events while maintaining ordering.
 */
public class OutputDisruptor {
    private static final Logger logger = LoggerFactory.getLogger(OutputDisruptor.class);
    private static final int RING_BUFFER_SIZE = 1024 * 1024; // Must be power of 2
    
    private final Disruptor<OutputEvent> disruptor;
    private final RingBuffer<OutputEvent> ringBuffer;
    private final List<OutputEventHandler> handlers = new ArrayList<>();
    
    public OutputDisruptor() {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger threadCount = new AtomicInteger(0);
            
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "OutputDisruptor-" + threadCount.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        
        this.disruptor = new Disruptor<>(
            OutputEvent::new,
            RING_BUFFER_SIZE,
            threadFactory,
            ProducerType.SINGLE,
            new YieldingWaitStrategy()
        );
        
        // Set up multiple event handlers for different output streams
        MarketDataHandler marketDataHandler = new MarketDataHandler();
        AuditTrailHandler auditHandler = new AuditTrailHandler();
        ClientNotificationHandler clientHandler = new ClientNotificationHandler();
        
        handlers.add(marketDataHandler);
        handlers.add(auditHandler);
        handlers.add(clientHandler);
        
        // All handlers process events in parallel
        disruptor.handleEventsWith(
            marketDataHandler,
            auditHandler,
            clientHandler
        );
        
        this.ringBuffer = disruptor.getRingBuffer();
        
        logger.info("Output Disruptor initialized with {} handlers", handlers.size());
    }
    
    public void start() {
        disruptor.start();
        logger.info("Output Disruptor started");
    }
    
    public void shutdown() {
        disruptor.shutdown();
        logger.info("Output Disruptor shutdown");
    }
    
    /**
     * Publish an event to all output handlers
     */
    public void publishEvent(Event event) {
        long sequence = ringBuffer.next();
        try {
            OutputEvent outputEvent = ringBuffer.get(sequence);
            outputEvent.setEvent(event);
        } finally {
            ringBuffer.publish(sequence);
        }
    }
    
    /**
     * Event that carries output data through the disruptor
     */
    public static class OutputEvent {
        private Event event;
        
        public void setEvent(Event event) {
            this.event = event;
        }
        
        public Event getEvent() {
            return event;
        }
    }
    
    /**
     * Base handler for output events
     */
    public abstract static class OutputEventHandler implements EventHandler<OutputEvent> {
        protected final Logger logger = LoggerFactory.getLogger(getClass());
        
        @Override
        public void onEvent(OutputEvent outputEvent, long sequence, boolean endOfBatch) throws Exception {
            try {
                handleEvent(outputEvent.getEvent());
            } catch (Exception e) {
                logger.error("Error handling output event", e);
            }
        }
        
        protected abstract void handleEvent(Event event);
    }
    
    /**
     * Handles market data events - publishes to market data feeds
     */
    public static class MarketDataHandler extends OutputEventHandler {
        @Override
        protected void handleEvent(Event event) {
            if ("MARKET_DATA_UPDATED".equals(event.getEventType()) || 
                "TRADE_EXECUTED".equals(event.getEventType())) {
                
                // Simulate publishing to market data feed
                logger.info("Publishing to market data feed: {}", event);
                
                // In real implementation, this would:
                // - Format message for market data protocol
                // - Send to market data multicast groups
                // - Update price displays
                // - Notify trading algorithms
            }
        }
    }
    
    /**
     * Handles audit trail events - persists all events for compliance
     */
    public static class AuditTrailHandler extends OutputEventHandler {
        @Override
        protected void handleEvent(Event event) {
            // All events go to audit trail
            logger.debug("Audit trail: {}", event);
            
            // In real implementation, this would:
            // - Write to high-speed persistent storage
            // - Maintain regulatory audit trail
            // - Support trade reconstruction
            // - Handle compliance reporting
        }
    }
    
    /**
     * Handles client notifications - sends order confirmations and fills
     */
    public static class ClientNotificationHandler extends OutputEventHandler {
        @Override
        protected void handleEvent(Event event) {
            if ("ORDER_PLACED".equals(event.getEventType()) || 
                "TRADE_EXECUTED".equals(event.getEventType())) {
                
                logger.info("Client notification: {}", event);
                
                // In real implementation, this would:
                // - Send order confirmations via FIX protocol
                // - Update client positions
                // - Send execution reports
                // - Handle settlement instructions
            }
        }
    }
    
    /**
     * Get performance metrics
     */
    public double getRingBufferUtilization() {
        long cursor = ringBuffer.getCursor();
        long capacity = ringBuffer.getBufferSize();
        return (double) cursor / capacity;
    }
    
    public int getRingBufferSize() {
        return RING_BUFFER_SIZE;
    }
} 