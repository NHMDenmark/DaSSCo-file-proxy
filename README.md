## Running for the first time
* Make sure your ``private.pem`` file is at the project's root
* Make sure your ``application-local.properties`` file has correct user info
* In ``application.properties``, make sure the ``share.mountFolder`` variable is a path on your PC

## Running database and keycloak
* Be sure these aren't already running in _dassco-asset-service_
* Run ``docker compose -f docker-compose-keycloak.yaml up --build``
* Run ``docker compose -f docker-compose-postgres.yaml up --build``
* Run the project either
  * Through Spring Boot (preferred)
    * ``mvn spring-boot:run`` 
  * With docker
    * ``docker compose -f docker-compose-app.yaml up --build``
