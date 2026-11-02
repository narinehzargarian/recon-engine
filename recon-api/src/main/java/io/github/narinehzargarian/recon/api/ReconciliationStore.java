package io.github.narinehzargarian.recon.api;

import io.github.narinehzargarian.recon.ReconciliationResult;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Keeps each run's summary in columns, so runs can be listed and filtered, and the full result as
 * JSON, since it's only ever read back whole.
 */
@Repository
public class ReconciliationStore {

    public record Listing(UUID id, String leftName, String rightName, int leftCount, int rightCount,
                          int unmatchedLeft, int unmatchedRight, OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;

    public ReconciliationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID save(String leftName, String rightName, ReconciliationResult result, String resultJson) {
        UUID id = UUID.randomUUID();
        var summary = result.summary();
        jdbc.sql("""
                INSERT INTO reconciliation (id, left_name, right_name, left_count, right_count, unmatched_left, unmatched_right, result)
                VALUES (:id, :leftName, :rightName, :leftCount, :rightCount, :unmatchedLeft, :unmatchedRight, CAST(:result AS jsonb))
                """)
                .param("id", id)
                .param("leftName", leftName)
                .param("rightName", rightName)
                .param("leftCount", summary.leftCount())
                .param("rightCount", summary.rightCount())
                .param("unmatchedLeft", result.unmatchedLeft().size())
                .param("unmatchedRight", result.unmatchedRight().size())
                .param("result", resultJson)
                .update();
        return id;
    }

    public List<Listing> list(int limit) {
        return jdbc.sql("""
                SELECT id, left_name, right_name, left_count, right_count, unmatched_left, unmatched_right, created_at
                FROM reconciliation ORDER BY created_at DESC LIMIT :limit
                """)
                .param("limit", limit)
                .query(Listing.class)
                .list();
    }

    public Optional<String> resultJson(UUID id) {
        return jdbc.sql("SELECT result::text FROM reconciliation WHERE id = :id").param("id", id).query(String.class).optional();
    }
}
