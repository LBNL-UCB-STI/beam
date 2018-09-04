FROM nimmis/java-centos:openjdk-8-jdk
ARG JAR_FILE
COPY ${JAR_FILE} app.jar
ENTRYPOINT ["java", "-jar", "/root/app.jar"]