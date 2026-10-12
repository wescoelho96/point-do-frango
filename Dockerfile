# Etapa 1: build (Maven + JDK, presentes apenas na imagem intermediária)
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
# Dependências em camada própria: o cache só é invalidado quando o pom.xml muda
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# Etapa 2: runtime (apenas JRE + .jar, resultando em uma imagem menor)
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# Executa com usuário sem privilégios (não root)
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /app/target/point-do-frango.jar app.jar

# Instância pequena (pouca CPU): só o compilador C1 e o coletor serial encurtam a subida da aplicação
# e evitam o tempo limite do deploy; para este volume de uso a diferença de desempenho não aparece.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k -Duser.timezone=America/Sao_Paulo"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
