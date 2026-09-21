package com.geneinvoice.common.query;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

@FunctionalInterface
public interface PredicateResolver {
    Predicate resolve(FilterSpec spec, Root<?> root, CriteriaQuery<?> query, CriteriaBuilder cb);
}
