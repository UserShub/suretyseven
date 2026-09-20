# --- Build stage ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# Cache dependencies separately from source so code-only changes don't
# re-download the internet on every build.
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# --- Run stage ---
# Ubuntu/jammy-based rather than alpine: the *-jre-alpine tags for this
# image have had spotty/missing multi-arch manifests (notably on arm64 /
# Apple Silicon), causing "no match for platform in manifest" pulls. jammy
# is a larger image but reliably multi-arch.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd -r app && useradd -r -g app app
COPY --from=build /app/target/*.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
