FROM rabbitmq:4.1.1-alpine
COPY rabbitmq/rabbitmq.conf /etc/rabbitmq/rabbitmq.conf
COPY rabbitmq/startup.sh /startup.sh
RUN rabbitmq-plugins enable rabbitmq_auth_backend_oauth2 rabbitmq_management rabbitmq_jms_topic_exchange

CMD ["sh", "startup.sh"]
