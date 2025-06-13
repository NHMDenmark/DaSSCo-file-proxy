cat /etc/rabbitmq/rabbitmq.conf

echo $KEYCLOAK_SCHEME
echo $KEYCLOAK_HOSTNAME
echo $KEYCLOAK_REALM
echo $KEYCLOAK_AUD
sed -i 's/KEYCLOAK_AUD/'"$KEYCLOAK_AUD"'/g' /etc/rabbitmq/rabbitmq.conf
sed -i 's/KEYCLOAK_SCHEME/'"$KEYCLOAK_SCHEME"'/g' /etc/rabbitmq/rabbitmq.conf
sed -i 's/KEYCLOAK_HOSTNAME/'"$KEYCLOAK_HOSTNAME"'/g' /etc/rabbitmq/rabbitmq.conf
sed -i 's/KEYCLOAK_REALM/'"$KEYCLOAK_REALM"'/g' /etc/rabbitmq/rabbitmq.conf

cat /etc/rabbitmq/rabbitmq.conf
exec rabbitmq-server
