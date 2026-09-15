FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/build/install/super-planner-ai-gateway/ ./
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["./bin/super-planner-ai-gateway"]
