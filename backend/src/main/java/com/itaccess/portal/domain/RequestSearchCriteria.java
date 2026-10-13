package com.itaccess.portal.domain;

/**
 * Filter + paging parameters passed to the search SQL.
 *
 * @param status       exact status, or null for all
 * @param searchPattern LIKE pattern already upper-cased and escaped by the service, or null
 * @param offset       rows to skip
 * @param limit        maximum rows to return
 */
public record RequestSearchCriteria(RequestStatus status, String searchPattern, int offset, int limit) {
}
