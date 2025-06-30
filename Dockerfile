# Multi-stage Dockerfile for LMAX Exchange
# Stage 1: Build
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /app
COPY pom.xml .
COPY src/ src/

# Build the application
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy

# Install required packages
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -g 1001 lmax && \
    useradd -u 1001 -g lmax -m lmax

# Create application directory
WORKDIR /opt/lmax-exchange

# Copy the built jar from build stage
COPY --from=build /app/target/lmax-exchange-1.0-SNAPSHOT.jar app.jar

# Copy configuration files
COPY docker/logback-docker.xml logback-docker.xml

# Set ownership
RUN chown -R lmax:lmax /opt/lmax-exchange

# Switch to non-root user
USER lmax

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/api/v1/health || exit 1

# Expose ports
EXPOSE 8080 9999 9998

# Environment variables
ENV JAVA_OPTS="-server \
    -Xms1g -Xmx2g \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=10 \
    -XX:+UseStringDeduplication \
    -Dcom.sun.management.jmxremote=true \
    -Dcom.sun.management.jmxremote.port=9999 \
    -Dcom.sun.management.jmxremote.rmi.port=9998 \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.ssl=false \
    -Djava.rmi.server.hostname=localhost \
    -Dcom.sun.management.jmxremote.local.only=false"
ENV DB_URL="jdbc:postgresql://postgres:5432/lmax_exchange"
ENV DB_USERNAME="lmax_user"
ENV DB_PASSWORD="lmax_password"
ENV HTTP_PORT="8080"
ENV LOG_LEVEL="INFO"

# Start command
CMD ["sh", "-c", "java $JAVA_OPTS -Dlogback.configurationFile=logback-docker.xml -jar app.jar server"] 