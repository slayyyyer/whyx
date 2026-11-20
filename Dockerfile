# Build the shaded Why jar with the Gradle wrapper, then run the whyx pages on a small JRE image.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY why-lang/gradlew ./
COPY why-lang/gradle ./gradle
COPY why-lang/settings.gradle.kts why-lang/build.gradle.kts why-lang/gradle.properties ./
COPY why-lang/why-gradle-plugin ./why-gradle-plugin
COPY why-lang/src ./src
RUN chmod +x gradlew && ./gradlew --no-daemon shadowJar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system why \
    && useradd --system --gid why --home-dir /app why \
    && mkdir -p /data \
    && chown -R why:why /data
COPY --from=build /src/build/libs/why.jar /app/why.jar
COPY app /app/site
USER why
EXPOSE 10000
# Render routes HTTP to the port named by PORT (10000 by default) and ends TLS at its proxy, so
# the cookies need --secure-cookies. The database lives in /data: on a free plan that folder is
# ephemeral and the migrations reseed the demo on every boot.
CMD ["sh", "-c", "exec java -jar /app/why.jar /app/site --host 0.0.0.0 --port ${PORT:-10000} --db /data/data.db --secure-cookies"]
