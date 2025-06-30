package com.lmax.exchange.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * Database configuration for LMAX Exchange PostgreSQL persistence.
 * Optimized for high-throughput single-threaded batching performance.
 */
public class DatabaseConfig {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);
    
    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/lmax_exchange";
    private static final String DEFAULT_USERNAME = "lmax_user";
    private static final String DEFAULT_PASSWORD = "lmax_password";
    
    // Pool size optimized for single-threaded processor with batching
    private static final int MINIMUM_IDLE = 2;
    private static final int MAXIMUM_POOL_SIZE = 4;
    private static final long CONNECTION_TIMEOUT = 30000; // 30 seconds
    private static final long IDLE_TIMEOUT = 600000; // 10 minutes
    private static final long MAX_LIFETIME = 1800000; // 30 minutes
    
    private HikariDataSource dataSource;
    
    public DatabaseConfig() {
        this(DEFAULT_URL, DEFAULT_USERNAME, DEFAULT_PASSWORD);
    }
    
    public DatabaseConfig(String url, String username, String password) {
        initializeDataSource(url, username, password);
    }
    
    private void initializeDataSource(String url, String username, String password) {
        logger.info("Initializing PostgreSQL connection pool for LMAX Exchange");
        
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        
        // Optimized for high-throughput batching
        config.setMinimumIdle(MINIMUM_IDLE);
        config.setMaximumPoolSize(MAXIMUM_POOL_SIZE);
        config.setConnectionTimeout(CONNECTION_TIMEOUT);
        config.setIdleTimeout(IDLE_TIMEOUT);
        config.setMaxLifetime(MAX_LIFETIME);
        
        // PostgreSQL-specific optimizations
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        config.addDataSourceProperty("defaultAutoCommit", "false");
        
        // Connection validation
        config.setConnectionTestQuery("SELECT 1");
        config.setValidationTimeout(5000);
        
        this.dataSource = new HikariDataSource(config);
        
        logger.info("PostgreSQL connection pool initialized with {} max connections", MAXIMUM_POOL_SIZE);
    }
    
    public DataSource getDataSource() {
        return dataSource;
    }
    
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("Closing PostgreSQL connection pool");
            dataSource.close();
        }
    }
    
    // Environment-based configuration
    public static DatabaseConfig fromEnvironment() {
        String url = System.getenv().getOrDefault("DB_URL", DEFAULT_URL);
        String username = System.getenv().getOrDefault("DB_USERNAME", DEFAULT_USERNAME);
        String password = System.getenv().getOrDefault("DB_PASSWORD", DEFAULT_PASSWORD);
        
        return new DatabaseConfig(url, username, password);
    }
} 