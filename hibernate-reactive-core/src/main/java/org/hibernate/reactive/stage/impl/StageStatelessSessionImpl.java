/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.stage.impl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.hibernate.LockMode;
import org.hibernate.reactive.common.ResultSetMapping;
import org.hibernate.reactive.pool.ReactiveConnection;
import org.hibernate.reactive.session.ReactiveStatelessSession;
import org.hibernate.reactive.stage.Stage;

import jakarta.persistence.EntityGraph;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.CriteriaUpdate;

import static org.hibernate.reactive.util.impl.CompletionStages.returnOrRethrow;

/**
 * Implements the {@link Stage.StatelessSession} API. This delegating
 * class is needed to avoid name clashes when implementing both
 * {@code StatelessSession} and {@link org.hibernate.StatelessSession}.
 */
public class StageStatelessSessionImpl implements Stage.StatelessSession {

	private final ReactiveStatelessSession delegate;

	public StageStatelessSessionImpl(ReactiveStatelessSession delegate) {
		this.delegate = delegate;
	}

	public ReactiveConnection getReactiveConnection() {
		return delegate.getReactiveConnection();
	}

	@Override
	public <T> CompletionStage<T> get(Class<T> entityClass, Object id) {
		return delegate.reactiveGet( entityClass, id );
	}

	@Override
	public <T> CompletionStage<T> get(Class<T> entityClass, Object id, LockMode lockMode) {
		return delegate.reactiveGet( entityClass, id, lockMode, null );
	}

	@Override
	public CompletionStage<Void> insert(Object entity) {
		return delegate.reactiveInsert( entity );
	}

	@Override
	public CompletionStage<Void> insert(Object... entities) {
		return delegate.reactiveInsertAll( entities );
	}

	@Override
	public CompletionStage<Void> insert(int batchSize, Object... entities) {
		return delegate.reactiveInsertAll( batchSize, entities );
	}

	@Override
	public CompletionStage<Void> delete(Object entity) {
		return delegate.reactiveDelete( entity );
	}

	@Override
	public CompletionStage<Void> delete(Object... entities) {
		return delegate.reactiveDeleteAll( entities );
	}

	@Override
	public CompletionStage<Void> delete(int batchSize, Object... entities) {
		return delegate.reactiveDeleteAll( batchSize, entities );
	}

	@Override
	public CompletionStage<Void> update(Object entity) {
		return delegate.reactiveUpdate( entity );
	}

	@Override
	public CompletionStage<Void> update(Object... entities) {
		return delegate.reactiveUpdateAll( entities );
	}

	@Override
	public CompletionStage<Void> update(int batchSize, Object... entities) {
		return delegate.reactiveUpdateAll( batchSize, entities );
	}

	@Override
	public CompletionStage<Void> refresh(Object entity) {
		return delegate.reactiveRefresh( entity );
	}

	@Override
	public CompletionStage<Void> refresh(Object... entities) {
		return delegate.reactiveRefreshAll( entities );
	}

	@Override
	public CompletionStage<Void> refresh(int batchSize, Object... entities) {
		return delegate.reactiveRefreshAll( batchSize, entities );
	}

	@Override
	public CompletionStage<Void> refresh(Object entity, LockMode lockMode) {
		return delegate.reactiveRefresh( entity, lockMode );
	}

	@Override
	public <T> CompletionStage<T> fetch(T association) {
		return delegate.reactiveFetch( association, false );
	}

	@Override
	public <T> CompletionStage<T> withTransaction(Function<Stage.Transaction, CompletionStage<T>> work) {
		return currentTransaction == null
				? new Transaction<T>().execute( work )
				: work.apply( currentTransaction );
	}

	@Override
	public CompletionStage<Void> close() {
		CompletableFuture<Void> closing = new CompletableFuture<>();
		delegate.close( closing );
		return closing;
	}

	@Override
	public boolean isOpen() {
		return delegate.isOpen();
	}

	private Transaction<?> currentTransaction;

	@Override
	public Stage.Transaction currentTransaction() {
		return currentTransaction;
	}

	private class Transaction<T> implements Stage.Transaction {
		boolean rollback;
		Throwable error;

