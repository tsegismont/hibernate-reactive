/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.mutiny.impl;

import org.hibernate.LockMode;
import org.hibernate.graph.spi.RootGraphImplementor;
import org.hibernate.query.criteria.JpaCriteriaInsert;
import org.hibernate.reactive.common.AffectedEntities;
import org.hibernate.reactive.common.ResultSetMapping;
import org.hibernate.reactive.mutiny.Mutiny;
import org.hibernate.reactive.mutiny.Mutiny.Query;
import org.hibernate.reactive.mutiny.Mutiny.SelectionQuery;
import org.hibernate.reactive.pool.ReactiveConnection;
import org.hibernate.reactive.query.ReactiveQuery;
import org.hibernate.reactive.session.ReactiveStatelessSession;

import io.smallrye.mutiny.Uni;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.TypedQueryReference;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.CriteriaUpdate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Implements the {@link Mutiny.StatelessSession} API. This delegating
 * class is needed to avoid name clashes when implementing both
 * {@code StatelessSession} and {@link org.hibernate.StatelessSession}.
 */
public class MutinyStatelessSessionImpl implements Mutiny.StatelessSession {

	private final ReactiveStatelessSession delegate;
	private final MutinySessionFactoryImpl factory;

	public MutinyStatelessSessionImpl(ReactiveStatelessSession delegate, MutinySessionFactoryImpl factory) {
		this.delegate = delegate;
		this.factory = factory;
	}

	public ReactiveConnection getReactiveConnection() {
		return delegate.getReactiveConnection();
	}

	<T> Uni<T> uni(Supplier<CompletionStage<T>> stageSupplier) {
		return factory.uni( stageSupplier );
	}

	@Override
	public <T> Uni<T> get(Class<T> entityClass, Object id) {
		return uni( () -> delegate.reactiveGet( entityClass, id ) );
	}

	@Override
	public <T> Uni<List<T>> get(Class<T> entityClass, Object... ids) {
		return uni( () -> delegate.reactiveGet( entityClass, ids ) );
	}

	@Override
	public <T> Uni<T> get(Class<T> entityClass, Object id, LockMode lockMode) {
		return uni( () -> delegate.reactiveGet( entityClass, id, lockMode, null ) );
	}

	@Override
	public <T> Uni<T> get(EntityGraph<T> entityGraph, Object id) {
		Class<T> entityClass = ( (RootGraphImplementor<T>) entityGraph ).getGraphedType().getJavaType();
		return uni( () -> delegate.reactiveGet( entityClass, id, null, entityGraph ) );
	}

	@Override
	public <R> Query<R> createQuery(TypedQueryReference<R> typedQueryReference) {
		ReactiveQuery<R> reactiveQuery = delegate.createReactiveQuery( typedQueryReference );
		return new MutinyQueryImpl<>( reactiveQuery, factory );
	}

	@Override
	public <R> Query<R> createQuery(String queryString) {
		return new MutinyQueryImpl<>( delegate.createReactiveQuery( queryString ), factory );
	}

	@Override @Deprecated
	public <R> SelectionQuery<R> createQuery(String queryString, Class<R> resultType) {
		return new MutinySelectionQueryImpl<>( delegate.createReactiveQuery( queryString, resultType ), factory );
	}

	@Override
	public <R> SelectionQuery<R> createSelectionQuery(String queryString, Class<R> resultType) {
		return new MutinySelectionQueryImpl<>( delegate.createReactiveSelectionQuery( queryString, resultType ), factory );
	}

	@Override
	public Mutiny.MutationQuery createMutationQuery(String queryString) {
		return new MutinyMutationQueryImpl<>( delegate.createReactiveMutationQuery( queryString ), factory );
	}

	@Override
	public Mutiny.MutationQuery createMutationQuery(CriteriaUpdate<?> updateQuery) {
		return new MutinyMutationQueryImpl<>( delegate.createReactiveMutationQuery( updateQuery ), factory );
	}

	@Override
	public Mutiny.MutationQuery createMutationQuery(CriteriaDelete<?> deleteQuery) {
		return new MutinyMutationQueryImpl<>( delegate.createReactiveMutationQuery( deleteQuery ) , factory );
	}

	@Override
	public Mutiny.MutationQuery createMutationQuery(JpaCriteriaInsert<?> insert) {
		return new MutinyMutationQueryImpl<>( delegate.createReactiveMutationQuery( insert ) , factory );
	}

	@Override
	public <R> Query<R> createNativeQuery(String queryString, AffectedEntities affectedEntities) {
		return new MutinyQueryImpl<>( delegate.createReactiveNativeQuery( queryString, affectedEntities ), factory );
	}

