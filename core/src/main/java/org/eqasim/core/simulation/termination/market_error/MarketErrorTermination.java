package org.eqasim.core.simulation.termination.market_error;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import com.google.inject.BindingAnnotation;

/**
 * Binding annotation for market error termination components.
 */
@Retention(RetentionPolicy.RUNTIME)
@BindingAnnotation
public @interface MarketErrorTermination {
}

