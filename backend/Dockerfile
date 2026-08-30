# ============================================
# Stage 1: Build JAR với Maven
# ============================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy pom.xml và src
COPY pom.xml ./
COPY src ./src

# Download Maven and build
RUN apk add --no-cache maven && \
    mvn clean package -DskipTests

# Stage 2: Runtime Image (Siêu nhẹ & Bảo mật)
# ============================================
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Tạo user không phải root để bảo mật
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy file .jar từ Stage 1 với đúng quyền sở hữu chown
COPY --from=builder --chown=spring:spring /app/target/*.jar app.jar

EXPOSE 8080

# Healthcheck endpoint cho Spring Actuator
HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
