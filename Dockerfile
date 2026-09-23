# Build stage
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies || true
COPY src ./src
RUN ./gradlew --no-daemon fatJar

# Runtime stage
# Debian, not Alpine: sqlite-jdbc ships a native library that misbehaves on musl,
# and it fails at the first query rather than at build time.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# The application does not need root, and a web process that holds it turns any
# remote code execution into full control of the container. It cannot simply be
# declared with USER, though: Fly mounts the data volume at container start, root-owned,
# so the entrypoint drops privileges after fixing the mount. setpriv comes from
# util-linux, which is already in the base image.
RUN useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin app

COPY --from=build /src/build/libs/app.jar ./app.jar
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh && chown app:app /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
ENV DB_PATH=/data/budget.db
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
