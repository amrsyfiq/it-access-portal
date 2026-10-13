package com.itaccess.portal.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * Annotation-based mapper, shown for comparison with the XML mappers.
 * Fine for one-liners; anything with dynamic SQL or result maps lives in XML.
 */
@Mapper
public interface HealthMapper {

    @Select("SELECT 1 FROM DUAL")
    int ping();
}
