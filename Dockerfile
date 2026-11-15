# --- build: compile the jar with the Maven wrapper (tests run separately: they need Docker for Testcontainers) ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# dependencies first, so they are cached until pom.xml changes
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src src
RUN ./mvnw -q -B package -DskipTests && cp target/*.jar app.jar

# --- run: only the JRE and the jar ---
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 lavka
COPY --from=build /app/app.jar app.jar
USER lavka
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
