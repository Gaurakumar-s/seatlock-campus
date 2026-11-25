FROM node:22-alpine AS web
WORKDIR /web
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS api
WORKDIR /build
COPY backend/pom.xml ./
COPY backend/src ./src
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S seatlock && adduser -S seatlock -G seatlock
WORKDIR /app
COPY --from=api /build/target/seatlock-1.0.0.jar ./seatlock.jar
USER seatlock
ENV BIND_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/seatlock.jar"]
