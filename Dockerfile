# syntax=docker/dockerfile:1
FROM node:24-bookworm@sha256:7b559cf7f77d837192392499a850abb1a3d1f49fbc2bd0692d54b212c9e38f6d AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:25-jdk-noble@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e AS backend
WORKDIR /workspace
COPY . ./
COPY --from=frontend /frontend/dist /frontend-build
WORKDIR /workspace/backend
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew --no-daemon --max-workers=2 -PfrontendBuildOutput=/frontend-build bootJar && \
    mkdir -p /healthcheck && javac -d /healthcheck /workspace/docker/healthcheck/Healthcheck.java

FROM eclipse-temurin:25-jre-noble@sha256:3f2223fe04b750aa5a8179e29b5c9a1ddf7665fc9bb08bfe11ad01d143bc13a5 AS runtime
WORKDIR /app
RUN apt-get update && \
    apt-get install --only-upgrade --yes --no-install-recommends libbrotli1 && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system --gid 10001 app && \
    useradd --system --uid 10001 --gid 10001 --no-create-home app && \
    mkdir /data && chown 10001:10001 /data
COPY --from=backend --chown=10001:10001 /workspace/backend/build/libs/running-dashboard-backend-0.1.0-SNAPSHOT.jar /app/app.jar
COPY --from=backend --chown=10001:10001 /healthcheck/Healthcheck.class /healthcheck/Healthcheck.class
ENV SPRING_PROFILES_ACTIVE=container \
    DATA_DIRECTORY=/data
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
