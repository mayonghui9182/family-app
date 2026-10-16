FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY build/install/family-app-daily-oauth/ ./
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin daily
USER daily
ENTRYPOINT ["bin/family-app-daily-oauth"]
