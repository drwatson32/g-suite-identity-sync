FROM adoptopenjdk/openjdk11:x86_64-alpine-jdk11u-nightly-slim AS build
MAINTAINER Alexander Rumyankov <alex@rumyankovs.com>

RUN apk -- update \
  && apk add maven \
  && rm -rf /var/cache/apk/*

COPY . /g-suite-identity-sync

RUN cd /g-suite-identity-sync && mvn install

FROM adoptopenjdk/openjdk11:x86_64-alpine-jdk11u-nightly-slim
MAINTAINER Alexander Rumyankov <alex@rumyankovs.com>

ENV KARAF_USER karaf
ENV KARAF_UID 8181
ENV JAVA_MAX_MEM 256m
ENV KARAF_EXEC exec

RUN addgroup -g "$KARAF_UID" "$KARAF_USER" && adduser -h "/home/$KARAF_USER" -g "" -D -G "$KARAF_USER" -u "$KARAF_UID" "$KARAF_USER"

USER $KARAF_USER

COPY --from=build --chown=karaf:karaf /g-suite-identity-sync/distribution/target/assembly/ /opt/karaf

RUN sed -i 's/log4j2.rootLogger.appenderRef.RollingFile/#log4j2.rootLogger.appenderRef.RollingFile/' /opt/karaf/etc/org.ops4j.pax.logging.cfg \
    && sed -i '/felix.fileinstall.log.default/a felix.fileinstall.subdir.mode\ =\ recurse' /opt/karaf/etc/config.properties \
    && sed -i -e '$a log4j2.logger.cxf.name\ =\ org.apache.cxf' /opt/karaf/etc/org.ops4j.pax.logging.cfg \
    && sed -i -e '$a log4j2.logger.cxf.level\ =\ WARN' /opt/karaf/etc/org.ops4j.pax.logging.cfg \
    && mkdir -p /opt/karaf/data /opt/karaf/data/log /opt/karaf/etc/identity \
    && chown -R $KARAF_USER.$KARAF_USER /opt/karaf/data /opt/karaf/data/log /opt/karaf/etc/identity

VOLUME ["/opt/karaf/etc/identity"]

EXPOSE 8101 8181
CMD ["/opt/karaf/bin/karaf", "run"]
