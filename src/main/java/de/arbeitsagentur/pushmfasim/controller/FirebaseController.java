package de.arbeitsagentur.pushmfasim.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.arbeitsagentur.pushmfasim.model.FcmMessageRequest;
import de.arbeitsagentur.pushmfasim.model.FcmMessageResponse;
import de.arbeitsagentur.pushmfasim.model.FcmTokenResponse;
import de.arbeitsagentur.pushmfasim.services.SseService;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Controller
public class FirebaseController {
    private static final String TOKEN_VALUE_STRING = "keycloak_push_mfa_simulator_valid_assertion";
    private static final Logger LOG = LoggerFactory.getLogger(FirebaseController.class.getName());

    @Autowired
    private SseService sseService;

    @PostMapping(path = "/fcm/token")
    public ResponseEntity<FcmTokenResponse> getToken(@RequestParam("assertion") String assertion) {
        if (assertion == null || assertion.isEmpty()) {
            return ResponseEntity.status(HttpStatusCode.valueOf(401)).body(null);
        }

        return ResponseEntity.ok(
                FcmTokenResponse.builder().accessToken(TOKEN_VALUE_STRING).build());
    }

    @PostMapping(path = "/fcm/messages:send")
    public ResponseEntity<FcmMessageResponse> sendMessage(
            @RequestHeader("Authorization") String authorization, @RequestBody FcmMessageRequest request) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatusCode.valueOf(401)).body(null);
        }
        String token = authorization.substring("Bearer ".length());
        if (!TOKEN_VALUE_STRING.equals(token)) {
            return ResponseEntity.status(HttpStatusCode.valueOf(401)).body(null);
        }
        if (request == null
                || request.getMessage() == null
                || request.getMessage().getToken() == null
                || request.getMessage().getNotification() == null
                || request.getMessage().getData() == null
                || request.getMessage().getData().getToken() == null) {
            return ResponseEntity.status(HttpStatusCode.valueOf(400)).body(null);
        }

        // Publish request as server-sent event for further processing
        sseService.sendMessageToAllEmitters(request.getMessage());

        return ResponseEntity.ok(FcmMessageResponse.builder()
                .name("projects/ba-secure-mock/FcmMessageRequest")
                .build());
    }

    @GetMapping("/fcm/register-sse")
    public ResponseEntity<SseEmitter> sse() {
        // add response headers
        HttpHeaders headers = new HttpHeaders();
        headers.add("Connection", "keep-alive");
        headers.add("Cache-Control", "no-cache");
        headers.add("Content-Type", "text/event-stream");

        SseEmitter emitter = sseService.createSseEmitter();
        if (emitter == null) {
            return ResponseEntity.status(500).body(null);
        }
        return ResponseEntity.status(HttpStatus.OK).headers(headers).body(emitter);
    }

    @GetMapping("/fcm/credentials")
    public ResponseEntity<String> getCredentials() {
        Map<String, String> credentials = Map.of(
                "type", "service_account",
                "project_id", "ba-secure-mock",
                "private_key_id", "some_key_id",
                "private_key", getPrivateKeyPem(),
                "client_email", "fcm-mock@test.de",
                "token_uri", "http://localhost:5000/mock/fcm/token");
        try {
            return ResponseEntity.ok(
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(credentials));
        } catch (JsonProcessingException ex) {
            LOG.error("Error on creating mock credentials", ex);
        }
        return ResponseEntity.status(HttpStatusCode.valueOf(500)).body(null);
    }

    private String getPrivateKeyPem() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            PrivateKey privateKey = keyPair.getPrivate();
            PKCS8EncodedKeySpec pkcs8Spec = new PKCS8EncodedKeySpec(privateKey.getEncoded());
            byte[] pkcs8Bytes = pkcs8Spec.getEncoded();
            String base64Encoded = Base64.getMimeEncoder().encodeToString(pkcs8Bytes);
            StringBuilder pemBuilder = new StringBuilder();
            pemBuilder.append("-----BEGIN PRIVATE KEY-----");
            pemBuilder.append(base64Encoded);
            pemBuilder.append("-----END PRIVATE KEY-----");
            return pemBuilder.toString();
        } catch (NoSuchAlgorithmException ex) {
            LOG.error("Error on generating mock private key", ex);
        }
        return null;
    }
}
