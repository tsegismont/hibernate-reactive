/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.event.impl;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import org.hibernate.HibernateException;
import org.hibernate.Interceptor;
import org.hibernate.engine.internal.CascadePoint;
import org.hibernate.engine.internal.Collections;
import org.hibernate.engine.spi.CollectionKey;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.spi.Status;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.FlushEntityEvent;
import org.hibernate.event.spi.FlushEntityEventListener;
import org.hibernate.event.spi.FlushEvent;
import org.hibernate.event.spi.PersistContext;
import org.hibernate.internal.util.EntityPrinter;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.reactive.engine.ReactiveActionQueue;
import org.hibernate.reactive.engine.impl.Cascade;
import org.hibernate.reactive.engine.impl.CascadingActions;
import org.hibernate.reactive.engine.impl.QueuedOperationCollectionAction;
import org.hibernate.reactive.engine.impl.ReactiveCollectionRecreateAction;
import org.hibernate.reactive.engine.impl.ReactiveCollectionRemoveAction;
import org.hibernate.reactive.engine.impl.ReactiveCollectionUpdateAction;
import org.hibernate.reactive.logging.impl.Log;
import org.hibernate.reactive.logging.impl.LoggerFactory;
import org.hibernate.reactive.session.ReactiveSession;

import static org.hibernate.reactive.engine.impl.CascadingActions.PERSIST_ON_FLUSH;
import static org.hibernate.reactive.util.impl.CompletionStages.loop;

/**
 * Collects commons methods needed during the management of flush events.
 *
 * @see org.hibernate.event.internal.AbstractFlushingEventListener
 */
public abstract class AbstractReactiveFlushingEventListener {

	private static final Log LOG = LoggerFactory.make( Log.class, MethodHandles.lookup() );

	protected CompletionStage<Void> performExecutions(EventSource session) {
		LOG.trace( "Executing flush" );

		// IMPL NOTE : here we alter the flushing flag of the persistence context to allow
		//		during-flush callbacks more leniency in regards to initializing proxies and
		//		lazy collections during their processing.
		// For more information, see HHH-2763
		session.getJdbcCoordinator().flushBeginning();
		session.getPersistenceContext().setFlushing( true );
		// we need to lock the collection caches before executing entity inserts/updates
		// in order to account for bidirectional associations
		final ReactiveActionQueue actionQueue = actionQueue(session);
		actionQueue.prepareActions();
		return actionQueue.executeActions()
				.whenComplete( (v, x) -> {
					session.getPersistenceContext().setFlushing( false );
					session.getJdbcCoordinator().flushEnding();
				} );
	}

	private ReactiveActionQueue actionQueue(EventSource session) {
		return session.unwrap( ReactiveSession.class ).getReactiveActionQueue();
	}

	/**
	 * Coordinates the processing necessary to get things ready for executions
	 * as db calls by prepping the session caches and moving the appropriate
	 * entities and collections to their respective execution queues.
	 *
	 * @param event The flush event.
	 * @throws HibernateException Error flushing caches to execution queues.
	 */
	protected CompletionStage<Void> flushEverythingToExecutions(FlushEvent event) throws HibernateException {
		LOG.trace( "Flushing session" );
		final EventSource session = event.getSession();
		final PersistenceContext persistenceContext = session.getPersistenceContextInternal();
		return preFlush( session, persistenceContext )
				.thenRun( () -> flushEverythingToExecutions( event, persistenceContext, session ) );
	}

	protected void flushEverythingToExecutions(FlushEvent event, PersistenceContext persistenceContext, EventSource session) {
		persistenceContext.setFlushing( true );
		try {
			int entityCount = flushEntities( event, persistenceContext );
			int collectionCount = flushCollections( session, persistenceContext );

			event.setNumberOfEntitiesProcessed( entityCount );
			event.setNumberOfCollectionsProcessed( collectionCount );
		}
		finally {
			persistenceContext.setFlushing( false);
		}

		//some statistics
		logFlushResults( event );
	}

	protected CompletionStage<Void> preFlush(EventSource session, PersistenceContext persistenceContext) {
		session.getInterceptor().preFlush( persistenceContext.managedEntitiesIterator() );
		return prepareEntityFlushes( session, persistenceContext )
				.thenAccept( v -> {
					// we could move this inside if we wanted to
					// tolerate collection initializations during
					// collection dirty checking:
					prepareCollectionFlushes( persistenceContext );
					// now, any collections that are initialized
					// inside this block do not get updated - they
					// are ignored until the next flush
				} );
	}

