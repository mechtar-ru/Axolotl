FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# Plugin bridge stage
FROM oven/bun:1.3 AS plugin-stage
WORKDIR /plugins
COPY plugins/package.json plugins/bun.lock ./
RUN bun install --frozen-lockfile --production
COPY plugins/plugin-bridge.js ./

# Final runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

# Install Bun in final image for plugin bridge
COPY --from=oven/bun:1.3 /usr/local/bin/bun /usr/local/bin/bun

# Copy plugins
COPY --from=plugin-stage /plugins /app/plugins

# Copy backend jar
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
