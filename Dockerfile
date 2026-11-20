# Build the runnable whyx jar with the published Why plugin, then run it on a small JRE image.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
COPY app ./app
RUN chmod +x gradlew && ./gradlew --no-daemon shadowJar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system why \
    && useradd --system --gid why --home-dir /app why \
    && mkdir -p /data \
    && chown -R why:why /data
COPY --from=build /src/build/libs/whyx.jar /app/whyx.jar
USER why
EXPOSE 10000
# Render routes HTTP to the port named by PORT (10000 by default) and ends TLS at its proxy, so
# the cookies need --secure-cookies. The database lives in /data: on a free plan that folder is
# ephemeral and the migrations reseed the demo on every boot.
CMD ["sh", "-c", "exec java -jar /app/whyx.jar --host 0.0.0.0 --port ${PORT:-10000} --db /data/data.db --secure-cookies"]
