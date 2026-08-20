# Container image for the public web demo. Build from the repository root, because demo-web is a
# composite build that resolves musicmeta-core from local source:
#
#   docker build -f demo-web/Dockerfile -t musicmeta-demo-web .
#
# Java 17 throughout, matching the toolchain the demo declares.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN cd demo-web && ../gradlew installDist --no-daemon --console=plain

FROM eclipse-temurin:17-jre
# Cloud Run supplies PORT and expects the process to listen on it; the default matches the local
# demo so the image behaves the same under `docker run` with nothing set.
ENV PORT=8080
WORKDIR /app
COPY --from=build /src/demo-web/build/install/musicmeta-demo-web ./
# The start script resolves no relative paths outside /app, so an unprivileged user is enough. The
# demo writes nothing: its cache is in-memory and its secrets arrive as environment variables.
RUN useradd --system --create-home --uid 10001 demo
USER demo
EXPOSE 8080
CMD ["./bin/musicmeta-demo-web"]
