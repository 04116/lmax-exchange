# LMAX Exchange Monitoring Guide

## Overview

This guide covers monitoring and profiling the LMAX Exchange application using VisualVM, JConsole, and other JVM monitoring tools.

## High CPU Usage Analysis

The LMAX Exchange is designed for high-performance and will typically show high CPU usage (300-500%) due to:

1. **Disruptor Pattern**: Busy-waiting for maximum performance
2. **Multiple Threads**: Input/Output disruptors + HTTP server + persistence
3. **Low-Latency Design**: Optimized for speed over CPU efficiency

## Quick Start Monitoring

### 1. Install VisualVM

```bash
# On macOS
brew install --cask visualvm

# On Linux (Ubuntu/Debian)
sudo apt-get install visualvm

# On Windows
# Download from https://visualvm.github.io/
```

### 2. Start Application with Monitoring

```bash
# Start the application with JMX enabled
make docker-up

# Verify JMX ports are exposed
make monitor-resources
```

### 3. Connect VisualVM

```bash
# Start VisualVM and connect
make monitor-visualvm
```

**Manual Connection:**

1. Open VisualVM
2. Right-click "Local"
3. Add JMX Connection
4. Connection URL: `localhost:9999`
5. Click "OK"

## Monitoring Commands

### Resource Monitoring

```bash
# View container stats
make monitor-resources

# Continuous monitoring
watch -n 2 "make monitor-resources"
```

### JMX Connectivity

```bash
# Test JMX connection
make monitor-jmx

# Connect with JConsole
jconsole localhost:9999
```

### Application Logs

```bash
# Tail logs
make monitor-logs

# View specific log files
docker exec lmax-exchange tail -f /app/logs/application.log
```

### Garbage Collection

```bash
# GC monitoring
make monitor-gc

# Real-time GC stats
docker exec lmax-exchange jstat -gc $(docker exec lmax-exchange pgrep java) 1s
```

### Thread Analysis

```bash
# Thread dump
make monitor-threads

# Continuous thread monitoring
watch -n 5 "make monitor-threads"
```

## VisualVM Analysis Guide

### 1. CPU Profiling

**High CPU Threads to Expect:**

- `InputDisruptor-Thread`: Processing incoming orders
- `OutputDisruptor-Thread`: Processing outbound events
- `BusinessLogic-Thread`: Core matching engine
- `BatchProcessor-Thread`: Database persistence
- `vert.x-eventloop-thread`: HTTP request handling

**Normal CPU Hotspots:**

- `OrderBook.addOrder()`: Order matching logic
- `Disruptor.publishEvent()`: Event publishing
- `ConcurrentHashMap.get/put()`: Fast lookups
- `Ring buffer` operations

### 2. Memory Analysis

**Expected Memory Patterns:**

- **Heap Usage**: 200-500MB steady state
- **Off-Heap**: Disruptor ring buffers
- **GC Frequency**: Short, frequent G1GC pauses (<10ms)

**Memory Hotspots:**

- Order objects in ring buffers
- Trade event objects
- Database connection pools

### 3. Thread Analysis

**Expected Thread Count**: 15-25 threads

- 1x Business Logic processor
- 2x Disruptor threads (Input/Output)
- 1x Persistence thread
- 4-8x HTTP eventloop threads
- 2-4x Database connection threads
- JVM system threads

## Performance Tuning

### JVM Tuning Options

Current settings in `Dockerfile`:

```bash
JAVA_OPTS="-server \
    -Xms1g -Xmx2g \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=10 \
    -XX:+UseStringDeduplication"
```

### Advanced Tuning

```bash
# Low-latency tuning (add to JAVA_OPTS)
-XX:+UnlockExperimentalVMOptions
-XX:+UseZGC                          # Ultra-low latency GC
-XX:+DisableExplicitGC               # Prevent explicit GC calls
-XX:+AlwaysPreTouch                  # Allocate memory upfront

# CPU optimization
-XX:+UseTransparentHugePages         # Large memory pages
-XX:+OptimizeStringConcat            # String optimization

# Debugging (only for development)
-Xlog:gc*:gc.log:time,tags           # Detailed GC logging
-XX:+PrintCompilation                # JIT compilation info
```

## Expected Performance Characteristics

### Normal CPU Usage

- **Load Testing**: 300-500% CPU usage (normal)
- **Idle State**: 50-100% CPU usage (disruptor busy-wait)
- **Peak Load**: Up to 800% CPU usage

### Memory Usage

- **Heap**: 200-800MB depending on load
- **Off-Heap**: 100-200MB (ring buffers)
- **Total**: 1-2GB maximum

### Latency Characteristics

- **Order Processing**: <1ms (99th percentile)
- **Trade Execution**: <0.5ms median
- **HTTP Response**: <50ms (99th percentile)

## Optimization Strategies

### 1. Reduce CPU Usage

```java
// Option 1: Reduce disruptor busy-wait
WaitStrategy.blocking()  // Instead of yielding

// Option 2: Lower ring buffer size
.bufferSize(1024)        // Instead of 65536

// Option 3: Batch processing
.batchSize(100)          // Process multiple events
```

### 2. Database Optimization

```sql
-- Reduce persistence frequency
batch_size = 10000       -- Larger batches
batch_timeout = 1000ms   -- Longer timeout
```

### 3. HTTP Optimization

```java
// Reduce HTTP threads
setWorkerPoolSize(2)     // Instead of 8
```

## Alerts and Monitoring

### Key Metrics to Watch

- **CPU > 600%**: May indicate performance issues
- **Memory > 1.5GB**: Potential memory leak
- **GC Pause > 50ms**: GC tuning needed
- **Thread Count > 50**: Thread leak possible

### Production Monitoring

```bash
# Set up alerts for:
docker stats --format "table {{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}"

# Memory alerts
docker exec lmax-exchange jstat -gc $(pgrep java) | awk '{if($8>1500000) print "HIGH_MEMORY"}'
```

## Troubleshooting

### High CPU Issues

1. Check for infinite loops in business logic
2. Verify disruptor configuration
3. Monitor GC frequency and duration
4. Check database connection pool settings

### Memory Issues

1. Heap dump analysis: `jmap -dump:live,format=b,file=heap.hprof <pid>`
2. Check for object retention in ring buffers
3. Monitor database connection leaks

### Connection Issues

1. Verify JMX ports are accessible: `telnet localhost 9999`
2. Check firewall settings
3. Validate hostname resolution

## Advanced Analysis

### Flight Recorder

```bash
# Enable JFR (add to JAVA_OPTS)
-XX:+FlightRecorder
-XX:StartFlightRecording=duration=60s,filename=lmax.jfr

# Analyze with JMC
jmc lmax.jfr
```

### Native Tools

```bash
# System-level profiling
perf top -p $(docker exec lmax-exchange pgrep java)

# Memory analysis
pmap $(docker exec lmax-exchange pgrep java)
```

This monitoring setup provides comprehensive visibility into the LMAX Exchange performance characteristics and helps optimize for production deployment.