	@Override
	public <R> SelectionQuery<R> createNativeQuery(String queryString, Class<R> resultType, AffectedEntities affectedEntities) {
		return new MutinyQueryImpl<>( delegate.createReactiveNativeQuery( queryString, resultType, affectedEntities ), factory );
	}

	@Override
	public <R> SelectionQuery<R> createNativeQuery(String queryString, ResultSetMapping<R> resultSetMapping, AffectedEntities affectedEntities) {
		return new MutinyQueryImpl<>( delegate.createReactiveNativeQuery( queryString, resultSetMapping, affectedEntities ), factory );
	}

	@Override
	public <R> Query<R> createNamedQuery(String queryName) {
		return new MutinyQueryImpl<>( delegate.createReactiveNamedQuery( queryName ), factory );
	}

	@Override
	public <R> SelectionQuery<R> createNamedQuery(String queryName, Class<R> resultType) {
		return new MutinySelectionQueryImpl<>( delegate.createReactiveNamedQuery( queryName, resultType ), factory );
	}

	@Override
	public <R> Query<R> createNativeQuery(String queryString) {
		return new MutinyQueryImpl<>( delegate.createReactiveNativeQuery( queryString ), factory );
	}

	@Override
	public <R> SelectionQuery<R> createNativeQuery(String queryString, Class<R> resultType) {
		return new MutinySelectionQueryImpl<>( delegate.createReactiveNativeQuery( queryString, resultType ), factory );
	}

	@Override
	public <R> SelectionQuery<R> createNativeQuery(String queryString, ResultSetMapping<R> resultSetMapping) {
		return new MutinySelectionQueryImpl<>( delegate.createReactiveNativeQuery( queryString, resultSetMapping ), factory );
	}

	@Override
	public <R> SelectionQuery<R> createQuery(CriteriaQuery<R> criteriaQuery) {
		return new MutinySelectionQueryImpl<>( delegate.createReactiveQuery( criteriaQuery ), factory );
	}

	@Override
	public <R> Mutiny.MutationQuery createQuery(CriteriaUpdate<R> criteriaUpdate) {
		return new MutinyMutationQueryImpl<>( delegate.createReactiveMutationQuery( criteriaUpdate ), factory );
	}

	@Override
	public <R> Mutiny.MutationQuery createQuery(CriteriaDelete<R> criteriaDelete) {
		return new MutinyMutationQueryImpl<>( delegate.createReactiveMutationQuery( criteriaDelete ), factory );
	}

	@Override
	public Uni<Void> insert(Object entity) {
		return uni( () -> delegate.reactiveInsert( entity ) );
	}

	@Override
	public Uni<Void> insertAll(Object... entities) {
		return uni( () -> delegate.reactiveInsertAll( entities.length, entities ) );
	}

	@Override
	public Uni<Void> insertAll(int batchSize, Object... entities) {
		return uni( () -> delegate.reactiveInsertAll( batchSize, entities ) );
	}

	@Override
	public Uni<Void> insertMultiple(List<?> entities) {
		return insertAll( entities.size(), entities.toArray() );
	}

	@Override
	public Uni<Void> delete(Object entity) {
		return uni( () -> delegate.reactiveDelete( entity ) );
	}

	@Override
	public Uni<Void> deleteAll(Object... entities) {
		return uni( () -> delegate.reactiveDeleteAll( entities.length, entities ) );
	}

	@Override
	public Uni<Void> deleteAll(int batchSize, Object... entities) {
		return uni( () -> delegate.reactiveDeleteAll( batchSize, entities ) );
	}

	@Override
	public Uni<Void> deleteMultiple(List<?> entities) {
		return deleteAll( entities.size(), entities.toArray() );
	}

	@Override
	public Uni<Void> update(Object entity) {
		return uni( () -> delegate.reactiveUpdate( entity ) );
	}

	@Override
	public Uni<Void> updateAll(Object... entities) {
		return uni( () -> delegate.reactiveUpdateAll( entities.length, entities ) );
	}

	@Override
	public Uni<Void> updateAll(int batchSize, Object... entities) {
		return uni( () -> delegate.reactiveUpdateAll( batchSize, entities ) );
	}

	@Override
	public Uni<Void> updateMultiple(List<?> entities) {
		return updateAll( entities.size(), entities.toArray() );
	}

	@Override
	public Uni<Void> refresh(Object entity) {
		return uni( () -> delegate.reactiveRefresh( entity ) );
	}

	@Override
	public Uni<Void> upsert(Object entity) {
		return uni( () -> delegate.reactiveUpsert( entity ) );
	}

