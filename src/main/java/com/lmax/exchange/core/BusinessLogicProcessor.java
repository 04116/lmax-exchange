package com.lmax.exchange.core;

import com.lmax.exchange.domain.*;
import com.lmax.exchange.events.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The heart of the LMAX architecture - a single-threaded processor that handles
 * all business logic for the exchange. This demonstrates the power of single-threaded
 * processing and elimination of database transactions through event sourcing.
 * 
 * All state is kept in memory, and the entire state can be reconstructed by
 * replaying the event stream (Event Sourcing pattern).
 */
public class BusinessLogicProcessor {
    private static final Logger logger = LoggerFactory.getLogger(BusinessLogicProcessor.class);
    
    // In-memory state - no database needed!
    private final Map<String, Market> markets = new HashMap<>();
    private final Map<Long, Order> activeOrders = new HashMap<>();
    private final Map<String, OrderBook> orderBooks = new HashMap<>();
    private final List<Trade> trades = new ArrayList<>();
    private final List<Event> eventJournal = new ArrayList<>();
    
    // Sequence generators
    private final AtomicLong orderIdGenerator = new AtomicLong(1);
    private final AtomicLong tradeIdGenerator = new AtomicLong(1);
    private final AtomicLong sequenceGenerator = new AtomicLong(1);
    
    // Event listeners for output
    private final List<EventListener> eventListeners = new ArrayList<>();
    
    public BusinessLogicProcessor() {
        initializeMarkets();
    }
    
    private void initializeMarkets() {
        // Initialize some sample markets
        Market btcusd = new Market("BTCUSD", "Bitcoin/USD", 
                                  LocalTime.of(0, 0), LocalTime.of(23, 59),
                                  new BigDecimal("0.01"), 1L);
        Market ethusd = new Market("ETHUSD", "Ethereum/USD",
                                  LocalTime.of(0, 0), LocalTime.of(23, 59),
                                  new BigDecimal("0.01"), 1L);
        
        // Open markets
        markets.put("BTCUSD", btcusd.withStatus(Market.MarketStatus.OPEN));
        markets.put("ETHUSD", ethusd.withStatus(Market.MarketStatus.OPEN));
        
        // Initialize order books
        orderBooks.put("BTCUSD", new OrderBook("BTCUSD"));
        orderBooks.put("ETHUSD", new OrderBook("ETHUSD"));
        
        logger.info("Initialized markets: {}", markets.keySet());
    }
    
    /**
     * Main entry point for processing orders. This method demonstrates the complex
     * transaction processing described in the LMAX article:
     * 1. Check if target market is open
     * 2. Validate order for that market
     * 3. Choose matching policy
     * 4. Sequence order for best price matching
     * 5. Create and publicize trades
     * 6. Update prices based on new trades
     */
    public synchronized ProcessingResult processOrder(String userId, String symbol, 
                                                     Order.OrderType type, Order.OrderSide side,
                                                     BigDecimal price, long quantity, 
                                                     Order.TimeInForce timeInForce) {
        
        long startTime = System.nanoTime();
        
        try {
            // Step 1: Check if target market is open to take orders
            Market market = markets.get(symbol);
            if (market == null) {
                return ProcessingResult.rejected("Unknown market: " + symbol);
            }
            
            if (!market.isMarketOpen()) {
                return ProcessingResult.rejected("Market " + symbol + " is closed");
            }
            
            // Step 2: Validate order for that market
            ValidationResult validation = validateOrder(market, type, side, price, quantity, timeInForce);
            if (!validation.isValid()) {
                return ProcessingResult.rejected(validation.getReason());
            }
            
            // Step 3: Create order with unique ID
            long orderId = orderIdGenerator.getAndIncrement();
            Order order = new Order(orderId, userId, symbol, type, side, price, quantity, timeInForce);
            
            // Step 4: Process order through matching engine
            OrderBook orderBook = orderBooks.get(symbol);
            MatchingResult matchingResult = processOrderMatching(order, orderBook);
            
            // Step 5: Update in-memory state based on matching result
            updateStateFromMatching(matchingResult, market);
            
            // Step 6: Generate and journal events (Event Sourcing)
            journalEvents(matchingResult);
            
            long processingTime = System.nanoTime() - startTime;
            
            logger.debug("Processed order {} in {} nanoseconds", orderId, processingTime);
            
            return ProcessingResult.success(matchingResult);
            
        } catch (Exception e) {
            logger.error("Error processing order", e);
            return ProcessingResult.rejected("Internal error: " + e.getMessage());
        }
    }
    
