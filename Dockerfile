FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get upgrade -y \
    && rm -f /usr/bin/pebble \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/build/install/super-planner-ai-gateway/ ./
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["./bin/super-planner-ai-gateway"]
