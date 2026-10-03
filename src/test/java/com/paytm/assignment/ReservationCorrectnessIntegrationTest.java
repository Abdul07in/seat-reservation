package com.paytm.assignment;

import com.fasterxml.jackson.databind.JsonNode;
import com.paytm.assignment.constant.UserRole;
import com.paytm.assignment.entity.UserEntity;
import com.paytm.assignment.repository.UserRepository;
import com.paytm.assignment.service.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.junit.jupiter.api.AfterAll;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationCorrectnessIntegrationTest {

    private static final String TEST_DATABASE_URL = System.getenv("TEST_DATABASE_URL");
    private static final PostgreSQLContainer<?> POSTGRES = TEST_DATABASE_URL == null || TEST_DATABASE_URL.isBlank()
            ? new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seat_reservation_test")
            .withUsername("seat_reservation")
            .withPassword("seat_reservation") : null;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            POSTGRES.start();
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> TEST_DATABASE_URL);
            registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USERNAME"));
            registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
        }
    }

    @AfterAll
    static void stopPostgres() {
        if (POSTGRES != null) {
            POSTGRES.stop();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Test
    void concurrentHotSeatRequestsHaveOneWinnerAndNoServerErrors() throws Exception {
        String showId = createShow(List.of("A1"), 4);
        List<String> buyerTokens = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            buyerTokens.add(createBuyerToken());
        }

        int contenders = buyerTokens.size();
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<ResponseEntity<JsonNode>>> pending = new ArrayList<>();
            for (int index = 0; index < contenders; index++) {
                String token = buyerTokens.get(index);
                String key = "hot-seat-" + index;
                pending.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS), "Contention barrier timed out");
                    return reserve(showId, token, key, List.of("A1"));
                }));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS), "Contenders did not reach the start barrier");
            start.countDown();
            List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> future : pending) {
                responses.add(future.get(45, TimeUnit.SECONDS));
            }

            assertEquals(1, responses.stream().filter(this::isStatus201).count(), "Exactly one buyer must win the hot seat");
            assertEquals(contenders - 1, responses.stream().filter(this::isStatus409).count(), "All other buyers must receive a clean conflict");
            assertTrue(responses.stream().allMatch(response -> response.getStatusCode().value() == 201
                    || response.getStatusCode().value() == 409), "Contention must not produce 5xx or unexpected statuses");
            assertEquals(1, counts(showId).path("confirmed").asInt());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Contention workers failed to stop");
        }
    }

    @Test
    void idempotencyReturnsOriginalReservationAndRejectsChangedSeatSet() {
        String showId = createShow(List.of("A1", "A2"), 4);
        String token = createBuyerToken();

        ResponseEntity<JsonNode> first = reserve(showId, token, "same-key", List.of("A1", "A2"));
        ResponseEntity<JsonNode> replay = reserve(showId, token, "same-key", List.of("A2", "A1"));
        ResponseEntity<JsonNode> changed = reserve(showId, token, "same-key", List.of("A1"));

        assertEquals(201, first.getStatusCode().value());
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody().path("reservation_id").asText(), replay.getBody().path("reservation_id").asText());
        assertEquals(409, changed.getStatusCode().value());
        assertEquals("IDEMPOTENCY_KEY_REUSED", changed.getBody().path("code").asText());
        assertReconciles(showId);
    }

    @Test
    void concurrentRequestsForOneUserCannotExceedSeatLimit() throws Exception {
        List<String> labels = List.of("A1", "A2", "A3", "A4", "A5", "A6");
        String showId = createShow(labels, 2);
        String token = createBuyerToken();
        CountDownLatch ready = new CountDownLatch(labels.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(labels.size());
        try {
            List<Future<ResponseEntity<JsonNode>>> pending = new ArrayList<>();
            for (int index = 0; index < labels.size(); index++) {
                String label = labels.get(index);
                pending.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS), "Limit-race barrier timed out");
                    return reserve(showId, token, "limit-" + label, List.of(label));
                }));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS), "Limit-race workers did not reach the start barrier");
            start.countDown();
            List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> future : pending) {
                responses.add(future.get(45, TimeUnit.SECONDS));
            }

            assertEquals(2, responses.stream().filter(this::isStatus201).count());
            assertEquals(labels.size() - 2, responses.stream().filter(this::isStatus409).count());
            assertTrue(responses.stream().filter(this::isStatus409)
                    .allMatch(response -> "USER_SEAT_LIMIT_EXCEEDED".equals(response.getBody().path("code").asText())));
            assertTrue(responses.stream().allMatch(response -> response.getStatusCode().value() == 201
                    || response.getStatusCode().value() == 409), "Limit race must not produce 5xx or unexpected statuses");
            assertEquals(2, counts(showId).path("confirmed").asInt());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Limit-race workers failed to stop");
        }
    }

    @Test
    void multiSeatRequestIsAllOrNothingWhenOneSeatDoesNotExist() {
        String showId = createShow(List.of("A1", "A2"), 4);
        String firstBuyer = createBuyerToken();
        String secondBuyer = createBuyerToken();
        assertEquals(201, reserve(showId, firstBuyer, "claim-a1", List.of("A1")).getStatusCode().value());

        ResponseEntity<JsonNode> partialRequest = reserve(showId, secondBuyer, "partial", List.of("A2", "A9"));
        assertEquals(409, partialRequest.getStatusCode().value());
        assertEquals("SEAT_UNAVAILABLE", partialRequest.getBody().path("code").asText());
        assertEquals("available", seatStatus(showId, "A2"), "A2 must remain available after the failed multi-seat request");
        assertEquals(201, reserve(showId, secondBuyer, "claim-a2", List.of("A2")).getStatusCode().value());
        assertReconciles(showId);
    }

    @Test
    void cancellationIsOwnerOnlyAndReleasedSeatCanBeBookedAgain() {
        String showId = createShow(List.of("A1"), 4);
        String owner = createBuyerToken();
        String otherBuyer = createBuyerToken();
        ResponseEntity<JsonNode> reservation = reserve(showId, owner, "cancel-target", List.of("A1"));
        String reservationId = reservation.getBody().path("reservation_id").asText();

        ResponseEntity<JsonNode> forbidden = cancel(reservationId, otherBuyer);
        assertEquals(403, forbidden.getStatusCode().value());
        assertEquals("confirmed", seatStatus(showId, "A1"), "Unauthorized cancellation must not release the seat");

        ResponseEntity<JsonNode> cancelled = cancel(reservationId, owner);
        assertEquals(200, cancelled.getStatusCode().value());
        assertEquals("cancelled", cancelled.getBody().path("status").asText());
        assertEquals(200, cancel(reservationId, owner).getStatusCode().value(), "Repeated cancellation should be safe");

        ResponseEntity<JsonNode> rebooked = reserve(showId, otherBuyer, "rebook-a1", List.of("A1"));
        assertEquals(201, rebooked.getStatusCode().value());
        assertNotEquals(reservationId, rebooked.getBody().path("reservation_id").asText());
        assertReconciles(showId);
    }

    private String createShow(List<String> seatLabels, int perUserLimit) {
        String adminToken = adminToken();
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "verification-" + UUID.randomUUID());
        payload.put("seats", seatLabels);
        payload.put("price_paise", 25000);
        payload.put("per_user_limit", perUserLimit);
        ResponseEntity<JsonNode> response = exchange(HttpMethod.POST, "/shows", payload, adminToken, null);
        assertEquals(201, response.getStatusCode().value(), "Show setup should succeed");
        return response.getBody().path("id").asText();
    }

    private String adminToken() {
        UserEntity admin = users.findByEmailIgnoreCase("admin")
                .orElseThrow(() -> new IllegalStateException("Bootstrap admin was not created"));
        return jwtTokenService.issue(admin).accessToken();
    }

    private String createBuyerToken() {
        UserEntity buyer = users.saveAndFlush(new UserEntity(
                "buyer-" + UUID.randomUUID() + "@example.test", passwordEncoder.encode("test-password"), UserRole.USER));
        return jwtTokenService.issue(buyer).accessToken();
    }

    private ResponseEntity<JsonNode> reserve(String showId, String token, String idempotencyKey, List<String> seatLabels) {
        return exchange(HttpMethod.POST, "/shows/" + showId + "/reserve", Map.of("seats", seatLabels), token, idempotencyKey);
    }

    private ResponseEntity<JsonNode> cancel(String reservationId, String token) {
        return exchange(HttpMethod.POST, "/reservations/" + reservationId + "/cancel", null, token, null);
    }

    private ResponseEntity<JsonNode> showState(String showId) {
        return exchange(HttpMethod.GET, "/shows/" + showId, null, null, null);
    }

    private JsonNode counts(String showId) {
        return showState(showId).getBody().path("counts");
    }

    private String seatStatus(String showId, String label) {
        for (JsonNode seat : showState(showId).getBody().path("seats")) {
            if (label.equals(seat.path("seat").asText())) {
                return seat.path("status").asText();
            }
        }
        throw new AssertionError("Seat missing from show state: " + label);
    }

    private void assertReconciles(String showId) {
        JsonNode state = showState(showId).getBody();
        JsonNode counts = state.path("counts");
        assertEquals(state.path("total_seats").asInt(), counts.path("available").asInt()
                + counts.path("held").asInt() + counts.path("confirmed").asInt());
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, Object body, String token, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        if (key != null) headers.set("Idempotency-Key", key);
        return http.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private boolean isStatus201(ResponseEntity<JsonNode> response) {
        return response.getStatusCode() == HttpStatus.CREATED;
    }

    private boolean isStatus409(ResponseEntity<JsonNode> response) {
        return response.getStatusCode() == HttpStatus.CONFLICT;
    }
}
