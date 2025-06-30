package com.lmax.exchange.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lmax.exchange.ExchangeMain;
import com.lmax.exchange.core.BusinessLogicProcessor;
import com.lmax.exchange.domain.Order;
import com.lmax.exchange.domain.Trade;
import com.lmax.exchange.persistence.BatchedPersistenceProcessor;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance Vert.x HTTP server for LMAX Exchange.
 * Provides REST API for order submission and market data.
 */
public class ExchangeHttpServer extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(ExchangeHttpServer.class);
    private static final Logger perfLogger = LoggerFactory.getLogger("PERFORMANCE");
    
    private final int port;
    private final BusinessLogicProcessor processor;
    private final BatchedPersistenceProcessor persistenceProcessor;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestCounter;
    private HttpServer server;
    
    public ExchangeHttpServer(int port, BusinessLogicProcessor processor, 
                            BatchedPersistenceProcessor persistenceProcessor) {
        this.port = port;
        this.processor = processor;
        this.persistenceProcessor = persistenceProcessor;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.requestCounter = new AtomicLong(0);
    }
    
    @Override
    public void start(Promise<Void> startPromise) {
        Router router = createRouter();
        
        vertx.createHttpServer()
            .requestHandler(router)
            .listen(port)
            .onSuccess(httpServer -> {
                this.server = httpServer;
                logger.info("LMAX Exchange HTTP server started on port {}", port);
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }
    
    @Override
    public void stop(Promise<Void> stopPromise) {
        if (server != null) {
            server.close()
                .onSuccess(v -> {
                    logger.info("LMAX Exchange HTTP server stopped");
                    stopPromise.complete();
                })
                .onFailure(stopPromise::fail);
        } else {
            stopPromise.complete();
        }
    }
    
    private Router createRouter() {
        Router router = Router.router(vertx);
        
        // Enable CORS
        router.route().handler(CorsHandler.create("*")
            .allowedMethod(io.vertx.core.http.HttpMethod.GET)
            .allowedMethod(io.vertx.core.http.HttpMethod.POST)
            .allowedMethod(io.vertx.core.http.HttpMethod.PUT)
            .allowedMethod(io.vertx.core.http.HttpMethod.DELETE)
            .allowedHeader("Content-Type")
            .allowedHeader("Authorization"));
        
        // Body handler for JSON
        router.route().handler(BodyHandler.create().setBodyLimit(1024 * 1024)); // 1MB limit
        
        // Request logging and metrics
        router.route().handler(this::logRequest);
        
        // API routes
        router.post("/api/v1/orders").handler(this::submitOrder);
        router.get("/api/v1/orders/:symbol").handler(this::getOrders);
        router.get("/api/v1/trades/:symbol").handler(this::getTrades);
        router.get("/api/v1/markets/:symbol").handler(this::getMarketData);
        router.get("/api/v1/health").handler(this::healthCheck);
        router.get("/api/v1/metrics").handler(this::getMetrics);
        
        // Static content (for web UI)
        router.route("/ui/*").handler(StaticHandler.create("webroot/ui"));
        router.route("/").handler(ctx -> ctx.response().putHeader("Location", "/ui/").setStatusCode(302).end());
        
        // Error handling
        router.route().failureHandler(this::handleError);
        
        return router;
    }
    
    private void logRequest(RoutingContext ctx) {
        long requestId = requestCounter.incrementAndGet();
        ctx.put("requestId", requestId);
        ctx.put("startTime", System.nanoTime());
        
        if (requestId % 10000 == 0) {
            perfLogger.info("Processed {} HTTP requests", requestId);
        }
        
        ctx.next();
    }
    
    private void submitOrder(RoutingContext ctx) {
        try {
            JsonObject body = ctx.body().asJsonObject();
            
            // Parse order request
            String user = body.getString("user");
            String symbol = body.getString("symbol");
            String typeStr = body.getString("type");
            String sideStr = body.getString("side");
            BigDecimal price = body.getString("price") != null ? 
                new BigDecimal(body.getString("price")) : null;
            int quantity = body.getInteger("qty");
            
            // Validate required fields
            if (user == null || symbol == null || typeStr == null || sideStr == null || quantity <= 0) {
                respondWithError(ctx, 400, "Missing or invalid required fields");
                return;
            }
            
            Order.OrderType type = Order.OrderType.valueOf(typeStr.toUpperCase());
            Order.OrderSide side = Order.OrderSide.valueOf(sideStr.toUpperCase());
            
            // Market orders don't need price
            if (type == Order.OrderType.LIMIT && price == null) {
                respondWithError(ctx, 400, "Price required for limit orders");
                return;
            }
            
            // Submit order to business logic processor
            var result = processor.processOrder(user, symbol, type, side, price, quantity, Order.TimeInForce.GTC);
            
            if (result.isSuccess()) {
                JsonObject response = new JsonObject()
                    .put("status", "submitted")
                    .put("timestamp", Instant.now().toString());
                
                ctx.response()
                    .setStatusCode(202)
                    .putHeader("Content-Type", "application/json")
                    .end(response.encode());
                    
                logRequestCompleted(ctx, 202);
            } else {
                respondWithError(ctx, 503, "Order submission failed - system overloaded");
            }
            
        } catch (IllegalArgumentException e) {
            respondWithError(ctx, 400, "Invalid order parameters: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error submitting order", e);
            respondWithError(ctx, 500, "Internal server error");
        }
    }
    
    private void getOrders(RoutingContext ctx) {
        try {
            String symbol = ctx.pathParam("symbol");
            var allOrders = processor.getActiveOrders();
            List<Order> orders = allOrders.values().stream()
                .filter(order -> order.getSymbol().equals(symbol))
                .toList();
            
            JsonObject response = new JsonObject()
                .put("symbol", symbol)
                .put("orders", orders.size())
                .put("data", objectMapper.writeValueAsString(orders));
            
            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
                
            logRequestCompleted(ctx, 200);
            
        } catch (Exception e) {
            logger.error("Error getting orders", e);
            respondWithError(ctx, 500, "Internal server error");
        }
    }
    
    private void getTrades(RoutingContext ctx) {
        try {
            String symbol = ctx.pathParam("symbol");
            List<Trade> trades = processor.getTrades().stream()
                .filter(trade -> trade.getSymbol().equals(symbol))
                .toList();
            
            JsonObject response = new JsonObject()
                .put("symbol", symbol)
                .put("trades", trades.size())
                .put("data", objectMapper.writeValueAsString(trades));
            
            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
                
            logRequestCompleted(ctx, 200);
            
        } catch (Exception e) {
            logger.error("Error getting trades", e);
            respondWithError(ctx, 500, "Internal server error");
        }
    }
    
    private void getMarketData(RoutingContext ctx) {
        try {
            String symbol = ctx.pathParam("symbol");
            var market = processor.getMarket(symbol);
            
            if (market == null) {
                respondWithError(ctx, 404, "Market not found: " + symbol);
                return;
            }
            
            JsonObject response = new JsonObject()
                .put("symbol", symbol)
                .put("status", market.getStatus().name())
                .put("timestamp", Instant.now().toString());
            
            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
                
            logRequestCompleted(ctx, 200);
            
        } catch (Exception e) {
            logger.error("Error getting market data", e);
            respondWithError(ctx, 500, "Internal server error");
        }
    }
    
    private void healthCheck(RoutingContext ctx) {
        JsonObject health = new JsonObject()
            .put("status", "healthy")
            .put("timestamp", Instant.now().toString())
            .put("uptime", System.currentTimeMillis())
            .put("processor", "running")
            .put("persistence", persistenceProcessor != null ? "running" : "disabled");
        
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(health.encode());
            
        logRequestCompleted(ctx, 200);
    }
    
    private void getMetrics(RoutingContext ctx) {
        JsonObject metrics = new JsonObject()
            .put("timestamp", Instant.now().toString())
            .put("http_requests_total", requestCounter.get())
            .put("processed_events", processor.getProcessedEventCount())
            .put("active_orders_total", processor.getActiveOrderCount())
            .put("trades_total", processor.getTrades().size());
        
        if (persistenceProcessor != null) {
            metrics.put("persistence_queue_size", persistenceProcessor.getQueueSize())
                   .put("persistence_batches_total", persistenceProcessor.getBatchCount())
                   .put("persistence_events_total", persistenceProcessor.getProcessedEventCount());
        }
        
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(metrics.encode());
            
        logRequestCompleted(ctx, 200);
    }
    
    private void handleError(RoutingContext ctx) {
        Throwable failure = ctx.failure();
        int statusCode = ctx.statusCode() > 0 ? ctx.statusCode() : 500;
        
        logger.error("HTTP request failed with status {}", statusCode, failure);
        
        if (!ctx.response().ended()) {
            respondWithError(ctx, statusCode, "Request failed");
        }
    }
    
    private void respondWithError(RoutingContext ctx, int statusCode, String message) {
        JsonObject error = new JsonObject()
            .put("error", message)
            .put("status", statusCode)
            .put("timestamp", Instant.now().toString());
        
        ctx.response()
            .setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .end(error.encode());
            
        logRequestCompleted(ctx, statusCode);
    }
    
    private void logRequestCompleted(RoutingContext ctx, int statusCode) {
        Long startTime = ctx.get("startTime");
        if (startTime != null) {
            long duration = System.nanoTime() - startTime;
            double durationMs = duration / 1_000_000.0;
            
            // Log slow requests
            if (durationMs > 100) {
                logger.warn("Slow request: {} {} completed in {:.2f}ms with status {}", 
                           ctx.request().method(), ctx.request().path(), durationMs, statusCode);
            }
        }
    }
    
    public static Future<String> start(Vertx vertx, int port, BusinessLogicProcessor processor, 
                                     BatchedPersistenceProcessor persistenceProcessor) {
        Promise<String> promise = Promise.promise();
        
        ExchangeHttpServer server = new ExchangeHttpServer(port, processor, persistenceProcessor);
        
        vertx.deployVerticle(server)
            .onSuccess(deploymentId -> {
                logger.info("Exchange HTTP server deployed with ID: {}", deploymentId);
                promise.complete(deploymentId);
            })
            .onFailure(promise::fail);
        
        return promise.future();
    }
} 