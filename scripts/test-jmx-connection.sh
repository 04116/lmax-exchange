#!/bin/bash

# LMAX Exchange JMX Connection Test Script

echo "=== LMAX Exchange JMX Connectivity Test ==="
echo ""

# Test JMX port accessibility
echo "1. Testing JMX port accessibility..."
if nc -z localhost 9999 >/dev/null 2>&1; then
    echo "✅ JMX port 9999 is accessible"
else
    echo "❌ JMX port 9999 is not accessible"
    exit 1
fi

echo ""

# Test RMI port accessibility
echo "2. Testing RMI port accessibility..."
if nc -z localhost 9998 >/dev/null 2>&1; then
    echo "✅ RMI port 9998 is accessible"
else
    echo "❌ RMI port 9998 is not accessible"
    exit 1
fi

echo ""

# Test application health
echo "3. Testing application health..."
if curl -f http://localhost:8080/api/v1/health >/dev/null 2>&1; then
    echo "✅ Application is healthy and responding"
else
    echo "❌ Application is not responding"
    exit 1
fi

echo ""

# Show JMX connection info
echo "4. JMX Connection Information:"
echo "   JMX URL: service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi"
echo "   RMI Port: 9998"
echo "   Host: localhost"
echo ""

# VisualVM connection instructions
echo "5. VisualVM Connection Instructions:"
echo "   a. Open VisualVM"
echo "   b. Right-click 'Local' in the left panel"
echo "   c. Select 'Add JMX Connection'"
echo "   d. Enter connection: localhost:9999"
echo "   e. Click 'OK'"
echo ""

# JConsole connection instructions
echo "6. JConsole Connection Instructions:"
echo "   Run: jconsole localhost:9999"
echo "   Or use: make monitor-jmx"
echo ""

# Container resource usage
echo "7. Current Resource Usage:"
docker stats lmax-exchange --no-stream --format "table {{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}"

echo ""
echo "🎯 All monitoring connections are ready!"
echo "   High CPU usage (300-500%) is normal for LMAX disruptor architecture"
echo "   Use VisualVM to analyze CPU hotspots and memory allocation patterns" 