package com.itaccess.portal.mapper;

import com.itaccess.portal.TestcontainersConfiguration;
import com.itaccess.portal.domain.AccessLevel;
import com.itaccess.portal.domain.AccessRequest;
import com.itaccess.portal.domain.RequestHistory;
import com.itaccess.portal.domain.RequestSearchCriteria;
import com.itaccess.portal.domain.RequestStatus;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real SQL from the XML mappers against Oracle in a container (Flyway creates the schema).
 * Each test runs in a transaction that is rolled back afterwards, so tests do not see each other's rows.
 */
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AccessRequestMapperIntegrationTest {

    private static final OffsetDateTime T0 =
            OffsetDateTime.of(2026, 10, 13, 8, 0, 0, 0, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private AccessRequestMapper requestMapper;
    @Autowired
    private RequestHistoryMapper historyMapper;

    @Test
    void insertReturnsGeneratedIdAndRoundTripsAllColumns() {
        AccessRequest saved = insert("Ted L", "GitLab", RequestStatus.PENDING, T0);

        assertThat(saved.getId()).isNotNull().isPositive();

        AccessRequest loaded = requestMapper.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getRequesterName()).isEqualTo("Ted L");
        assertThat(loaded.getSystemName()).isEqualTo("GitLab");
        assertThat(loaded.getAccessLevel()).isEqualTo(AccessLevel.WRITE);
        assertThat(loaded.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(loaded.getCreatedAt()).isEqualTo(T0);
    }

    @Test
    void findByIdWithHistoryJoinsHistoryRowsInOrder() {
        AccessRequest saved = insert("Ted L", "Jira", RequestStatus.PENDING, T0);
        historyMapper.insert(new RequestHistory(saved.getId(), null, RequestStatus.PENDING, null, T0, "ted.l"));
        historyMapper.insert(new RequestHistory(saved.getId(), RequestStatus.PENDING, RequestStatus.APPROVED,
                "ok", T0.plusMinutes(5), "alice.admin"));

        AccessRequest loaded = requestMapper.findByIdWithHistory(saved.getId()).orElseThrow();

        assertThat(loaded.getSystemName()).isEqualTo("Jira");
        assertThat(loaded.getHistory()).hasSize(2);
        assertThat(loaded.getHistory().get(0).getOldStatus()).isNull();
        assertThat(loaded.getHistory().get(1).getNewStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(loaded.getHistory().get(1).getReviewComment()).isEqualTo("ok");
        assertThat(loaded.getHistory().get(1).getChangedBy()).isEqualTo("alice.admin");
    }

    @Test
    void findByIdWithHistoryReturnsEmptyHistoryListWhenNoRows() {
        AccessRequest saved = insert("Ted L", "Jira", RequestStatus.PENDING, T0);

        assertThat(requestMapper.findByIdWithHistory(saved.getId()).orElseThrow().getHistory()).isEmpty();
        assertThat(requestMapper.findByIdWithHistory(-1L)).isEmpty();
    }

    @Test
    void updateStatusIfPendingOnlyChangesPendingRows() {
        AccessRequest saved = insert("Ted L", "AWS", RequestStatus.PENDING, T0);

        assertThat(requestMapper.updateStatusIfPending(saved.getId(), RequestStatus.APPROVED, T0.plusHours(1)))
                .isEqualTo(1);
        assertThat(requestMapper.updateStatusIfPending(saved.getId(), RequestStatus.REJECTED, T0.plusHours(2)))
                .isZero();

        AccessRequest loaded = requestMapper.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(loaded.getUpdatedAt()).isEqualTo(T0.plusHours(1));
    }

    @Test
    void searchFiltersByStatusAndTextAndPages() {
        // The Oracle container is shared with tests that commit rows, so every name carries a unique
        // tag and every query is scoped to it; the test then only ever sees its own rows.
        String tag = "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        insert("Ann Smith " + tag, "GitLab " + tag, RequestStatus.PENDING, T0);
        insert("Bob Jones " + tag, "Snowflake " + tag, RequestStatus.PENDING, T0.plusMinutes(1));
        insert("Carl 100% " + tag, "Jira " + tag, RequestStatus.APPROVED, T0.plusMinutes(2));
        String onlyMine = "%" + tag + "%";

        assertThat(requestMapper.count(new RequestSearchCriteria(RequestStatus.PENDING, onlyMine, 0, 10)))
                .isEqualTo(2);
        // matches on system name
        assertThat(requestMapper.search(new RequestSearchCriteria(null, "%SNOWFLAKE " + tag + "%", 0, 10)))
                .extracting(AccessRequest::getRequesterName).containsExactly("Bob Jones " + tag);
        // matches on requester name
        assertThat(requestMapper.search(new RequestSearchCriteria(null, "%SMITH " + tag + "%", 0, 10)))
                .extracting(AccessRequest::getSystemName).containsExactly("GitLab " + tag);
        // escaped % matches a literal percent sign only
        assertThat(requestMapper.search(new RequestSearchCriteria(null, "%100\\% " + tag + "%", 0, 10)))
                .extracting(AccessRequest::getRequesterName).containsExactly("Carl 100% " + tag);
        // newest first, second page of size 1
        assertThat(requestMapper.search(new RequestSearchCriteria(null, onlyMine, 1, 1)))
                .extracting(AccessRequest::getRequesterName).containsExactly("Bob Jones " + tag);
    }

    @Test
    void existsById() {
        AccessRequest saved = insert("Ted L", "SAP", RequestStatus.PENDING, T0);
        assertThat(requestMapper.existsById(saved.getId())).isTrue();
        assertThat(requestMapper.existsById(-1L)).isFalse();
    }

    private AccessRequest insert(String requester, String system, RequestStatus status, OffsetDateTime at) {
        AccessRequest r = new AccessRequest();
        r.setRequesterName(requester);
        r.setSystemName(system);
        r.setAccessLevel(AccessLevel.WRITE);
        r.setReason("Integration test request");
        r.setStatus(status);
        r.setCreatedAt(at);
        r.setUpdatedAt(at);
        requestMapper.insert(r);
        return r;
    }
}
