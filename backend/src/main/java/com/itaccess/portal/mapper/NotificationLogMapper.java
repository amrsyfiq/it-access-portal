package com.itaccess.portal.mapper;

import com.itaccess.portal.domain.NotificationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface NotificationLogMapper {

    /** Throws DuplicateKeyException if a row with the same EVENT_ID exists (unique constraint). */
    int insertProcessed(NotificationLog log);

    Optional<NotificationLog> findByEventId(@Param("eventId") String eventId);

    /** FAILED -> PROCESSED, e.g. when a dead-lettered message is replayed and now succeeds. */
    int markProcessed(@Param("eventId") String eventId, @Param("processedAt") OffsetDateTime processedAt);

    /** Insert a FAILED row, or refresh the error of an existing FAILED row. Never touches PROCESSED rows. */
    int recordFailure(NotificationLog log);

    List<NotificationLog> findByRequestId(@Param("requestId") long requestId);
}
