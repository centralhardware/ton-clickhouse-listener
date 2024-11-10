FROM gradle:jdk22-graal as gradle

COPY ./ ./

RUN gradle shadowJar

FROM findepi/graalvm:java22

WORKDIR /bot

COPY --from=gradle /home/gradle/build/libs/shadow-1.0-SNAPSHOT-all.jar .

CMD ["java", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-jar", "shadow-1.0-SNAPSHOT-all.jar" ]