package com.geneinvoice.common.query;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;

@FunctionalInterface
public interface PathResolver {
    Expression<?> resolve(Root<?> root, CriteriaQuery<?> query, CriteriaBuilder cb);
}
