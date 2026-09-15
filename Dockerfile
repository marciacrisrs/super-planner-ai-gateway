FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/install/super-planner-ai-gateway/ ./
RUN rm -f /usr/bin/pebble
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["./bin/super-planner-ai-gateway"]
