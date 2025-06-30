package com.lmax.exchange.persistence;

import com.lmax.exchange.domain.Order;
import com.lmax.exchange.domain.Trade;
import com.lmax.exchange.events.Event;
import com.lmax.exchange.events.OrderPlacedEvent;
import com.lmax.exchange.events.TradeExecutedEvent;
import com.lmax.exchange.disruptor.OutputDisruptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance batched persistence processor optimized for LMAX single-threaded architecture.
 * Uses dedicated thread for batching database writes to maximize throughput.
 */
public class BatchedPersistenceProcessor implements Runnable, OutputDisruptor.EventListener {
    private static final Logger logger = LoggerFactory.getLogger(BatchedPersistenceProcessor.class);
    private static final Logger perfLogger = LoggerFactory.getLogger("PERFORMANCE");
    
    private static final int BATCH_SIZE = 1000;
    private static final long BATCH_TIMEOUT_MS = 100; // 100ms max wait for batch
    private static final int QUEUE_CAPACITY = 100000;
    
    private final DataSource dataSource;
    private final BlockingQueue<Event> eventQueue;
    private final AtomicBoolean running;
    private final AtomicLong processedEvents;
    private final AtomicLong batchCount;
    private Thread processingThread;
    
    // SQL statements for batched inserts
    private static final String INSERT_ORDER_SQL = """
        INSERT INTO orders (order_id, user_id, symbol, order_type, side, price, quantity, 
                           remaining_qty, status, created_at, updated_at) 
        VALUES (?, ?, ?, ?::order_type, ?::order_side, ?, ?, ?, ?::order_status, ?, ?)
        ON CONFLICT (order_id) DO UPDATE SET
            remaining_qty = EXCLUDED.remaining_qty,
            status = EXCLUDED.status,
            updated_at = EXCLUDED.updated_at
        """;
    
