package com.geneinvoice.common.scope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The endpoint answers as of a past date when asked. On a class it covers the GET handlers; on a method it
 * also covers a read-only POST (exports, the rule preview).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AsOfAware {}
