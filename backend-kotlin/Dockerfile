FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
COPY gradle gradle
COPY gradlew gradlew
COPY build.gradle.kts build.gradle.kts
COPY settings.gradle.kts settings.gradle.kts
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q
COPY src src
RUN ./gradlew shadowJar --no-daemon -q && \
    find build/libs -name "*.jar" ! -name "*-plain.jar" | head -1 | xargs -I{} cp {} build/libs/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/build/libs/app.jar app.jar
EXPOSE 8000
CMD ["java", "-jar", "app.jar"]
