FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
RUN groupadd --gid 10001 comanda && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin comanda
WORKDIR /opt/comanda
# Build this distribution only through scripts/dgradle before building this image.
COPY --chown=10001:10001 ./ ./
USER 10001:10001
ENV JAVA_OPTS="-Xms64m -Xmx256m -XX:MaxDirectMemorySize=32m -XX:MaxMetaspaceSize=80m -XX:ReservedCodeCacheSize=32m -Xss512k -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["/opt/comanda/bin/comanda-relay"]