    private ValidationResult validateOrder(Market market, Order.OrderType type, Order.OrderSide side,
                                         BigDecimal price, long quantity, Order.TimeInForce timeInForce) {
        
        // Validate quantity
        if (!market.isValidOrderSize(quantity)) {
            return ValidationResult.invalid("Order quantity " + quantity + " below minimum " + market.getMinOrderSize());
        }
        
        // Validate price for limit orders
        if (type == Order.OrderType.LIMIT && price != null && !market.isValidPrice(price)) {
            return ValidationResult.invalid("Invalid price " + price + " (not multiple of tick size " + market.getTickSize() + ")");
        }
        
        // Market orders don't need price validation
        if (type == Order.OrderType.MARKET && price != null && price.compareTo(BigDecimal.ZERO) != 0) {
            return ValidationResult.invalid("Market orders should not specify a price");
        }
        
        return ValidationResult.valid();
    }
    
    /**
     * Step 4: Choose the right matching policy and sequence orders for best price matching.
     * This implements price-time priority matching.
     */
    private MatchingResult processOrderMatching(Order order, OrderBook orderBook) {
        MatchingResult result = new MatchingResult(order);
        
        if (order.getType() == Order.OrderType.MARKET) {
            // Market order - match against best available prices
            processMarketOrder(order, orderBook, result);
        } else if (order.getType() == Order.OrderType.LIMIT) {
            // Limit order - match at specified price or better
            processLimitOrder(order, orderBook, result);
        }
        
        return result;
    }
    
    private void processMarketOrder(Order order, OrderBook orderBook, MatchingResult result) {
        if (order.getSide() == Order.OrderSide.BUY) {
            // Buy market order matches against asks (sell orders)
            matchAgainstSide(order, orderBook.getAsks(), result);
        } else {
            // Sell market order matches against bids (buy orders)
            matchAgainstSide(order, orderBook.getBids(), result);
        }
    }
    
    private void processLimitOrder(Order order, OrderBook orderBook, MatchingResult result) {
        if (order.getSide() == Order.OrderSide.BUY) {
            // Buy limit order can match against asks at or below limit price
            matchAgainstSideWithPriceLimit(order, orderBook.getAsks(), result, true);
        } else {
            // Sell limit order can match against bids at or above limit price  
            matchAgainstSideWithPriceLimit(order, orderBook.getBids(), result, false);
        }
        
        // Add remaining quantity to order book if not fully filled
        if (result.getRemainingOrder().getRemainingQuantity() > 0) {
            if (order.getTimeInForce() != Order.TimeInForce.IOC) { // IOC orders don't rest in book
                orderBook.addOrder(result.getRemainingOrder());
                activeOrders.put(result.getRemainingOrder().getOrderId(), result.getRemainingOrder());
            }
        }
    }
    
    private void matchAgainstSide(Order incomingOrder, PriorityQueue<Order> oppositeSide, MatchingResult result) {
        Order currentOrder = result.getRemainingOrder();
        
        while (!oppositeSide.isEmpty() && currentOrder.getRemainingQuantity() > 0) {
            Order restingOrder = oppositeSide.peek();
            
            long matchQuantity = Math.min(currentOrder.getRemainingQuantity(), restingOrder.getRemainingQuantity());
            BigDecimal matchPrice = restingOrder.getPrice(); // Price priority
            
            // Create trade
            Trade trade = createTrade(currentOrder, restingOrder, matchPrice, matchQuantity);
            result.addTrade(trade);
            
            // Update orders
            currentOrder = currentOrder.fillQuantity(matchQuantity);
            Order updatedRestingOrder = restingOrder.fillQuantity(matchQuantity);
            
            // Remove from book if fully filled
            if (updatedRestingOrder.getRemainingQuantity() == 0) {
                oppositeSide.poll();
                activeOrders.remove(updatedRestingOrder.getOrderId());
            } else {
                // Update in book (remove and re-add to maintain priority)
                oppositeSide.poll();
                oppositeSide.offer(updatedRestingOrder);
                activeOrders.put(updatedRestingOrder.getOrderId(), updatedRestingOrder);
            }
        }
        
        result.setRemainingOrder(currentOrder);
    }
    
