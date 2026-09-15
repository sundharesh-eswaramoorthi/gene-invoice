package com.geneinvoice.common.query;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Custom filtering for a column whose meaning is not a plain path — a to-many relationship, say,
 * where "equals" means "has a row matching" and "is empty" means "has no rows".
 */
@FunctionalInterface
public interface PredicateResolver {
    Predicate resolve(FilterSpec spec, Root<?> root, CriteriaQuery<?> query, CriteriaBuilder cb);
}
