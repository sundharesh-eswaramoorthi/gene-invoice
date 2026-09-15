package com.geneinvoice.common.query;

import java.util.List;

/**
 * The kind of a table column. Determines which filter operators the column accepts and how the
 * frontend renders its filter editor.
 */
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
    /** A foreign key rendered as a searchable picker; supports "is empty" for the POC-missing case. */
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