    private void matchAgainstSideWithPriceLimit(Order incomingOrder, PriorityQueue<Order> oppositeSide, 
                                               MatchingResult result, boolean isBuyOrder) {
        Order currentOrder = result.getRemainingOrder();
        
        while (!oppositeSide.isEmpty() && currentOrder.getRemainingQuantity() > 0) {
            Order restingOrder = oppositeSide.peek();
            
            // Check price compatibility
            boolean canMatch = isBuyOrder ? 
                currentOrder.getPrice().compareTo(restingOrder.getPrice()) >= 0 :
                currentOrder.getPrice().compareTo(restingOrder.getPrice()) <= 0;
                
            if (!canMatch) {
                break; // No more matching possible at this price level
            }
            
            long matchQuantity = Math.min(currentOrder.getRemainingQuantity(), restingOrder.getRemainingQuantity());
            BigDecimal matchPrice = restingOrder.getPrice(); // Price priority - resting order's price
            
            // Create trade
            Trade trade = createTrade(currentOrder, restingOrder, matchPrice, matchQuantity);
            result.addTrade(trade);
            
            // Update orders
            currentOrder = currentOrder.fillQuantity(matchQuantity);
            Order updatedRestingOrder = restingOrder.fillQuantity(matchQuantity);
            
            // Remove from book if fully filled
            if (updatedRestingOrder.getRemainingQuantity() == 0) {
                oppositeSide.poll();
                activeOrders.remove(updatedRestingOrder.getOrderId());
            } else {
                // Update in book
                oppositeSide.poll();
                oppositeSide.offer(updatedRestingOrder);
                activeOrders.put(updatedRestingOrder.getOrderId(), updatedRestingOrder);
            }
        }
        
        result.setRemainingOrder(currentOrder);
    }
    
    private Trade createTrade(Order buyOrder, Order sellOrder, BigDecimal price, long quantity) {
        long tradeId = tradeIdGenerator.getAndIncrement();
        
        // Determine which is buy and which is sell
        if (buyOrder.getSide() == Order.OrderSide.BUY) {
            return new Trade(tradeId, buyOrder.getOrderId(), sellOrder.getOrderId(),
                           buyOrder.getUserId(), sellOrder.getUserId(), buyOrder.getSymbol(),
                           price, quantity);
        } else {
            return new Trade(tradeId, sellOrder.getOrderId(), buyOrder.getOrderId(),
                           sellOrder.getUserId(), buyOrder.getUserId(), buyOrder.getSymbol(),
                           price, quantity);
        }
    }
    
    /**
     * Step 5: Update in-memory state based on matching results
     */
    private void updateStateFromMatching(MatchingResult matchingResult, Market market) {
        // Add trades to trade history
        trades.addAll(matchingResult.getTrades());
        
        // Update market data if there were trades
        if (!matchingResult.getTrades().isEmpty()) {
            Trade lastTrade = matchingResult.getTrades().get(matchingResult.getTrades().size() - 1);
            Market updatedMarket = market.addTrade(lastTrade);
            
            // Update best bid/ask from order book
            OrderBook orderBook = orderBooks.get(market.getSymbol());
            BigDecimal bestBid = orderBook.getBestBid();
            BigDecimal bestAsk = orderBook.getBestAsk();
            long bidQuantity = orderBook.getBidQuantity();
            long askQuantity = orderBook.getAskQuantity();
            
            updatedMarket = updatedMarket.updatePrices(lastTrade.getPrice(), bestBid, bestAsk, 
                                                      bidQuantity, askQuantity);
            markets.put(market.getSymbol(), updatedMarket);
        }
    }
    
