# Build the shaded Why jar, then run the whyx pages on a small JRE image.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY why-lang/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY why-lang/src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system why \
    && useradd --system --gid why --home-dir /app why \
    && mkdir -p /data \
    && chown why:why /data
COPY --from=build /src/target/why.jar /app/why.jar
COPY app /app/site
USER why
EXPOSE 10000
# Render routes HTTP to the port named by PORT (10000 by default) and ends TLS at its proxy, so
# the cookies need --secure-cookies. The database lives in /data: on a free plan that folder is
# ephemeral and the migrations reseed the demo on every boot.
CMD ["sh", "-c", "exec java -jar /app/why.jar /app/site --host 0.0.0.0 --port ${PORT:-10000} --db /data/data.db --secure-cookies"]
