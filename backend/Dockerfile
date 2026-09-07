# =====================================================================
# CivicTrack backend image.
#
# Three stages: Maven build, CDS training run, runtime.
#
# The middle stage exists because cold start is this project's single
# largest demo risk. Render's free tier spins an instance down after 15
# minutes idle, and an uninstrumented Spring Boot cold start is 20-40
# seconds -- long enough to lose a room. Class Data Sharing pre-parses the
# class metadata into a memory-mappable archive, which the JVM then loads
# instead of re-parsing every class on every boot.
# =====================================================================

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Dependency resolution is cached as its own layer, so a source-only change
# does not re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------------------------------------------------------------------
# CDS training run.
#
# This stage MUST use the same base image as the runtime stage below. A
# dynamic CDS archive is tied to the base archive of the exact JVM build
# that produced it: train on 21-jdk, run on 21-jre, and the archive is
# rejected at startup. The default -Xshare:auto then falls back silently,
# so the container boots perfectly well with no CDS at all and nothing in
# the logs says so. That is the failure this comment exists to prevent.
#
# There is no database at image-build time, so the 'cds' profile switches
# off Flyway and stops Hibernate dialling out for dialect metadata.
# See application-cds.yml.
# ---------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS cds
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted \
 && cp -r extracted/dependencies/. . \
 && cp -r extracted/spring-boot-loader/. . \
 && cp -r extracted/snapshot-dependencies/. . \
 && cp -r extracted/application/. . \
 && rm -rf extracted app.jar \
 && java -XX:ArchiveClassesAtExit=application.jsa \
         -Dspring.profiles.active=cds \
         -Dspring.context.exit=onRefresh \
         org.springframework.boot.loader.launch.JarLauncher \
 # Assert the archive is actually loadable. -Xshare:on turns a silent
 # fallback into a hard failure, so a broken archive fails the BUILD
 # rather than quietly costing 20 seconds of startup in the demo.
 && java -XX:SharedArchiveFile=application.jsa -Xshare:on \
         -Dspring.profiles.active=cds \
         -Dspring.context.exit=onRefresh \
         org.springframework.boot.loader.launch.JarLauncher \
 && echo "CDS archive verified loadable"

# ---------------------------------------------------------------------
# Runtime. Same base image as the CDS stage, deliberately.
# ---------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=cds --chown=app:app /app/ ./
USER app
EXPOSE 8080

# MaxRAMPercentage=75 is not optional. The JVM's default heap sizing inside a
# 512 MB container leaves too little headroom and produces intermittent OOM
# kills that look like random crashes. SerialGC because a single-core free
# instance gains nothing from a concurrent collector's overhead.
#
# SharedArchiveFile is left on the default -Xshare:auto here rather than
# -Xshare:on: in production a graceful fallback beats refusing to boot. The
# build stage above already proved the archive loads, so a fallback at this
# point would mean the runtime JVM differs from the one that trained it.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k -XX:SharedArchiveFile=application.jsa"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
