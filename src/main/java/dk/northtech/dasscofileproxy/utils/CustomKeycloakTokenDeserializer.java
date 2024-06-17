package dk.northtech.dasscofileproxy.utils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import dk.northtech.dasscofileproxy.domain.KeycloakToken;

import java.io.IOException;
import java.time.Instant;

public class CustomKeycloakTokenDeserializer extends StdDeserializer<KeycloakToken> {

    public CustomKeycloakTokenDeserializer() {
        this(null);
    }

    public CustomKeycloakTokenDeserializer(Class<?> vc) {
        super(vc);
    }

    @Override
    public KeycloakToken deserialize(JsonParser parser, DeserializationContext deserializer) {
        ObjectCodec codec = parser.getCodec();
        JsonNode node = null;
        try {
            node = codec.readTree(parser);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // try catch block
        JsonNode accessTokenNode = node.get("access_token");
        String accessToken = accessTokenNode.asText();

        JsonNode expiresInNode = node.get("expires_in");
        long expiresIn = expiresInNode.asLong();

        // Used to validate if the access token should be refreshed
        Instant accessExpirationTimeStamp = Instant.now().plusSeconds(expiresIn);

        JsonNode refreshExpiresInNode = node.get("refresh_expires_in");
        long refreshExpiresIn = refreshExpiresInNode.asLong();

        // Used to validate if the refresh token is still valid
        Instant refreshExpirationTimeStamp = Instant.now().plusSeconds(refreshExpiresIn);

        JsonNode refreshTokenNode = node.get("refresh_token");
        String refreshToken = refreshTokenNode.asText();

        JsonNode tokenTypeNode = node.get("token_type");
        String tokenType = tokenTypeNode.asText();

        JsonNode scopeNode = node.get("scope");
        String scope = scopeNode.asText();

        return new KeycloakToken(accessToken, expiresIn, accessExpirationTimeStamp, refreshExpiresIn, refreshExpirationTimeStamp, refreshToken, tokenType, scope);
    }
}
