FROM eclipse-mosquitto:2.0

COPY config/mosquitto.conf config/acl /mosquitto/config/
COPY init.sh /init.sh

ENTRYPOINT ["sh", "/init.sh"]
