package com.itaccess.portal.mapper;

import com.itaccess.portal.domain.AccessRequest;
import com.itaccess.portal.domain.RequestSearchCriteria;
import com.itaccess.portal.domain.RequestStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis generates the implementation of this interface at startup.
 * Each method name matches a statement id in resources/mapper/AccessRequestMapper.xml.
 */
@Mapper
public interface AccessRequestMapper {

    /** Inserts the row and writes the generated ID back into {@code request.id}. */
    int insert(AccessRequest request);

    Optional<AccessRequest> findById(@Param("id") long id);

    /** ACCESS_REQUEST LEFT JOIN REQUEST_HISTORY, mapped into one object with a history list. */
    Optional<AccessRequest> findByIdWithHistory(@Param("id") long id);

    List<AccessRequest> search(@Param("criteria") RequestSearchCriteria criteria);

    long count(@Param("criteria") RequestSearchCriteria criteria);

    boolean existsById(@Param("id") long id);

    /**
     * Changes the status only if the request is still PENDING.
     *
     * @return number of rows updated: 1 on success, 0 if the request is missing or already reviewed
     */
    int updateStatusIfPending(@Param("id") long id,
                              @Param("newStatus") RequestStatus newStatus,
                              @Param("updatedAt") OffsetDateTime updatedAt);
}
