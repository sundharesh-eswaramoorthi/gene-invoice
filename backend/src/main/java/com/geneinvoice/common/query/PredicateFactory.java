package com.geneinvoice.common.query;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

@FunctionalInterface
public interface PredicateFactory {
    Predicate build(Root<?> root, CriteriaQuery<?> query, CriteriaBuilder cb);
}
