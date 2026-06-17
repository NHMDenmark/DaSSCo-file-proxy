## Running for the first time
* Make sure your ``private.pem`` file is at the project's root
* Make sure your ``application-local.properties`` file has correct user info
* In ``application.properties``, make sure the ``share.mountFolder`` variable is a path on your PC

## Running database and keycloak
* Make sure the ``KEYCLOAK_ADMIN_CLIENT_SECRET`` is updated and correct
* Run ``docker compose up --build``

## Run the application
* Click the run button or use the command ``mvn spring-boot:run`` 
* You can add the ``docker-compose-app.yaml`` file to the ``docker-compose.yaml`` file if you want to run it with the keycloak and database instead 

## Configuration
* ``ASSET_BUNDLE_MAX_SIZE_GB`` sets the maximum total size for an asset bundle download in decimal GB. Default: ``20``.


## Large file upload
For all API's the following headers are required
* `Tus-Resumable: 1.0.0`

### POST
For this API the following headers are 
* `Upload-Length: <total bytes>` (required)
* `Upload-Metadata: filename <filename base64 encoded>` (not required)

### PATCH
For this API the following headers are
* `Upload-Offset: <total bytes>` (required)
* `Content-Length: <total bytes>` (required)
* `Content-Type: application/offset+octet-stream` (required)

### HEAD

### DELETE

## Large file download
