FROM nimmis/java-centos:openjdk-8-jdk
ENV PWD=/root
ARG JAR_FILE
ARG TEST
COPY ${JAR_FILE} app.jar
COPY test test
ENTRYPOINT ["java", "-jar", "/root/app.jar"]