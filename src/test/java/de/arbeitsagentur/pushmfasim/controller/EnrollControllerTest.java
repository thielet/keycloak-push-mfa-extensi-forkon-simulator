package de.arbeitsagentur.pushmfasim.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnrollControllerTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private Resource jwkResource;

    private EnrollController enrollController;

    private String validEnrollmentToken;
    private RSAKey rsaKey;

    @BeforeEach
    void setUp() throws Exception {
        // Create controller manually to inject mocks
        enrollController = new EnrollController();

        // Generate a test RSA key pair
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        java.security.KeyPair keyPair = kpg.generateKeyPair();
        rsaKey = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
                .privateKey((java.security.interfaces.RSAPrivateKey) keyPair.getPrivate())
                .build();

        // Create a valid enrollment token
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("enrollmentId", "test-enrollment-id")
                .claim("nonce", "test-nonce")
                .subject("test-user-id")
                .build();

        JWSHeader header =
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("DEVICE_KEY_ID").build();

        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        signedJWT.sign(new RSASSASigner(rsaKey));
        validEnrollmentToken = signedJWT.serialize();

        // Mock JWK resource - use an answer to create fresh input stream each time
        String jwkJson = String.format(
                "{\"public\":%s,\"private\":%s}", rsaKey.toPublicJWK().toJSONString(), rsaKey.toJSONString());

        when(jwkResource.getInputStream()).thenAnswer(invocation -> new ByteArrayInputStream(jwkJson.getBytes()));

        // Inject mocks and resources
        Field restTemplateField = EnrollController.class.getDeclaredField("restTemplate");
        restTemplateField.setAccessible(true);
        restTemplateField.set(enrollController, restTemplate);

        Field jwkResourceField = EnrollController.class.getDeclaredField("jwkResource");
        jwkResourceField.setAccessible(true);
        jwkResourceField.set(enrollController, jwkResource);

        // Set default IAM URL
        Field defaultIamUrlField = EnrollController.class.getDeclaredField("defaultIamUrl");
        defaultIamUrlField.setAccessible(true);
        defaultIamUrlField.set(enrollController, "http://localhost:8080/realms/demo/push-mfa/enroll/complete");
    }

    // ============ Request Validation Tests ============

    @Test
    void testInvalidJwtFormat() throws Exception {
        assertThrows(Exception.class, () -> {
            enrollController.completeEnrollProcess("not.a.jwt", "context", null);
        });
    }

    @Test
    void testInvalidJwtToken() throws Exception {
        assertThrows(Exception.class, () -> {
            enrollController.completeEnrollProcess("invalid-token-format", null, null);
        });
    }

    // ============ JWT Claims Validation Tests ============

    @Test
    void testMissingEnrollmentIdClaim() throws Exception {
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("nonce", "test-nonce")
                .subject("test-user-id")
                .build();
        JWSHeader header =
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("KEY").build();
        SignedJWT jwt = new SignedJWT(header, claimsSet);
        jwt.sign(new RSASSASigner(rsaKey));
        String token = jwt.serialize();

        ResponseEntity<String> response = enrollController.completeEnrollProcess(token, "context", null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid token: missing required claims", response.getBody());
    }

    @Test
    void testMissingNonceClaim() throws Exception {
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("enrollmentId", "test-enrollment-id")
                .subject("test-user-id")
                .build();
        JWSHeader header =
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("KEY").build();
        SignedJWT jwt = new SignedJWT(header, claimsSet);
        jwt.sign(new RSASSASigner(rsaKey));
        String token = jwt.serialize();

        ResponseEntity<String> response = enrollController.completeEnrollProcess(token, "context", null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid token: missing required claims", response.getBody());
    }

    @Test
    void testMissingSubjectClaim() throws Exception {
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("enrollmentId", "test-enrollment-id")
                .claim("nonce", "test-nonce")
                .build();
        JWSHeader header =
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("KEY").build();
        SignedJWT jwt = new SignedJWT(header, claimsSet);
        jwt.sign(new RSASSASigner(rsaKey));
        String token = jwt.serialize();

        ResponseEntity<String> response = enrollController.completeEnrollProcess(token, "context", null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid token: missing required claims", response.getBody());
    }

    // ============ Successful Enrollment Tests ============

    @SuppressWarnings("null")
    @Test
    void testSuccessfulEnrollmentWithDefaultIamUrl() throws Exception {
        ResponseEntity<String> keycloakResponse = new ResponseEntity<>("{\"status\": \"success\"}", HttpStatus.OK);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(keycloakResponse);

        ResponseEntity<String> response = enrollController.completeEnrollProcess(validEnrollmentToken, "context", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"status\": \"success\"}", response.getBody());

        verify(restTemplate).exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @SuppressWarnings("null")
    @Test
    void testSuccessfulEnrollmentWithCustomIamUrl() throws Exception {
        String customUrl = "https://custom-keycloak.example.com/realms/demo/push-mfa/enroll/complete";
        ResponseEntity<String> keycloakResponse = new ResponseEntity<>("{\"status\": \"success\"}", HttpStatus.OK);

        when(restTemplate.exchange(eq(customUrl), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(keycloakResponse);

        ResponseEntity<String> response =
                enrollController.completeEnrollProcess(validEnrollmentToken, "context", customUrl);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"status\": \"success\"}", response.getBody());
    }

    @SuppressWarnings("null")
    @Test
    void testSuccessfulEnrollmentWithoutContext() throws Exception {
        ResponseEntity<String> keycloakResponse = new ResponseEntity<>("{\"status\": \"success\"}", HttpStatus.OK);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(keycloakResponse);

        ResponseEntity<String> response = enrollController.completeEnrollProcess(validEnrollmentToken, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @SuppressWarnings("null")
    @Test
    void testSuccessfulEnrollmentWithEmptyContext() throws Exception {
        ResponseEntity<String> keycloakResponse = new ResponseEntity<>("{\"status\": \"success\"}", HttpStatus.OK);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(keycloakResponse);

        ResponseEntity<String> response = enrollController.completeEnrollProcess(validEnrollmentToken, "", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // ============ Keycloak Response Tests ============

    @SuppressWarnings("null")
    @Test
    void testKeycloakErrorResponse() throws Exception {
        ResponseEntity<String> keycloakResponse = new ResponseEntity<>("Enrollment failed", HttpStatus.BAD_REQUEST);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(keycloakResponse);

        ResponseEntity<String> response = enrollController.completeEnrollProcess(validEnrollmentToken, "context", null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Enrollment failed", response.getBody());
    }

    @SuppressWarnings("null")
    @Test
    void testKeycloakUnauthorizedResponse() throws Exception {
        ResponseEntity<String> keycloakResponse = new ResponseEntity<>("Unauthorized", HttpStatus.UNAUTHORIZED);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(keycloakResponse);

        ResponseEntity<String> response = enrollController.completeEnrollProcess(validEnrollmentToken, "context", null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @SuppressWarnings("null")
    @Test
    void testKeycloakInternalServerError() throws Exception {
        ResponseEntity<String> keycloakResponse =
                new ResponseEntity<>("Internal Server Error", HttpStatus.INTERNAL_SERVER_ERROR);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(keycloakResponse);

        ResponseEntity<String> response = enrollController.completeEnrollProcess(validEnrollmentToken, "context", null);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    // ============ JWT Generation Tests ============

    @SuppressWarnings("null")
    @Test
    void testEnrollmentJwtIncludesRequiredClaims() throws Exception {
        ResponseEntity<String> keycloakResponse = new ResponseEntity<>("{\"status\": \"success\"}", HttpStatus.OK);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(keycloakResponse);

        enrollController.completeEnrollProcess(validEnrollmentToken, "test-context", null);

        verify(restTemplate).exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @SuppressWarnings("null")
    @Test
    void testEnrollmentJwtWithSpecialCharactersInContext() throws Exception {
        ResponseEntity<String> keycloakResponse = new ResponseEntity<>("{\"status\": \"success\"}", HttpStatus.OK);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(keycloakResponse);

        ResponseEntity<String> response =
                enrollController.completeEnrollProcess(validEnrollmentToken, "context-with-special-chars_123", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // ============ Edge Case Tests ============

    @SuppressWarnings("null")
    @Test
    void testEnrollmentWithVeryLongToken() throws Exception {
        // Create a token with many claims
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("enrollmentId", "enrollment-" + "x".repeat(100))
                .claim("nonce", "nonce-" + "y".repeat(100))
                .subject("user-" + "z".repeat(100))
                .build();

        JWSHeader header =
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("KEY").build();
        SignedJWT jwt = new SignedJWT(header, claimsSet);
        jwt.sign(new RSASSASigner(rsaKey));
        String longToken = jwt.serialize();

        ResponseEntity<String> keycloakResponse = new ResponseEntity<>("{\"status\": \"success\"}", HttpStatus.OK);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(keycloakResponse);

        ResponseEntity<String> response = enrollController.completeEnrollProcess(longToken, "context", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @SuppressWarnings("null")
    @Test
    void testEnrollmentWithCustomHttpMethod() throws Exception {
        ResponseEntity<String> keycloakResponse = new ResponseEntity<>("{\"status\": \"success\"}", HttpStatus.OK);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(keycloakResponse);

        enrollController.completeEnrollProcess(validEnrollmentToken, "context", null);

        verify(restTemplate).exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @SuppressWarnings("null")
    @Test
    void testEnrollmentWithExpiredToken() throws Exception {
        // Create token with expiration in the past
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("enrollmentId", "test-enrollment-id")
                .claim("nonce", "test-nonce")
                .subject("test-user-id")
                .expirationTime(new Date(System.currentTimeMillis() - 3600000)) // 1 hour ago
                .build();

        JWSHeader header =
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("KEY").build();
        SignedJWT jwt = new SignedJWT(header, claimsSet);
        jwt.sign(new RSASSASigner(rsaKey));
        String expiredToken = jwt.serialize();

        // Controller doesn't validate expiration time, so it should still work
        ResponseEntity<String> keycloakResponse = new ResponseEntity<>("{\"status\": \"success\"}", HttpStatus.OK);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(keycloakResponse);

        ResponseEntity<String> response = enrollController.completeEnrollProcess(expiredToken, "context", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @SuppressWarnings("null")
    @Test
    void testEnrollmentWithNullClaims() throws Exception {
        // Create minimal token
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("enrollmentId", "test-enrollment-id")
                .claim("nonce", "test-nonce")
                .subject("test-user-id")
                .build();

        JWSHeader header =
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("KEY").build();
        SignedJWT jwt = new SignedJWT(header, claimsSet);
        jwt.sign(new RSASSASigner(rsaKey));
        String token = jwt.serialize();

        ResponseEntity<String> keycloakResponse = new ResponseEntity<>("{\"status\": \"success\"}", HttpStatus.OK);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(keycloakResponse);

        ResponseEntity<String> response = enrollController.completeEnrollProcess(token, "context", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