		CompletionStage<T> execute(Function<Stage.Transaction, CompletionStage<T>> work) {
			currentTransaction = this;
			return begin()
					.thenCompose( v -> executeInTransaction( work ) )
					.whenComplete( (t, x) -> currentTransaction = null );
		}

		/**
		 * Run the code assuming that a transaction has already started so that we can
		 * differentiate an error starting a transaction (and therefore doesn't need to rollback)
		 * and an error thrown by the work.
		 */
		CompletionStage<T> executeInTransaction(Function<Stage.Transaction, CompletionStage<T>> work) {
			return work.apply( this )
					// have to capture the error here and pass it along,
					// since we can't just return a CompletionStage that
					// rolls back the transaction from the handle() function
					.handle( this::processError )
					// finally, commit or rollback the transaction, and
					// then rethrow the caught error if necessary
					.thenCompose( result -> end()
							// make sure that if rollback() throws,
							// the original error doesn't get swallowed
							.handle( this::processError )
							// finally, rethrow the original error, if any
							.thenApply( v -> returnOrRethrow( error, result ) )
					);
		}

		CompletionStage<Void> begin() {
			return delegate.getReactiveConnection().beginTransaction();
		}

		CompletionStage<Void> end() {
			ReactiveConnection c = delegate.getReactiveConnection();
			return rollback ? c.rollbackTransaction() : c.commitTransaction();
		}

		<R> R processError(R result, Throwable e) {
			if ( e != null ) {
				rollback = true;
				if ( error == null ) {
					error = e;
				}
				else {
					error.addSuppressed( e );
				}
			}
			return result;
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
	public <T> CompletionStage<T> get(EntityGraph<T> entityGraph, Object id) {
		throw new UnsupportedOperationException("Not yet implemented");
	}

	@Override
	public <R> Stage.Query<R> createQuery(String queryString) {
		return new StageQueryImpl<>( delegate.createReactiveQuery( queryString ) );
	}

	@Override
	public <R> Stage.Query<R> createQuery(String queryString, Class<R> resultType) {
		return new StageQueryImpl<>( delegate.createReactiveQuery( queryString, resultType ) );
	}

	@Override
	public <R> Stage.NativeQuery<R> createNativeQuery(String queryString) {
		return new StageNativeQueryImpl<>( delegate.createReactiveNativeQuery( queryString ) );
	}

	@Override
	public <R> Stage.Query<R> createNamedQuery(String queryName) {
		return new StageQueryImpl<>( delegate.createReactiveNamedQuery( queryName, null ) );
	}

	@Override
	public <R> Stage.Query<R> createNamedQuery(String queryName, Class<R> resultType) {
		return new StageQueryImpl<>( delegate.createReactiveNamedQuery( queryName, resultType ) );
	}

	@Override
	public <R> Stage.NativeQuery<R> createNativeQuery(String queryString, Class<R> resultType) {
		return new StageNativeQueryImpl<>( delegate.createReactiveNativeQuery( queryString, resultType ) );
	}

	@Override
	public <R> Stage.Query<R> createQuery(CriteriaQuery<R> criteriaQuery) {
		return new StageQueryImpl<>( delegate.createReactiveQuery( criteriaQuery ) );
	}

	@Override
	public <R> Stage.MutationQuery<R> createQuery(CriteriaUpdate<R> criteriaUpdate) {
		return new StageMutationQueryImpl<>( delegate.createReactiveMutationQuery( criteriaUpdate ) );
	}

	@Override
	public <R> Stage.MutationQuery<R> createQuery(CriteriaDelete<R> criteriaDelete) {
		return new StageMutationQueryImpl<>( delegate.createReactiveMutationQuery( criteriaDelete ) );
	}

	@Override
	public <T> ResultSetMapping<T> getResultSetMapping(Class<T> resultType, String mappingName) {
		return delegate.getResultSetMapping( resultType, mappingName );
	}

	@Override
	public <T> EntityGraph<T> getEntityGraph(Class<T> rootType, String graphName) {
		throw new UnsupportedOperationException("Not yet implemented");
	}

	@Override
	public <T> EntityGraph<T> createEntityGraph(Class<T> rootType) {
		throw new UnsupportedOperationException("Not yet implemented");
	}

	@Override
	public <T> EntityGraph<T> createEntityGraph(Class<T> rootType, String graphName) {
		throw new UnsupportedOperationException("Not yet implemented");
	}
}
