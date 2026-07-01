FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/gbn-udp.jar .
# Uso:
#   docker run --rm gbn-udp java -cp gbn-udp.jar gbn.Receptor 5000
#   docker run --rm gbn-udp java -cp gbn-udp.jar gbn.Emissor <args...>
ENTRYPOINT ["java", "-cp", "gbn-udp.jar"]
CMD ["gbn.Receptor", "5000"]
