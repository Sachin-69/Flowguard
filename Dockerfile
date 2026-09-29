FROM eclipse-temurin:23-jdk AS build
WORKDIR /app
COPY src ./src
COPY public ./public
RUN javac -encoding UTF-8 -d out src/com/flowguard/*.java

FROM eclipse-temurin:23-jre
WORKDIR /app
COPY --from=build /app/out ./out
COPY --from=build /app/public ./public
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-cp", "out", "com.flowguard.FlowGuardApp"]
