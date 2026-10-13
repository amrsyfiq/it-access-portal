package com.itaccess.portal.mapper;

import com.itaccess.portal.domain.RequestHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RequestHistoryMapper {

    int insert(RequestHistory history);

    List<RequestHistory> findByRequestId(@Param("requestId") long requestId);
}