	@Override
	public Uni<Void> upsertAll(Object... entities) {
		return uni( () -> delegate.reactiveUpsertAll( entities.length, entities ) );
	}

	@Override
	public Uni<Void> upsertAll(int batchSize, Object... entities) {
		return uni( () -> delegate.reactiveUpsertAll( batchSize, entities ) );
	}

	@Override
	public Uni<Void> upsertMultiple(List<?> entities) {
		return uni( () -> delegate.reactiveUpsertAll( entities.size(), entities.toArray() ) );
	}

	@Override
	public Uni<Void> refreshAll(Object... entities) {
		return uni( () -> delegate.reactiveRefreshAll( entities.length, entities ) );
	}

	@Override
	public Uni<Void> refreshAll(int batchSize, Object... entities) {
		return uni( () -> delegate.reactiveRefreshAll( batchSize, entities ) );
	}

	@Override
	public Uni<Void> refreshMultiple(List<?> entities) {
		return refreshAll( entities.size(), entities.toArray() );
	}

	@Override
	public Uni<Void> refresh(Object entity, LockMode lockMode) {
		return uni( () -> delegate.reactiveRefresh( entity, lockMode ) );
	}

	@Override
	public <T> Uni<T> fetch(T association) {
		return uni( () -> delegate.reactiveFetch( association, false ) );
	}

	@Override
	public Object getIdentifier(Object entity) {
		return delegate.getIdentifier(entity);
	}

	@Override
	public <T> Uni<T> withTransaction(Function<Mutiny.Transaction, Uni<T>> work) {
		return currentTransaction == null ? new Transaction<T>().execute( work ) : work.apply( currentTransaction );
	}

	private Transaction<?> currentTransaction;

	@Override
	public Mutiny.Transaction currentTransaction() {
		return currentTransaction;
	}

	private class Transaction<T> implements Mutiny.Transaction {
		boolean rollback;

		/**
		 * Execute the given work in a new transaction. Called only
		 * when no existing transaction was active.
		 */
		Uni<T> execute(Function<Mutiny.Transaction, Uni<T>> work) {
			currentTransaction = this;
			return begin()
					.chain( () -> executeInTransaction( work ) )
					.eventually( () -> currentTransaction = null );
		}

		/**
		 * Run the code assuming that a transaction has already started
		 * so that we can differentiate an error starting a transaction
		 * (which therefore does not need to trigger rollback) from an
		 * error thrown by the work (which does).
		 */
		Uni<T> executeInTransaction(Function<Mutiny.Transaction, Uni<T>> work) {
			return Uni.createFrom().deferred( () -> work.apply( this ) )
					// in the case of an exception or cancellation
					// we need to roll back the transaction
					.onFailure().call( this::rollback )
					.onCancellation().call( this::rollback )
					// finally, when there was no exception,
					// commit or rollback the transaction
					.call( () -> rollback ? rollback() : commit() );
		}

		Uni<Void> begin() {
			return Uni.createFrom().completionStage( delegate.getReactiveConnection().beginTransaction() );
		}

		Uni<Void> rollback() {
			return Uni.createFrom().completionStage( delegate.getReactiveConnection().rollbackTransaction() );
		}

		Uni<Void> commit() {
			return Uni.createFrom().completionStage( delegate.getReactiveConnection().commitTransaction() );
		}

		@Override
		public void markForRollback() {
			rollback = true;
		}

		@Override
		public boolean isMarkedForRollback() {
			return rollback;
		}
	}

	@Override
	public Uni<Void> close() {
		return uni( () -> {
			CompletableFuture<Void> closing = new CompletableFuture<>();
			delegate.close( closing );
			return closing;
		} );
	}

	@Override
	public boolean isOpen() {
		return delegate.isOpen();
	}

	@Override
	public MutinySessionFactoryImpl getFactory() {
		return factory;
	}

	@Override
	public CriteriaBuilder getCriteriaBuilder() {
		return getFactory().getCriteriaBuilder();
	}

	@Override
	public <T> ResultSetMapping<T> getResultSetMapping(Class<T> resultType, String mappingName) {
		return delegate.getResultSetMapping( resultType, mappingName );
	}

	@Override
	public <T> EntityGraph<T> getEntityGraph(Class<T> rootType, String graphName) {
		return delegate.getEntityGraph( rootType, graphName );
	}

	@Override
	public <T> EntityGraph<T> createEntityGraph(Class<T> rootType) {
		return delegate.createEntityGraph( rootType );
	}

	@Override
	public <T> EntityGraph<T> createEntityGraph(Class<T> rootType, String graphName) {
		return delegate.createEntityGraph( rootType, graphName );
	}
}
