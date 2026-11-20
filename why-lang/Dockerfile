# Build the shaded CLI jar with the Gradle wrapper, then run it on a small JRE image.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY why-gradle-plugin ./why-gradle-plugin
COPY src ./src
COPY site ./site
RUN chmod +x gradlew && ./gradlew --no-daemon shadowJar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system why \
    && useradd --system --gid why --home-dir /app why \
    && mkdir -p /data \
    && chown -R why:why /data
COPY --from=build /src/build/libs/why.jar /app/why.jar
COPY --from=build /src/site /app/site
USER why
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/why.jar", "/app/site", "--host", "0.0.0.0", "--port", "8080", "--db", "/data/data.db"]
