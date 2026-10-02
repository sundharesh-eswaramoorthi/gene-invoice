package com.geneinvoice.common;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionException;

import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

/**
 * Classifies a failure from the database. Callers ask {@link #isConcurrency} first: every transient
 * failure, a lock timeout included, also counts as {@link #isUnavailable}.
 */
public final class DatabaseErrors {

    private DatabaseErrors() {}

    public static boolean isUnavailable(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof TransientDataAccessException || t instanceof DataAccessResourceFailureException
                    || t instanceof TransactionException || t instanceof org.hibernate.TransactionException
                    || t instanceof SQLTransientException || t instanceof SQLRecoverableException
                    || t instanceof SQLNonTransientConnectionException) {
                return true;
            }
        }
        return false;
    }

    public static boolean isConcurrency(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConcurrencyFailureException || t instanceof PessimisticLockingFailureException
                    || t instanceof CannotAcquireLockException) {
                return true;
            }
        }
        return false;
    }
}
