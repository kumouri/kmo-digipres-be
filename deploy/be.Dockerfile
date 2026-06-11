# Minimal runtime image for kmo-digipres-be (KMOSF CRM, Z2).
# We do NOT use the Spring buildpack (its docker-java client speaks an API version
# Docker Desktop 29 rejects). build-crm.sh stages the fat jar here as be-app.jar
# (gitignored) and the host `docker` CLI builds this tiny JRE image. Same approach
# as repos/demo-sites/infra/be.Dockerfile.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY be-app.jar /app/app.jar
# Security (audit INFRA-03): drop root. The app only READS the jar (group-readable, mode 644)
# and writes transient files to /tmp (java.io.tmpdir, world-writable 1777), so an unprivileged
# uid is sufficient. Numeric uid + root group (10001:0) is OpenShift-friendly, needs no useradd
# layer, and works regardless of whether the uid exists in /etc/passwd. Verify on next deploy.
USER 10001:0
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
