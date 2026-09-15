package com.geneinvoice.common.query;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/** A predicate contributed by the caller, e.g. a mandatory access scope. */
@FunctionalInterface
public interface PredicateFactory {
    Predicate build(Root<?> root, CriteriaQuery<?> query, CriteriaBuilder cb);
}
