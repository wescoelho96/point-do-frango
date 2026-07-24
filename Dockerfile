FROM maven:3.9.6-eclipse-temurin-17
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean compile

EXPOSE 8080

CMD ["mvn", "exec:java", "-Dexec.mainClass=Main"]
