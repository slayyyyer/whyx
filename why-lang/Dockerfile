# Build the shaded CLI jar, then run it on a small JRE image.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY site ./site
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system why \
    && useradd --system --gid why --home-dir /app why \
    && mkdir -p /data \
    && chown -R why:why /data
COPY --from=build /src/target/why.jar /app/why.jar
COPY --from=build /src/site /app/site
USER why
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/why.jar", "/app/site", "--host", "0.0.0.0", "--port", "8080", "--db", "/data/data.db"]
