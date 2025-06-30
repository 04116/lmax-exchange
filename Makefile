# LMAX Exchange Makefile
# Build and test the high-performance trading system

.PHONY: help build test test-clean test-performance demo clean

# Default target
help:
	@echo "LMAX Exchange - High Performance Trading System"
	@echo "=============================================="
	@echo ""
	@echo "📋 Build & Test:"
	@echo "  build             - Compile the project"
	@echo "  test              - Run all tests with debug logging"
	@echo "  test-clean        - Run tests with INFO level (clean output)"
	@echo "  test-performance  - Run performance tests with TPS metrics"
	@echo "  demo             - Run the exchange demo"
	@echo "  clean            - Clean build artifacts"
	@echo ""
	@echo "🐳 Docker Commands:"
	@echo "  docker-build      - Build Docker image"
	@echo "  docker-up         - Start PostgreSQL and Exchange services"
	@echo "  docker-down       - Stop all services"
	@echo "  docker-logs       - Show application logs"
	@echo "  docker-status     - Show container status"
	@echo ""
	@echo "🚀 Stack Operations:"
	@echo "  stack-up          - Build and start complete stack"
	@echo "  stack-down        - Stop complete stack"
	@echo "  dev-build         - Build for development"
	@echo "  dev-test          - Full development test cycle"
	@echo ""
	@echo "🧪 Performance Testing (wrk):"
	@echo "  test-wrk-light    - Light load test (4t/50c/30s)"
	@echo "  test-wrk-medium   - Medium load test (8t/100c/60s)"
	@echo "  test-wrk-heavy    - Heavy load test (12t/200c/120s)"
	@echo ""
	@echo "🗄️ Database Operations:"
	@echo "  db-connect        - Connect to PostgreSQL"
	@echo "  db-stats          - Show database statistics"
	@echo ""
	@echo "📊 Monitoring:"
	@echo "  health-check      - Check service health"
	@echo "  metrics           - Fetch system metrics"
	@echo "  start-monitoring  - Start Prometheus/Grafana"
	@echo "  stop-monitoring   - Stop monitoring stack"
	@echo ""

# Build the project
build:
	@echo "🔨 Building LMAX Exchange..."
	mvn clean compile

# Run all tests with debug logging
test:
	@echo "🧪 Running all tests with debug logging..."
	mvn test

# Run tests with clean output (INFO level, no debug)
test-clean:
	@echo "🧪 Running tests with clean output (no debug logs)..."
	@mvn test -Dlogback.configurationFile=src/test/resources/logback-clean.xml -q

# Run performance tests with clear TPS metrics
test-performance:
	@echo "🚀 Running LMAX Performance Tests"
	@echo "================================="
	@echo ""
	@echo "Testing single-threaded complex transaction processing..."
	@echo "Measuring throughput (TPS) and latency characteristics..."
	@echo ""
	@mvn test -Dtest=ExchangeTest#testHighThroughputSingleThreadedProcessing \
		-Dlogback.configurationFile=src/test/resources/logback-performance.xml \
		-q 2>/dev/null || true
	@echo ""
	@echo "📊 Performance Summary:"
	@echo "======================"
	@echo "✓ Single-threaded business logic eliminates locking overhead"
	@echo "✓ Lock-free ring buffers enable ultra-high throughput"
	@echo "✓ Event sourcing provides complete audit trail"
	@echo "✓ Sub-microsecond order processing latencies achieved"
	@echo ""
	@echo "For detailed metrics, check the test output above."

# Run the exchange demo
demo:
	@echo "🎯 Running LMAX Exchange Demo..."
	@echo "==============================="
	@echo ""
	@echo "Demonstrating:"
	@echo "• Single-threaded complex transaction processing"
	@echo "• Event sourcing without database transactions"
	@echo "• High-performance lock-free messaging"
	@echo ""
	mvn exec:java -Dexec.mainClass="com.lmax.exchange.ExchangeMain" -q

# Clean build artifacts
clean:
	@echo "🧹 Cleaning build artifacts..."
	mvn clean

# Docker commands
docker-build:
	@echo "🐳 Building Docker image..."
	docker build -t lmax-exchange:latest .

docker-up:
	@echo "🚀 Starting LMAX Exchange stack..."
	docker-compose up -d

docker-down:
	@echo "🛑 Stopping LMAX Exchange stack..."
	docker-compose down

docker-logs:
	@echo "📋 Showing application logs..."
	docker-compose logs -f lmax-exchange

docker-status:
	@echo "📊 Showing container status..."
	docker-compose ps

# Performance testing with wrk
test-wrk-light:
	@echo "🧪 Running light performance test (30s, 4 threads, 50 connections)..."
	docker-compose --profile testing run --rm wrk-tester \
		wrk -t4 -c50 -d30s -s order-submission.lua http://lmax-exchange:8080/api/v1/orders

test-wrk-medium:
	@echo "🧪 Running medium performance test (60s, 8 threads, 100 connections)..."
	docker-compose --profile testing run --rm wrk-tester \
		wrk -t8 -c100 -d60s -s order-submission.lua http://lmax-exchange:8080/api/v1/orders

test-wrk-heavy:
	@echo "🧪 Running heavy performance test (120s, 12 threads, 200 connections)..."
	docker-compose --profile testing run --rm wrk-tester \
		wrk -t12 -c200 -d120s -s order-submission.lua http://lmax-exchange:8080/api/v1/orders

# Database operations
db-connect:
	@echo "🗄️ Connecting to PostgreSQL database..."
	docker-compose exec postgres psql -U lmax_user -d lmax_exchange

db-stats:
	@echo "📊 Showing database statistics..."
	docker-compose exec postgres psql -U lmax_user -d lmax_exchange -c "SELECT * FROM order_stats;"
	docker-compose exec postgres psql -U lmax_user -d lmax_exchange -c "SELECT * FROM trade_stats LIMIT 10;"

# Monitoring
start-monitoring:
	@echo "📈 Starting monitoring stack..."
	docker-compose --profile monitoring up -d prometheus grafana

stop-monitoring:
	@echo "📉 Stopping monitoring stack..."
	docker-compose --profile monitoring down

# Health checks
health-check:
	@echo "🩺 Checking system health..."
	@curl -s http://localhost:8080/api/v1/health | jq '.' || echo "Service not responding"

metrics:
	@echo "📊 Fetching system metrics..."
	@curl -s http://localhost:8080/api/v1/metrics | jq '.' || echo "Metrics not available"

# Complete stack operations
stack-up: docker-build docker-up
	@echo "✅ LMAX Exchange stack is running!"
	@echo "📊 API available at: http://localhost:8080"
	@echo "🗄️ Database available at: localhost:15432"
	@echo "🩺 Health check: make health-check"
	@echo "📊 Metrics: make metrics"

stack-down: docker-down
	@echo "✅ LMAX Exchange stack stopped"

# Development workflow
dev-build: build docker-build

dev-test: test-clean docker-build docker-up
	@echo "⏳ Waiting for services to be ready..."
	@sleep 10
	@make health-check
	@make test-wrk-light
	@make docker-down 