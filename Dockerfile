FROM openjdk:13
ADD add.tar /
EXPOSE 8080
VOLUME ["/conf"]
WORKDIR /
CMD ["/usr/bin/java", "-jar", "/app/app.jar", "-c", "/conf/config.json"]
