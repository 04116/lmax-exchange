package com.lmax.exchange;

import com.lmax.exchange.core.BusinessLogicProcessor;
import com.lmax.exchange.disruptor.InputDisruptor;
import com.lmax.exchange.disruptor.OutputDisruptor;
import com.lmax.exchange.domain.Order;
import com.lmax.exchange.events.Event;
import com.lmax.exchange.http.ExchangeHttpServer;
import com.lmax.exchange.persistence.BatchedPersistenceProcessor;
import com.lmax.exchange.persistence.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.core.Vertx;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Main Exchange class that demonstrates the complete LMAX architecture.
 * 
 * This implementation shows:
 * 1. Single-threaded Business Logic Processor (no locks, no database transactions)
 * 2. Input Disruptor for high-performance order ingestion
 * 3. Output Disruptor for parallel event publishing
 * 4. Event Sourcing for state reconstruction
 * 5. In-memory processing with nanosecond latencies
 * 
 * Key LMAX principles demonstrated:
 * - All business logic runs on a single thread (eliminates concurrency complexity)
 * - No database transactions (event sourcing provides durability)
 * - Lock-free data structures (Disruptor ring buffers)
 * - Mechanical sympathy (cache-friendly data structures)
 */
public class ExchangeMain {
    private static final Logger logger = LoggerFactory.getLogger(ExchangeMain.class);
    
    private final BusinessLogicProcessor businessLogicProcessor;
    private final InputDisruptor inputDisruptor;
    private final OutputDisruptor outputDisruptor;
    private final DatabaseConfig databaseConfig;
    private final BatchedPersistenceProcessor persistenceProcessor;
    
    public ExchangeMain() {
        logger.info("Initializing LMAX Exchange Architecture...");
        
        // 1. Create the single-threaded Business Logic Processor
        this.businessLogicProcessor = new BusinessLogicProcessor();
        
        // 2. Create Output Disruptor for publishing events
        this.outputDisruptor = new OutputDisruptor();
        
        // 3. Initialize database configuration
        this.databaseConfig = DatabaseConfig.fromEnvironment();
        
        // 4. Create batched persistence processor
        this.persistenceProcessor = new BatchedPersistenceProcessor(databaseConfig.getDataSource());
        
        // 5. Wire Business Logic Processor to Output Disruptor
        this.businessLogicProcessor.addEventListener(outputDisruptor::publishEvent);
        
        // 6. Wire Output Disruptor to Persistence Processor
        this.outputDisruptor.addEventListener(persistenceProcessor);
        
        // 7. Create Input Disruptor for processing orders
        this.inputDisruptor = new InputDisruptor(businessLogicProcessor);
        
        logger.info("LMAX Exchange Architecture initialized successfully");
    }
    
    public void start() {
        logger.info("Starting LMAX Exchange...");
        
        // 1. Start persistence processor
        persistenceProcessor.start();
        
        // 2. Start disruptors
        outputDisruptor.start();
        inputDisruptor.start();
        
        logger.info("LMAX Exchange started and ready for trading");
    }
    
    public void shutdown() {
        logger.info("Shutting down LMAX Exchange...");
        
        // 1. Shutdown disruptors
        inputDisruptor.shutdown();
        outputDisruptor.shutdown();
        
        // 2. Shutdown persistence processor
        persistenceProcessor.stop();
        
        // 3. Close database connections
        databaseConfig.close();
        
        logger.info("LMAX Exchange shutdown complete");
    }
    
    /**
     * Submit an order to the exchange
     */
    public void submitOrder(String userId, String symbol, Order.OrderType type, 
                           Order.OrderSide side, BigDecimal price, long quantity, 
                           Order.TimeInForce timeInForce) {
        inputDisruptor.submitOrder(userId, symbol, type, side, price, quantity, timeInForce);
    }
    
    /**
     * Get exchange statistics
     */
    public void printStatistics() {
        logger.info("=== LMAX Exchange Statistics ===");
        logger.info("Input Disruptor utilization: {:.2f}%", 
                   inputDisruptor.getRingBufferUtilization() * 100);
        logger.info("Output Disruptor utilization: {:.2f}%", 
                   outputDisruptor.getRingBufferUtilization() * 100);
        logger.info("Total events journaled: {}", 
                   businessLogicProcessor.getEventJournal().size());
        logger.info("Total trades executed: {}", 
                   businessLogicProcessor.getTrades().size());
        logger.info("Active orders in book: {}", 
                   businessLogicProcessor.getActiveOrders().size());
        
        // Print market data
        var market = businessLogicProcessor.getMarket("BTCUSD");
        if (market != null) {
            logger.info("BTCUSD Market Data: {}", market);
            var orderBook = businessLogicProcessor.getOrderBook("BTCUSD");
            logger.info("BTCUSD Order Book: {}", orderBook);
        }
    }
    
    /**
     * Demonstrate event sourcing by replaying events
     */
    public void demonstrateEventSourcing() {
        logger.info("=== Demonstrating Event Sourcing ===");
        
        // Get current events
        var events = businessLogicProcessor.getEventJournal();
        logger.info("Current event journal contains {} events", events.size());
        
        // Print first few events to show the audit trail
        events.stream().limit(10).forEach(event -> 
            logger.info("Event: {}", event));
        
        logger.info("In a real system, these events would enable:");
        logger.info("1. Complete state reconstruction after restart");
        logger.info("2. Point-in-time snapshots and replay");
        logger.info("3. Audit trail for regulatory compliance");
        logger.info("4. Disaster recovery without data loss");
    }
    
