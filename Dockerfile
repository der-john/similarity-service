FROM maven:3.5-jdk-8

ARG APP_VERSION
COPY . /usr/src/app
WORKDIR /usr/src/app
RUN rm -rf target
ENV MAVEN_OPTS=-Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn
RUN mvn --batch-mode -f pom.xml -s maven-settings.xml org.codehaus.mojo:versions-maven-plugin:2.1:set -DnewVersion=$APP_VERSION 1>/dev/null 2>/dev/null
RUN mvn --batch-mode -Dproject.version="$APP_VERSION" -s maven-settings.xml package
RUN cp target/*-*.jar target/service.jar
ENV JAVA_OPTS ""
CMD java $JAVA_OPTS -jar target/service.jar
