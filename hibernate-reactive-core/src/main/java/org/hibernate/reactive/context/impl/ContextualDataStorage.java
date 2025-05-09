/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.context.impl;

import org.hibernate.reactive.context.Context;

import io.vertx.core.impl.ContextInternal;
import io.vertx.core.spi.context.storage.AccessMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.vertx.core.impl.VertxBuilder;
import io.vertx.core.spi.VertxServiceProvider;
import io.vertx.core.spi.context.storage.ContextLocal;

/**
 * SPI Implementation for {@link ContextLocal} storage.
 */
public class ContextualDataStorage implements VertxServiceProvider {

	@SuppressWarnings("rawtypes")
	private final static ContextLocal<ConcurrentMap> CONTEXTUAL_DATA_KEY = ContextLocal.registerLocal( ConcurrentMap.class );

	@Override
	public void init(VertxBuilder vertxBuilder) {
	}

	@SuppressWarnings({ "unchecked" })
	public static <T> Map<Context.Key<T>, T> contextualDataMap(ContextInternal vertxContext) {
		return vertxContext.getLocal(
				ContextualDataStorage.CONTEXTUAL_DATA_KEY,
				AccessMode.CONCURRENT,
				ConcurrentHashMap::new
		);
	}
}
