package com.flowforge.controller;

import static com.flowforge.TestAccounts.PASSWORD;
import static com.flowforge.TestAccounts.body;
import static com.flowforge.TestAccounts.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.flowforge.IntegrationTest;
import com.flowforge.TestAccounts;

@IntegrationTest
class AuthApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JwtDecoder jwtDecoder;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc);
    }


    @Test
    void registrationCreatesAnAccountWithABcryptHashedPassword() {
        String email = uniqueEmail();

        MvcTestResult result = register(email, PASSWORD, "Ana");

        assertThat(result).hasStatus(HttpStatus.CREATED)
                .bodyJson().isLenientlyEqualTo("""
                        { "email": "%s", "displayName": "Ana" }
                        """.formatted(email));
        assertThat(body(result)).doesNotContain("password").doesNotContain(PASSWORD);

        String storedHash = jdbc.sql("select password_hash from \"user\" where email = ?")
                .param(email).query(String.class).single();
        assertThat(storedHash).startsWith("$2").isNotEqualTo(PASSWORD);
    }

    @Test
    void duplicateEmailIsRejectedIgnoringCase() {
        String email = uniqueEmail();
        accounts.register(email);

        assertThat(register(email.toUpperCase(), PASSWORD, "Someone else"))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.code").isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void registrationValidatesFields() {
        assertThat(register("not-an-email", "short", ""))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.errors[*].field").asArray()
                .containsExactlyInAnyOrder("email", "password", "displayName");
    }

    @Test
    void passwordLongerThanBcryptAcceptsIsAValidationErrorNotAServerError() {
        assertThat(register(uniqueEmail(), "a".repeat(73), "Ana"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("password");

        assertThat(register(uniqueEmail(), "a".repeat(72), "Ana")).hasStatus(HttpStatus.CREATED);
        assertThat(register(uniqueEmail(), "ä".repeat(37), "Ana")).hasStatus(HttpStatus.BAD_REQUEST);
    }


    @Test
    void loginReturnsAnAccessTokenValidFor60Minutes() {
        String email = uniqueEmail();
        accounts.register(email);

        MvcTestResult result = login(email, PASSWORD);

        assertThat(result).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "expiresIn": 3600, "user": { "email": "%s", "displayName": "Test User" } }
                        """.formatted(email));

        Jwt token = jwtDecoder.decode(JsonPath.read(body(result), "$.accessToken"));
        Number userId = JsonPath.read(body(result), "$.user.id");
        assertThat(token.getSubject()).isEqualTo(String.valueOf(userId.longValue()));
        assertThat(Duration.between(token.getIssuedAt(), token.getExpiresAt())).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void loginEmailIsCaseInsensitive() {
        String email = uniqueEmail();
        accounts.register(email);

        assertThat(login(email.toUpperCase(), PASSWORD)).hasStatusOk();
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameGeneric401() {
        String email = uniqueEmail();
        accounts.register(email);

        MvcTestResult wrongPassword = login(email, "wrong-password-123");
        MvcTestResult unknownEmail = login(uniqueEmail(), PASSWORD);

        assertThat(wrongPassword).hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().isLenientlyEqualTo("""
                        { "status": 401, "code": "INVALID_CREDENTIALS", "detail": "Invalid email or password" }
                        """);
        assertThat(unknownEmail).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(body(unknownEmail)).isEqualTo(body(wrongPassword));
    }

    @Test
    void overlongLoginPasswordIsInvalidCredentialsNotAServerError() {
        String email = uniqueEmail();
        accounts.register(email);

        assertThat(login(email, "a".repeat(100)))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
    }


    @Test
    void meReturnsTheAuthenticatedUser() {
        String email = uniqueEmail();
        accounts.register(email);
        String token = accounts.login(email);

        assertThat(mvc.get().uri("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "email": "%s", "displayName": "Test User" }
                        """.formatted(email));
    }

    @Test
    void protectedEndpointWithoutTokenReturns401ProblemDetail() {
        assertThat(mvc.get().uri("/api/auth/me"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .bodyJson().isLenientlyEqualTo("""
                        { "status": 401, "code": "UNAUTHENTICATED", "instance": "/api/auth/me" }
                        """);
    }

    @Test
    void malformedTokenReturns401() {
        assertThat(mvc.get().uri("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void expiredTokenReturns401() {
        Instant issued = Instant.now().minus(Duration.ofHours(2));
        String expired = sign(JwtClaimsSet.builder()
                .issuer("flowforge")
                .subject("999999")
                .issuedAt(issued)
                .expiresAt(issued.plus(Duration.ofMinutes(60)))
                .build());

        assertThat(mvc.get().uri("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void tokenFromAnotherIssuerReturns401() {
        String foreign = sign(JwtClaimsSet.builder()
                .issuer("someone-else")
                .subject("999999")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(5)))
                .build());

        assertThat(mvc.get().uri("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private String sign(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private MvcTestResult register(String email, String password, String displayName) {
        return mvc.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "email": "%s", "password": "%s", "displayName": "%s" }
                        """.formatted(email, password, displayName))
                .exchange();
    }

    private MvcTestResult login(String email, String password) {
        return mvc.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "email": "%s", "password": "%s" }
                        """.formatted(email, password))
                .exchange();
    }
}
