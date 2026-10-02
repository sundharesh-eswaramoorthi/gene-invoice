package com.geneinvoice.common.scope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The endpoint always answers live, even when a past date is being viewed. On a class it covers the GET
 * handlers; on a method it also allows a housekeeping write in historical mode.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AsOfIgnored {}
