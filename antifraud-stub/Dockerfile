# ---------- Этап сборки: Maven Wrapper внутри Docker, локально Maven не нужен ----------
# mvnw сам скачивает нужную версию Maven (см. .mvn/wrapper/maven-wrapper.properties).
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build

# Сначала только описание сборки — слой с Maven и зависимостями кешируется между сборками
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN sed -i 's/\r$//' mvnw && sh ./mvnw -B -q dependency:go-offline

COPY src ./src
RUN sh ./mvnw -B -DskipTests clean package

# ---------- Этап запуска ----------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1002 -g root antifraud

COPY --from=build /build/target/antifraud-stub-1.0.0.jar app.jar

USER 1002
EXPOSE 8081

ENV JAVA_OPTS="-Xmx128m -XX:MaxMetaspaceSize=96m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
