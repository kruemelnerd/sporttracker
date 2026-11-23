# syntax=docker/dockerfile:1
FROM node:26-bookworm@sha256:b7087bc4d973034b30854b378ba107f9fab622b47514cb7e2592e41b8bab8d49 AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:25-jdk-noble@sha256:4ddc1a8bdaf283421df5487411aff10b797ccf1e06a339041005114c8f489b5c AS backend
WORKDIR /workspace
COPY . ./
COPY --from=frontend /frontend/dist /frontend-build
WORKDIR /workspace/backend
ARG BUILD_COMMIT=""
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew --no-daemon --max-workers=2 -PbuildCommit="$BUILD_COMMIT" -PfrontendBuildOutput=/frontend-build bootJar && \
    mkdir -p /healthcheck && javac -d /healthcheck /workspace/docker/healthcheck/Healthcheck.java

FROM eclipse-temurin:25-jre-noble@sha256:f3dd0838e69b881cc382237d5901e74ae3705b1056ad964b2e0e523b174fef99 AS runtime
WORKDIR /app
RUN sed -i 's|http://archive.ubuntu.com/|https://archive.ubuntu.com/|; s|http://security.ubuntu.com/|https://security.ubuntu.com/|' /etc/apt/sources.list.d/ubuntu.sources && \
    apt-get -o Acquire::Retries=0 update && \
    apt-get -o Acquire::Retries=0 install --only-upgrade --yes --no-install-recommends apt libapt-pkg6.0t64 libbrotli1 && \
    dpkg-query -W apt libapt-pkg6.0t64 | cut -f2 | while read -r version; do dpkg --compare-versions "$version" ge 2.8.4ubuntu0.1 || exit 1; done && \
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