	protected void logFlushResults(FlushEvent event) {
		if ( !LOG.isDebugEnabled() ) {
			return;
		}
		final EventSource session = event.getSession();
		final PersistenceContext persistenceContext = session.getPersistenceContextInternal();
		LOG.debugf(
				"Flushed: %s insertions, %s updates, %s deletions to %s objects",
				session.getActionQueue().numberOfInsertions(),
				session.getActionQueue().numberOfUpdates(),
				session.getActionQueue().numberOfDeletions(),
				persistenceContext.getNumberOfManagedEntities()
		);
		LOG.debugf(
				"Flushed: %s (re)creations, %s updates, %s removals to %s collections",
				session.getActionQueue().numberOfCollectionCreations(),
				session.getActionQueue().numberOfCollectionUpdates(),
				session.getActionQueue().numberOfCollectionRemovals(),
				persistenceContext.getCollectionEntriesSize()
		);
		new EntityPrinter( session.getFactory() )
				.logEntities( persistenceContext.getEntityHoldersByKey().entrySet() );
	}

	/**
	 * process cascade save/update at the start of a flush to discover
	 * any newly referenced entity that must be passed to saveOrUpdate(),
	 * and also apply orphan delete
	 */
	private CompletionStage<Void> prepareEntityFlushes(EventSource session, PersistenceContext persistenceContext) throws HibernateException {
		LOG.debug( "Processing flush-time cascades" );

		final PersistContext context = PersistContext.create();
		//safe from concurrent modification because of how concurrentEntries() is implemented on IdentityMap
		final Map.Entry<Object, EntityEntry>[] entries = persistenceContext.reentrantSafeEntityEntries();
		return loop(
				entries,
				index -> flushable( entries[index].getValue() ),
				index -> cascadeOnFlush(
						session,
						entries[index].getValue().getPersister(),
						entries[index].getKey(),
						context
				)
		)
				// perform these checks after all cascade persist events have been
				// processed, so that all entities which will be persisted are
				// persistent when we do the check (I wonder if we could move this
				// into Nullability, instead of abusing the Cascade infrastructure)
				.thenCompose( v -> loop(
						entries,
						index -> flushable( entries[index].getValue() ),
						index -> Cascade.cascade(
								CascadingActions.CHECK_ON_FLUSH,
								CascadePoint.BEFORE_FLUSH,
								session,
								entries[index].getValue().getPersister(),
								entries[index].getKey(),
								null
						)
				) );
	}

	private CompletionStage<Void> cascadeOnFlush(
			EventSource session,
			EntityPersister persister,
			Object object,
			PersistContext anything)
			throws HibernateException {
		final PersistenceContext persistenceContext = session.getPersistenceContextInternal();
		persistenceContext.incrementCascadeLevel();
		return Cascade.cascade( PERSIST_ON_FLUSH, CascadePoint.BEFORE_FLUSH, session, persister, object, anything )
				.whenComplete( (unused, throwable) -> persistenceContext.decrementCascadeLevel() );
	}

	private static boolean flushable(EntityEntry entry) {
		final Status status = entry.getStatus();
		return status == Status.MANAGED
			|| status == Status.SAVING
			|| status == Status.READ_ONLY;
	}

	/**
	 * Initialize the flags of the CollectionEntry, including the
	 * dirty check.
	 */
	private void prepareCollectionFlushes(PersistenceContext persistenceContext) throws HibernateException {

		// Initialize dirty flags for arrays + collections with composite elements
		// and reset reached, doupdate, etc.

		LOG.debug( "Dirty checking collections" );
		persistenceContext.forEachCollectionEntry( (pc,ce) -> ce.preFlush( pc ), true );
	}

	/**
	 * 1. detect any dirty entities
	 * 2. schedule any entity updates
	 * 3. search out any reachable collections
	 */
	private int flushEntities(final FlushEvent event, final PersistenceContext persistenceContext) throws HibernateException {

		LOG.trace( "Flushing entities and processing referenced collections" );

		final EventSource source = event.getSession();
		final Iterable<FlushEntityEventListener> flushListeners =
				source.getFactory()
						.getEventListenerGroups()
						.eventListenerGroup_FLUSH_ENTITY
						.listeners();

		// Among other things, updateReachables() will recursively load all
		// collections that are moving roles. This might cause entities to
		// be loaded.

		// So this needs to be safe from concurrent modification problems.

		final Map.Entry<Object,EntityEntry>[] entityEntries = persistenceContext.reentrantSafeEntityEntries();
		final int count = entityEntries.length;

		for ( Map.Entry<Object,EntityEntry> me : entityEntries ) {

			// Update the status of the object and if necessary, schedule an update

			final EntityEntry entry = me.getValue();
			final Status status = entry.getStatus();

			if ( status != Status.LOADING && status != Status.GONE ) {
				final FlushEntityEvent entityEvent = new FlushEntityEvent( source, me.getKey(), entry );
				for ( FlushEntityEventListener listener : flushListeners ) {
					listener.onFlushEntity( entityEvent );
				}
			}
		}

		actionQueue( source ).sortActions();

		return count;
	}

