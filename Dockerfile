FROM gradle:8.14-jdk21@sha256:94452354d9218922457d82e85a343391bab351e7f518f6f5ab1db996967d238b AS build
WORKDIR /workspace
COPY . .
RUN gradle installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699
WORKDIR /app
RUN apk upgrade --no-cache \
    && addgroup -S app \
    && adduser -S app -G app
COPY --from=build --chown=app:app /workspace/build/install/super-planner-ai-gateway/ ./
ENV PORT=8080
EXPOSE 8080
USER app
ENTRYPOINT ["./bin/super-planner-ai-gateway"]