    private static final String INSERT_TRADE_SQL = """
        INSERT INTO trades (trade_id, symbol, price, quantity, buyer_id, seller_id, executed_at) 
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
    
    public BatchedPersistenceProcessor(DataSource dataSource) {
        this.dataSource = dataSource;
        this.eventQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        this.running = new AtomicBoolean(false);
        this.processedEvents = new AtomicLong(0);
        this.batchCount = new AtomicLong(0);
    }
    
    public void start() {
        if (running.compareAndSet(false, true)) {
            processingThread = new Thread(this, "BatchedPersistenceProcessor");
            processingThread.setDaemon(false);
            processingThread.start();
            logger.info("Batched persistence processor started with batch size: {}", BATCH_SIZE);
        }
    }
    
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (processingThread != null) {
                processingThread.interrupt();
                try {
                    processingThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // Process remaining events
            processBatch(drainQueue());
            
            perfLogger.info("Persistence processor stopped. Processed {} events in {} batches", 
                           processedEvents.get(), batchCount.get());
        }
    }
    
    public boolean persist(Event event) {
        if (!running.get()) {
            return false;
        }
        
        try {
            return eventQueue.offer(event, 1, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    @Override
    public boolean onEvent(Event event) {
        return persist(event);
    }
    
    @Override
    public void run() {
        logger.info("Starting batched persistence processing loop");
        
        List<Event> batch = new ArrayList<>(BATCH_SIZE);
        long lastBatchTime = System.currentTimeMillis();
        
        while (running.get() || !eventQueue.isEmpty()) {
            try {
                // Try to build a batch
                Event event = eventQueue.poll(BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (event != null) {
                    batch.add(event);
                    
                    // Add more events if available (up to batch size)
                    while (batch.size() < BATCH_SIZE) {
                        Event nextEvent = eventQueue.poll();
                        if (nextEvent == null) break;
                        batch.add(nextEvent);
                    }
                }
                
                long currentTime = System.currentTimeMillis();
                boolean timeoutReached = (currentTime - lastBatchTime) >= BATCH_TIMEOUT_MS;
                boolean batchSizeReached = batch.size() >= BATCH_SIZE;
                
                // Process batch if we have events and (timeout reached or batch is full)
                if (!batch.isEmpty() && (timeoutReached || batchSizeReached)) {
                    processBatch(new ArrayList<>(batch));
                    batch.clear();
                    lastBatchTime = currentTime;
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        // Process any remaining events
        if (!batch.isEmpty()) {
            processBatch(batch);
        }
        
        logger.info("Batched persistence processing loop terminated");
    }
    
    private void processBatch(List<Event> events) {
        if (events.isEmpty()) return;
        
        long startTime = System.nanoTime();
        
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            
            List<OrderPlacedEvent> orderEvents = new ArrayList<>();
            List<TradeExecutedEvent> tradeEvents = new ArrayList<>();
            
            // Separate events by type
            for (Event event : events) {
                if (event instanceof OrderPlacedEvent orderEvent) {
                    orderEvents.add(orderEvent);
                } else if (event instanceof TradeExecutedEvent tradeEvent) {
                    tradeEvents.add(tradeEvent);
                }
            }
            
            // Batch insert orders
            if (!orderEvents.isEmpty()) {
                batchInsertOrders(conn, orderEvents);
            }
            
            // Batch insert trades
            if (!tradeEvents.isEmpty()) {
                batchInsertTrades(conn, tradeEvents);
            }
            
            conn.commit();
            
            long processingTime = System.nanoTime() - startTime;
            long eventsProcessed = processedEvents.addAndGet(events.size());
            long batches = batchCount.incrementAndGet();
            
            if (batches % 100 == 0) { // Log every 100 batches
                double avgBatchTime = processingTime / 1_000_000.0; // Convert to milliseconds
                double eventsPerSecond = events.size() / (processingTime / 1_000_000_000.0);
                
                perfLogger.info("Batch {} processed: {} events in {:.2f}ms ({:.0f} events/sec), " +
                               "total processed: {}", 
                               batches, events.size(), avgBatchTime, eventsPerSecond, eventsProcessed);
            }
            
        } catch (SQLException e) {
            logger.error("Failed to persist batch of {} events", events.size(), e);
        }
    }
    
    private void batchInsertOrders(Connection conn, List<OrderPlacedEvent> orderEvents) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(INSERT_ORDER_SQL)) {
            for (OrderPlacedEvent event : orderEvents) {
                Order order = event.getOrder();
                Timestamp now = Timestamp.from(event.getTimestamp());
                
                stmt.setLong(1, order.getOrderId());
                stmt.setString(2, order.getUserId());
                stmt.setString(3, order.getSymbol());
                stmt.setString(4, order.getType().name());
                stmt.setString(5, order.getSide().name());
                stmt.setBigDecimal(6, order.getPrice());
                stmt.setLong(7, order.getQuantity());
                stmt.setLong(8, order.getRemainingQuantity());
                stmt.setString(9, order.getStatus().name());
                stmt.setTimestamp(10, now);
                stmt.setTimestamp(11, now);
                
                stmt.addBatch();
            }
            
            stmt.executeBatch();
        }
    }
    
    private void batchInsertTrades(Connection conn, List<TradeExecutedEvent> tradeEvents) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(INSERT_TRADE_SQL)) {
            for (TradeExecutedEvent event : tradeEvents) {
                Trade trade = event.getTrade();
                
                stmt.setLong(1, trade.getTradeId());
                stmt.setString(2, trade.getSymbol());
                stmt.setBigDecimal(3, trade.getPrice());
                stmt.setLong(4, trade.getQuantity());
                stmt.setString(5, trade.getBuyUserId());
                stmt.setString(6, trade.getSellUserId());
                stmt.setTimestamp(7, Timestamp.from(trade.getTimestamp()));
                
                stmt.addBatch();
            }
            
            stmt.executeBatch();
        }
    }
    
    private List<Event> drainQueue() {
        List<Event> events = new ArrayList<>();
        Event event;
        while ((event = eventQueue.poll()) != null) {
            events.add(event);
        }
        return events;
    }
    
    public long getProcessedEventCount() {
        return processedEvents.get();
    }
    
    public long getBatchCount() {
        return batchCount.get();
    }
    
    public int getQueueSize() {
        return eventQueue.size();
    }
} 