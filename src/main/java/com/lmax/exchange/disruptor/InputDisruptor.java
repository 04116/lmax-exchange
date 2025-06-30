package com.lmax.exchange.disruptor;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.exchange.core.BusinessLogicProcessor;
import com.lmax.exchange.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Input Disruptor handles incoming order requests and feeds them to the
 * Business Logic Processor. This provides high-performance, lock-free
 * queuing as described in the LMAX architecture.
 */
public class InputDisruptor {
    private static final Logger logger = LoggerFactory.getLogger(InputDisruptor.class);
    private static final int RING_BUFFER_SIZE = 1024 * 1024; // Must be power of 2
    
    private final Disruptor<OrderEvent> disruptor;
    private final RingBuffer<OrderEvent> ringBuffer;
    private final BusinessLogicProcessor processor;
    
    public InputDisruptor(BusinessLogicProcessor processor) {
        this.processor = processor;
        
        // Create thread factory for disruptor threads
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger threadCount = new AtomicInteger(0);
            
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "InputDisruptor-" + threadCount.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        
        // Create disruptor with single producer (as per LMAX design)
        this.disruptor = new Disruptor<>(
            OrderEvent::new,
            RING_BUFFER_SIZE,
            threadFactory,
            ProducerType.SINGLE,
            new YieldingWaitStrategy()
        );
        
        // Set up event handler that processes orders
        disruptor.handleEventsWith(new OrderEventHandler());
        
        this.ringBuffer = disruptor.getRingBuffer();
        
        logger.info("Input Disruptor initialized with ring buffer size: {}", RING_BUFFER_SIZE);
    }
    
    public void start() {
        disruptor.start();
        logger.info("Input Disruptor started");
    }
    
    public void shutdown() {
        disruptor.shutdown();
        logger.info("Input Disruptor shutdown");
    }
    
    /**
     * Submit an order for processing. This method is thread-safe and lock-free.
     */
    public void submitOrder(String userId, String symbol, Order.OrderType type, 
                           Order.OrderSide side, BigDecimal price, long quantity, 
                           Order.TimeInForce timeInForce) {
        
        long sequence = ringBuffer.next();
        try {
            OrderEvent event = ringBuffer.get(sequence);
            event.setOrderData(userId, symbol, type, side, price, quantity, timeInForce);
            event.setSubmissionTime(System.nanoTime());
        } finally {
            ringBuffer.publish(sequence);
        }
    }
    
    /**
     * Event that carries order data through the disruptor
     */
    public static class OrderEvent {
        private String userId;
        private String symbol;
        private Order.OrderType type;
        private Order.OrderSide side;
        private BigDecimal price;
        private long quantity;
        private Order.TimeInForce timeInForce;
        private long submissionTime;
        
        public void setOrderData(String userId, String symbol, Order.OrderType type, 
                                Order.OrderSide side, BigDecimal price, long quantity, 
                                Order.TimeInForce timeInForce) {
            this.userId = userId;
            this.symbol = symbol;
            this.type = type;
            this.side = side;
            this.price = price;
            this.quantity = quantity;
            this.timeInForce = timeInForce;
        }
        
        public void setSubmissionTime(long submissionTime) {
            this.submissionTime = submissionTime;
        }
        
        // Getters
        public String getUserId() { return userId; }
        public String getSymbol() { return symbol; }
        public Order.OrderType getType() { return type; }
        public Order.OrderSide getSide() { return side; }
        public BigDecimal getPrice() { return price; }
        public long getQuantity() { return quantity; }
        public Order.TimeInForce getTimeInForce() { return timeInForce; }
        public long getSubmissionTime() { return submissionTime; }
    }
    
    /**
     * Event handler that processes orders through the Business Logic Processor
     */
    private class OrderEventHandler implements EventHandler<OrderEvent> {
        @Override
        public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) throws Exception {
            long startTime = System.nanoTime();
            
            try {
                // Process order through single-threaded business logic processor
                BusinessLogicProcessor.ProcessingResult result = processor.processOrder(
                    event.getUserId(),
                    event.getSymbol(), 
                    event.getType(),
                    event.getSide(),
                    event.getPrice(),
                    event.getQuantity(),
                    event.getTimeInForce()
                );
                
                long processingTime = System.nanoTime() - startTime;
                long totalLatency = System.nanoTime() - event.getSubmissionTime();
                
                if (result.isSuccess()) {
                    logger.debug("Order processed successfully in {} ns (total latency: {} ns)", 
                               processingTime, totalLatency);
                } else {
                    logger.warn("Order rejected: {}", result.getMessage());
                }
                
            } catch (Exception e) {
                logger.error("Error processing order event", e);
            }
        }
    }
    
    /**
     * Get current ring buffer utilization for monitoring
     */
    public double getRingBufferUtilization() {
        long cursor = ringBuffer.getCursor();
        long capacity = ringBuffer.getBufferSize();
        return (double) cursor / capacity;
    }
    
    /**
     * Get ring buffer size
     */
    public int getRingBufferSize() {
        return RING_BUFFER_SIZE;
    }
} 