    /**
     * Step 6: Journal events for event sourcing and notify listeners
     */
    private void journalEvents(MatchingResult matchingResult) {
        long sequence = sequenceGenerator.getAndIncrement();
        
        // Journal order placed event
        OrderPlacedEvent orderEvent = new OrderPlacedEvent(sequence++, matchingResult.getOriginalOrder());
        eventJournal.add(orderEvent);
        notifyListeners(orderEvent);
        
        // Journal trade events
        for (Trade trade : matchingResult.getTrades()) {
            TradeExecutedEvent tradeEvent = new TradeExecutedEvent(sequence++, trade);
            eventJournal.add(tradeEvent);
            notifyListeners(tradeEvent);
        }
        
        // Journal market data update if there were trades
        if (!matchingResult.getTrades().isEmpty()) {
            String symbol = matchingResult.getOriginalOrder().getSymbol();
            Market market = markets.get(symbol);
            MarketDataUpdatedEvent marketEvent = new MarketDataUpdatedEvent(sequence++, market);
            eventJournal.add(marketEvent);
            notifyListeners(marketEvent);
        }
    }
    
    private void notifyListeners(Event event) {
        for (EventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                logger.warn("Error notifying listener", e);
            }
        }
    }
    
    // Public API methods
    public void addEventListener(EventListener listener) {
        eventListeners.add(listener);
    }
    
    public Market getMarket(String symbol) {
        return markets.get(symbol);
    }
    
    public OrderBook getOrderBook(String symbol) {
        return orderBooks.get(symbol);
    }
    
    public List<Trade> getTrades() {
        return new ArrayList<>(trades);
    }
    
    public List<Event> getEventJournal() {
        return new ArrayList<>(eventJournal);
    }
    
    public void clearEventJournal() {
        eventJournal.clear();
    }
    
    public Map<Long, Order> getActiveOrders() {
        return new HashMap<>(activeOrders);
    }
    
    public long getProcessedEventCount() {
        return eventJournal.size();
    }
    
    public long getActiveOrderCount() {
        return activeOrders.size();
    }
    
    /**
     * Demonstrates event sourcing - reconstruct state by replaying events
     */
    public void replayEvents(List<Event> events) {
        logger.info("Replaying {} events to reconstruct state", events.size());
        
        // Clear current state
        markets.clear();
        activeOrders.clear();
        orderBooks.clear();
        trades.clear();
        
        // Reinitialize
        initializeMarkets();
        
        // Replay events
        for (Event event : events) {
            if (event instanceof OrderPlacedEvent) {
                // Re-process the order
                Order order = ((OrderPlacedEvent) event).getOrder();
                processOrder(order.getUserId(), order.getSymbol(), order.getType(),
                           order.getSide(), order.getPrice(), order.getQuantity(), order.getTimeInForce());
            }
            // Other event types would be handled here
        }
        
        logger.info("State reconstruction complete");
    }
    
    // Helper classes
    public static class ProcessingResult {
        private final boolean success;
        private final String message;
        private final MatchingResult matchingResult;
        
        private ProcessingResult(boolean success, String message, MatchingResult matchingResult) {
            this.success = success;
            this.message = message;
            this.matchingResult = matchingResult;
        }
        
        public static ProcessingResult success(MatchingResult result) {
            return new ProcessingResult(true, "Success", result);
        }
        
        public static ProcessingResult rejected(String reason) {
            return new ProcessingResult(false, reason, null);
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public MatchingResult getMatchingResult() { return matchingResult; }
    }
    
    private static class ValidationResult {
        private final boolean valid;
        private final String reason;
        
        private ValidationResult(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }
        
        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }
        
        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }
        
        public boolean isValid() { return valid; }
        public String getReason() { return reason; }
    }
    
    public interface EventListener {
        void onEvent(Event event);
    }
} 