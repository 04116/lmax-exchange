-- LMAX Exchange Database Schema
-- Optimized for high-performance single-threaded batching

-- Create custom enums
CREATE TYPE order_type AS ENUM ('MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT');
CREATE TYPE order_side AS ENUM ('BUY', 'SELL');
CREATE TYPE order_status AS ENUM ('PENDING', 'FILLED', 'PARTIALLY_FILLED', 'CANCELLED', 'REJECTED');

-- Orders table - optimized for batched inserts
CREATE TABLE orders (
    order_id BIGINT PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    order_type order_type NOT NULL,
    side order_side NOT NULL,
    price DECIMAL(15,6),
    quantity BIGINT NOT NULL,
    remaining_qty BIGINT NOT NULL,
    status order_status NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Trades table - optimized for batched inserts
CREATE TABLE trades (
    trade_id BIGINT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    price DECIMAL(15,6) NOT NULL,
    quantity BIGINT NOT NULL,
    buyer_id VARCHAR(100) NOT NULL,
    seller_id VARCHAR(100) NOT NULL,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Performance-optimized indexes
CREATE INDEX CONCURRENTLY idx_orders_symbol_status ON orders(symbol, status) WHERE status IN ('PENDING', 'PARTIALLY_FILLED');
CREATE INDEX CONCURRENTLY idx_orders_user_symbol ON orders(user_id, symbol);
CREATE INDEX CONCURRENTLY idx_orders_created_at ON orders(created_at DESC);

CREATE INDEX CONCURRENTLY idx_trades_symbol_executed_at ON trades(symbol, executed_at DESC);
CREATE INDEX CONCURRENTLY idx_trades_executed_at ON trades(executed_at DESC);

-- Partitioning for large datasets (optional, for future scaling)
-- CREATE TABLE orders_2024 PARTITION OF orders FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
-- CREATE TABLE trades_2024 PARTITION OF trades FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');

-- Optimized sequences for high-throughput
CREATE SEQUENCE order_id_seq
    START WITH 1
    INCREMENT BY 1
    CACHE 1000;

CREATE SEQUENCE trade_id_seq
    START WITH 1
    INCREMENT BY 1
    CACHE 1000;

-- Performance monitoring views
CREATE VIEW order_stats AS
SELECT 
    symbol,
    order_type,
    side,
    status,
    COUNT(*) as count,
    AVG(quantity) as avg_quantity,
    SUM(quantity) as total_quantity
FROM orders 
GROUP BY symbol, order_type, side, status;

CREATE VIEW trade_stats AS
SELECT 
    symbol,
    COUNT(*) as trade_count,
    SUM(quantity) as total_volume,
    AVG(price) as avg_price,
    MIN(price) as min_price,
    MAX(price) as max_price,
    DATE_TRUNC('hour', executed_at) as hour_bucket
FROM trades 
GROUP BY symbol, DATE_TRUNC('hour', executed_at)
ORDER BY hour_bucket DESC;

-- Insert some initial test data
INSERT INTO orders (order_id, user_id, symbol, order_type, side, price, quantity, remaining_qty, status) VALUES
(1, 'test_user_1', 'BTCUSD', 'LIMIT', 'BUY', 45000.00, 100, 100, 'PENDING'),
(2, 'test_user_2', 'BTCUSD', 'LIMIT', 'SELL', 45001.00, 50, 50, 'PENDING'),
(3, 'test_user_3', 'ETHUSD', 'LIMIT', 'BUY', 3000.00, 200, 200, 'PENDING');

-- Grant permissions
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO lmax_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO lmax_user;

-- Performance optimization settings
ALTER TABLE orders SET (fillfactor = 90);
ALTER TABLE trades SET (fillfactor = 100);

-- Analyze tables for optimal query planning
ANALYZE orders;
ANALYZE trades; 