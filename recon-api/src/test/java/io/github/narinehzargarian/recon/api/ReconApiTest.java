package io.github.narinehzargarian.recon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;

/** Runs the real app against a real PostgreSQL, started in Docker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReconApiTest.Containers.class)
class ReconApiTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }

    private static final String LEDGER = """
            id,date,amount,description,reference
            L1,2026-02-01,-120.00,Payment to Lumen Telecom,
            L2,2026-02-03,50.00,Card sale,
            L3,2026-02-03,25.00,Card sale,
            L4,2026-02-10,-80.00,Uncleared check,
            """;
    private static final String BANK = """
            id,date,amount,description
            B1,2026-02-02,-120.00,LUMEN TELECOM 55312
            B2,2026-02-05,75.00,STRIPE PAYOUT
            B3,2026-02-06,-9.00,MONTHLY FEE
            """;

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper mapper;

    private static HttpEntity<ByteArrayResource> file(String name, String content, MediaType type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        return new HttpEntity<>(new ByteArrayResource(content.getBytes()) {
            @Override
            public String getFilename() {
                return name;
            }
        }, headers);
    }

    private ResponseEntity<String> upload(String left, String right, String options) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("left", file("ledger.csv", left, MediaType.TEXT_PLAIN));
        body.add("right", file("bank.csv", right, MediaType.TEXT_PLAIN));
        if (options != null) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            body.add("options", new HttpEntity<>(options, headers));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity("/api/reconciliations", new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void reconcilesStoresAndServesTheResult() throws Exception {
        ResponseEntity<String> created = upload(LEDGER, BANK, null);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = mapper.readTree(created.getBody());
        String id = body.get("id").asText();
        assertThat(body.at("/summary/matchesByType/GROUP").asInt()).isEqualTo(1);
        assertThat(created.getHeaders().getLocation()).hasToString("/api/reconciliations/" + id);

        JsonNode result = mapper.readTree(http.getForObject("/api/reconciliations/" + id, String.class));
        assertThat(result.get("unmatchedLeft")).hasSize(1);
        assertThat(result.at("/unmatchedLeft/0/id").asText()).isEqualTo("L4");
        assertThat(result.at("/unmatchedRight/0/id").asText()).isEqualTo("B3");

        ResponseEntity<String> report = http.getForEntity("/api/reconciliations/" + id + "/report.csv", String.class);
        assertThat(report.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(report.getBody()).startsWith("status,match_type").contains("unmatched_left").contains("L4");

        JsonNode list = mapper.readTree(http.getForObject("/api/reconciliations", String.class));
        assertThat(list.findValuesAsText("id")).contains(id);
    }

    @Test
    void acceptsMappingAndMatchingOptions() throws Exception {
        String bank = "Posted,Details,Debit,Credit\n02/02/2026,LUMEN,120.00,\n";
        String options = """
                {"rightMapping": {"date": "Posted", "dateFormat": "MM/dd/yyyy", "debit": "Debit", "credit": "Credit", "description": "Details"},
                 "config": {"amountToleranceMinor": 0, "dateWindowDays": 1, "minDescriptionSimilarity": 0, "groupMatching": false,
                            "maxGroupSize": 6, "groupWindowDays": 7, "feePercent": 0, "feeFixedMinor": 0, "optimalComponentLimit": 300}}
                """;
        ResponseEntity<String> created = upload(LEDGER, bank, options);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode summary = mapper.readTree(created.getBody()).get("summary");
        assertThat(summary.get("matchedRight").asInt()).isEqualTo(1);
        assertThat(summary.at("/matchesByType/GROUP").isMissingNode()).isTrue();
    }

    @Test
    void reportsBadRowsWithoutFailingTheRun() throws Exception {
        ResponseEntity<String> created = upload(LEDGER + "L9,not-a-date,1.00,Broken,\n", BANK, null);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = mapper.readTree(created.getBody());
        assertThat(body.get("leftErrors")).hasSize(1);
        assertThat(body.at("/leftErrors/0/line").asInt()).isEqualTo(6);
    }

    @Test
    void rejectsBadInputClearly() throws Exception {
        ResponseEntity<String> missingColumn = upload("when,how much\n2026-01-01,1\n", BANK, null);
        assertThat(missingColumn.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(missingColumn.getBody()).get("detail").asText()).contains("column 'date' not found");

        ResponseEntity<String> badConfig = upload(LEDGER, BANK, """
                {"config": {"amountToleranceMinor": -5, "dateWindowDays": 3, "minDescriptionSimilarity": 0, "groupMatching": true,
                            "maxGroupSize": 6, "groupWindowDays": 7, "feePercent": 0, "feeFixedMinor": 0, "optimalComponentLimit": 300}}
                """);
        assertThat(badConfig.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> empty = upload("", BANK, null);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownIdIsNotFound() {
        assertThat(http.getForEntity("/api/reconciliations/00000000-0000-0000-0000-000000000000", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/reconciliations/not-a-uuid", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void healthEndpointIsUp() {
        assertThat(http.getForObject("/actuator/health", String.class)).contains("UP");
    }
}
