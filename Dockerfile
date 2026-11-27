FROM eclipse-temurin:17-jdk AS builder
WORKDIR /workspace

COPY gradlew* settings.gradle.kts build.gradle.kts gradle.properties release-info.properties ./
COPY gradle ./gradle
COPY core ./core
COPY web ./web
COPY app/build.gradle.kts ./app/build.gradle.kts
COPY desktop/build.gradle.kts ./desktop/build.gradle.kts

RUN apt-get update && apt-get install -y --no-install-recommends curl unzip && rm -rf /var/lib/apt/lists/* \
    && curl -sSL https://services.gradle.org/distributions/gradle-9.3.1-bin.zip -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && rm -f /tmp/gradle.zip \
    && /opt/gradle-9.3.1/bin/gradle --no-daemon :web:packageWebApp --max-workers=1

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN useradd --create-home --shell /bin/bash mediax
COPY --from=builder /workspace/web/build/web-dist/lib ./lib
RUN chown -R mediax:mediax /app

USER mediax
ENV PORT=8080
EXPOSE 8080

CMD ["sh", "-c", "exec java -Xms64m -Xmx512m -Dfile.encoding=UTF-8 -cp '/app/lib/*' com.example.web.MainKt"]
