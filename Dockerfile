# syntax = docker/dockerfile:1.4
FROM azul/zulu-openjdk-alpine:17 as builder
LABEL maintainer="dlmcpaul@gmail.com"

ARG JAR_FILE
COPY ${JAR_FILE} /app.jar

# Generate a JDK class data share and
# Explode Uber jar into lib jars and classes
RUN "$JAVA_HOME/bin/java" -Xshare:dump && \
    "$JAVA_HOME/bin/jar" -xf app.jar

FROM azul/zulu-openjdk-alpine:17.0.6-17.40.19-jre-x86
LABEL maintainer="dlmcpaul@gmail.com"

COPY --from=builder "./BOOT-INF/lib" /app/lib
COPY --from=builder "./META-INF" /app/META-INF
COPY --from=builder "./BOOT-INF/classes" /app
COPY --from=builder "${JAVA_HOME}/lib/server/classes.jsa" "${JAVA_HOME}/lib/server"

RUN <<EOF
#!/bin/sh
if [ -f "/internal_db/solar_stats_db.mv.db" ]; then
  echo "V1 of H2 database found running upgrade"

  # Download migration tool
  curl -Lo H2MigrationTool.jar https://github.com/manticore-projects/H2MigrationTool/releases/download/1.2/H2MigrationTool-all.jar

  # rename as a backup file
  mv /internal_db/solar_stats_db.mv.db /internal_db/solar_stats_db_v1.mv.db

  # convert database
  echo "Converting H2 database to V2"
  java -jar H2MigrationTool-all.jar -f 1.4.200 -t 2.1.210 -d /internal_db/solar_stats_db_v1.mv.db

  #rename converted file to new database name
  mv /internal_db/solar_stats_db_v1.210null.mv.db /internal_db/solar_stats_db_v2.mv.db

  echo "Upgrade completed"
fi
EOF

ENV SPRING_DATASOURCE_URL=jdbc:h2:/internal_db/solar_stats_db_v2
RUN mkdir "/properties" && \
    touch "/properties/application.properties"

ENTRYPOINT ["java", "-cp", "app:app/lib/*", "-Xshare:auto", "-Djava.security.egd=file:/dev/./urandom", "-Dspring.jmx.enabled=false", "com.hz.EnphaseCollectorApplication", "--spring.config.additional-location=file:/properties/application.properties"]

EXPOSE 8080

VOLUME /internal_db /properties