    public static void main(String[] args) throws InterruptedException {
        // Check if we should run in demo mode or server mode
        boolean runDemo = args.length > 0 && "demo".equals(args[0]);
        
        ExchangeMain exchange = new ExchangeMain();
        
        if (runDemo) {
            // Demo mode - run demonstration and exit
            try {
                exchange.start();
                
                // Demonstrate the power of single-threaded processing
                exchange.demonstrateComplexTransactionProcessing();
                
                // Wait a bit for processing
                Thread.sleep(1000);
                
                // Show statistics
                exchange.printStatistics();
                
                // Demonstrate event sourcing
                exchange.demonstrateEventSourcing();
                
            } finally {
                exchange.shutdown();
            }
        } else {
            // Server mode - start and keep running
            exchange.start();
            
            // Start HTTP server
            ExchangeHttpServer.start(Vertx.vertx(), 8080, exchange.businessLogicProcessor, exchange.persistenceProcessor)
                .onSuccess(deploymentId -> 
                    logger.info("HTTP server started successfully on port 8080"))
                .onFailure(throwable -> {
                    logger.error("Failed to start HTTP server", throwable);
                    System.exit(1);
                });
            
            logger.info("LMAX Exchange started in server mode. HTTP API available on port 8080");
            logger.info("Press Ctrl+C to shutdown...");
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutdown signal received");
                exchange.shutdown();
            }));
            
            // Keep the main thread alive
            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                logger.info("Main thread interrupted, shutting down...");
                exchange.shutdown();
            }
        }
    }
    
    /**
     * Demonstrates the complex transaction processing described in the LMAX article.
     * This shows how a single thread can handle millions of operations per second
     * without database transactions or locks.
     */
    private void demonstrateComplexTransactionProcessing() throws InterruptedException {
        logger.info("=== Demonstrating Complex Transaction Processing ===");
        logger.info("Processing orders through single-threaded Business Logic Processor...");
        
        // Submit orders that will create complex trading scenarios
        
        // 1. Place some limit orders to build the order book
        submitOrder("trader1", "BTCUSD", Order.OrderType.LIMIT, Order.OrderSide.BUY, 
                   new BigDecimal("45000.00"), 100L, Order.TimeInForce.GTC);
                   
        submitOrder("trader2", "BTCUSD", Order.OrderType.LIMIT, Order.OrderSide.BUY, 
                   new BigDecimal("44999.00"), 50L, Order.TimeInForce.GTC);
                   
        submitOrder("trader3", "BTCUSD", Order.OrderType.LIMIT, Order.OrderSide.SELL, 
                   new BigDecimal("45001.00"), 75L, Order.TimeInForce.GTC);
                   
        submitOrder("trader4", "BTCUSD", Order.OrderType.LIMIT, Order.OrderSide.SELL, 
                   new BigDecimal("45002.00"), 200L, Order.TimeInForce.GTC);
        
        // 2. Submit market orders that will match against the book
        submitOrder("trader5", "BTCUSD", Order.OrderType.MARKET, Order.OrderSide.BUY, 
                   null, 150L, Order.TimeInForce.IOC);
                   
        submitOrder("trader6", "BTCUSD", Order.OrderType.MARKET, Order.OrderSide.SELL, 
                   null, 120L, Order.TimeInForce.IOC);
        
        // 3. Submit limit orders that will partially match
        submitOrder("trader7", "BTCUSD", Order.OrderType.LIMIT, Order.OrderSide.BUY, 
                   new BigDecimal("45001.50"), 300L, Order.TimeInForce.GTC);
        
        // 4. Demonstrate high-frequency trading scenario
        logger.info("Simulating high-frequency trading burst...");
        
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(1000);
        
        long startTime = System.nanoTime();
        
        // Submit 1000 orders concurrently
        for (int i = 0; i < 1000; i++) {
            final int orderId = i;
            executor.submit(() -> {
                try {
                    BigDecimal price = new BigDecimal("45000").add(
                        new BigDecimal(orderId % 10).multiply(new BigDecimal("0.01")));
                    Order.OrderSide side = orderId % 2 == 0 ? Order.OrderSide.BUY : Order.OrderSide.SELL;
                    
                    submitOrder("hft_trader_" + orderId, "BTCUSD", Order.OrderType.LIMIT, 
                               side, price, 1L, Order.TimeInForce.IOC);
                               
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(5, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        
        executor.shutdown();
        
        long totalTime = endTime - startTime;
        double ordersPerSecond = 1000.0 / (totalTime / 1_000_000_000.0);
        
        logger.info("Processed 1000 orders in {} nanoseconds", totalTime);
        logger.info("Throughput: {:.0f} orders per second", ordersPerSecond);
        logger.info("Average latency per order: {} nanoseconds", totalTime / 1000);
        
        logger.info("=== Key LMAX Benefits Demonstrated ===");
        logger.info("1. Single-threaded processing eliminates lock contention");
        logger.info("2. No database transactions - everything in memory with event sourcing");
        logger.info("3. Mechanical sympathy - cache-friendly data structures");
        logger.info("4. Predictable latencies - no GC pauses or lock waits");
        logger.info("5. Simple programming model - no complex concurrency code");
    }
    
    // Getters for testing/monitoring
    public BusinessLogicProcessor getBusinessLogicProcessor() {
        return businessLogicProcessor;
    }
    
    public InputDisruptor getInputDisruptor() {
        return inputDisruptor;
    }
    
    public OutputDisruptor getOutputDisruptor() {
        return outputDisruptor;
    }
} 