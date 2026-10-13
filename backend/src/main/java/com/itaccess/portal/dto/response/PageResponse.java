package com.itaccess.portal.dto.response;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, long totalItems) {
}
