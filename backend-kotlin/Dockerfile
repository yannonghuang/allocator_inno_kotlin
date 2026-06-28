FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
# Optional build-time HTTP(S) proxy for JVM tooling. Java's java.net ignores the
# HTTP(S)_PROXY env vars Docker injects, so the Gradle wrapper bootstrap and
# dependency resolution need the proxy as JVM system properties. Empty by default
# (direct egress). Example:
#   --build-arg GRADLE_PROXY_OPTS="-Dhttps.proxyHost=host.docker.internal -Dhttps.proxyPort=7890 -Dhttp.proxyHost=host.docker.internal -Dhttp.proxyPort=7890"
ARG GRADLE_PROXY_OPTS=""
ENV JAVA_TOOL_OPTIONS="${GRADLE_PROXY_OPTS}"
COPY gradle gradle
COPY gradlew gradlew
COPY build.gradle.kts build.gradle.kts
COPY settings.gradle.kts settings.gradle.kts
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q
ARG CACHEBUST=1
COPY src src
RUN ./gradlew shadowJar --no-daemon -q && \
    find build/libs -name "*.jar" ! -name "*-plain.jar" | head -1 | xargs -I{} cp {} build/libs/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/build/libs/app.jar app.jar
EXPOSE 8000
CMD ["java", "-Xmx12g", "-jar", "app.jar"]
