package com.geneinvoice.common.query;

import java.util.List;

public enum ColumnType {
    TEXT(FilterOperator.CONTAINS, FilterOperator.EQ, FilterOperator.NEQ,
            FilterOperator.IS_EMPTY, FilterOperator.IS_NOT_EMPTY),
    ENUM(FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.IN, FilterOperator.NOT_IN),
    BOOLEAN(FilterOperator.EQ),
    NUMBER(FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.GT, FilterOperator.GTE,
            FilterOperator.LT, FilterOperator.LTE, FilterOperator.BETWEEN),
    MONEY(FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.GT, FilterOperator.GTE,
            FilterOperator.LT, FilterOperator.LTE, FilterOperator.BETWEEN),
    DATE(FilterOperator.GTE, FilterOperator.LTE, FilterOperator.BETWEEN, FilterOperator.RELATIVE),
    REFERENCE(FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.IN,
            FilterOperator.IS_EMPTY, FilterOperator.IS_NOT_EMPTY);

    private final List<FilterOperator> operators;

    ColumnType(FilterOperator... ops) {
        this.operators = List.of(ops);
    }

    public List<FilterOperator> operators() {
        return operators;
    }

    public boolean supports(FilterOperator op) {
        return operators.contains(op);
    }
}