	/**
	 * process any unreferenced collections and then inspect all known collections,
	 * scheduling creates/removes/updates
	 */
	private int flushCollections(final EventSource session, final PersistenceContext persistenceContext) throws HibernateException {
		LOG.trace( "Processing unreferenced collections" );

		final int count = persistenceContext.getCollectionEntriesSize();

		persistenceContext.forEachCollectionEntry(
				(persistentCollection, collectionEntry) -> {
					if ( !collectionEntry.isReached() && !collectionEntry.isIgnore() ) {
						Collections.processUnreachableCollection( persistentCollection, session );
					}
				}, true );

		// Schedule updates to collections:

		LOG.trace( "Scheduling collection removes/(re)creates/updates" );

		final ReactiveActionQueue actionQueue = session.unwrap( ReactiveSession.class).getReactiveActionQueue();
		final Interceptor interceptor = session.getInterceptor();
		persistenceContext.forEachCollectionEntry(
				(coll, ce) -> {
					if ( ce.isDorecreate() ) {
						interceptor.onCollectionRecreate( coll, ce.getCurrentKey() );
						actionQueue.addAction(
								new ReactiveCollectionRecreateAction(
										coll,
										ce.getCurrentPersister(),
										ce.getCurrentKey(),
										session
								)
						);
					}
					if ( ce.isDoremove() ) {
						interceptor.onCollectionRemove( coll, ce.getLoadedKey() );
						actionQueue.addAction(
								new ReactiveCollectionRemoveAction(
										coll,
										ce.getLoadedPersister(),
										ce.getLoadedKey(),
										ce.isSnapshotEmpty( coll ),
										session
								)
						);
					}
					if ( ce.isDoupdate() ) {
						interceptor.onCollectionUpdate( coll, ce.getLoadedKey() );
						actionQueue.addAction(
								new ReactiveCollectionUpdateAction(
										coll,
										ce.getLoadedPersister(),
										ce.getLoadedKey(),
										ce.isSnapshotEmpty( coll ),
										session
								)
						);
					}
					// todo : I'm not sure the !wasInitialized part should really be part of this check
					if ( !coll.wasInitialized() && coll.hasQueuedOperations() ) {
						actionQueue.addAction(
								new QueuedOperationCollectionAction(
										coll,
										ce.getLoadedPersister(),
										ce.getLoadedKey(),
										session
								)
						);
					}
				}, true );

		actionQueue.sortCollectionActions();

		return count;
	}

	/**
	 * 1. Recreate the collection key to collection map
	 * 2. rebuild the collection entries
	 * 3. call Interceptor.postFlush()
	 */
	protected void postFlush(SessionImplementor session) throws HibernateException {

		LOG.trace( "Post flush" );

		final PersistenceContext persistenceContext = session.getPersistenceContextInternal();
		persistenceContext.clearCollectionsByKey();

		// the database has changed now, so the subselect results need to be invalidated
		// the batch fetching queues should also be cleared - especially the collection batch fetching one
		persistenceContext.getBatchFetchQueue().clear();

		persistenceContext.forEachCollectionEntry(
				(persistentCollection, collectionEntry) -> {
					collectionEntry.postFlush( persistentCollection );
					if ( collectionEntry.getLoadedPersister() == null ) {
						//if the collection is dereferenced, unset its session reference and remove from the session cache
						//iter.remove(); //does not work, since the entrySet is not backed by the set
						persistentCollection.unsetSession( session );
						persistenceContext.removeCollectionEntry( persistentCollection );
					}
					else {
						//otherwise recreate the mapping between the collection and its key
						final CollectionKey collectionKey = new CollectionKey(
								collectionEntry.getLoadedPersister(),
								collectionEntry.getLoadedKey()
						);
						persistenceContext.addCollectionByKey( collectionKey, persistentCollection );
					}
				}, true
		);
	}

	protected void postPostFlush(SessionImplementor session) {
		session.getInterceptor().postFlush( session.getPersistenceContextInternal().managedEntitiesIterator() );
	}

}
