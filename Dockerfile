# Build the app with Gradle, then run it on a small Java 21 runtime.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Download Gradle and the dependencies first so code changes rebuild faster.
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q installDist -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/install/ob-terminal /app
# Plans, the Upstox login and daily caches live here; mount a volume at /data to keep them across deploys.
ENV DATA_DIR=/data
# Sized for a 0.5 GB container.
ENV JAVA_OPTS="-Xmx256m -Xss512k -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=64m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
CMD ["/app/bin/ob-terminal"]
