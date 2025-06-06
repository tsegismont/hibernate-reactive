/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.id.impl;

import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.CompletionStage;

import org.hibernate.HibernateException;
import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.dialect.CockroachDialect;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.dialect.SQLServerDialect;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.engine.config.spi.StandardConverters;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.IdentifierGenerator;
import org.hibernate.id.enhanced.TableGenerator;
import org.hibernate.id.enhanced.TableStructure;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.jdbc.TooManyRowsAffectedException;
import org.hibernate.metamodel.spi.RuntimeModelCreationContext;
import org.hibernate.reactive.pool.ReactiveConnection;
import org.hibernate.reactive.provider.Settings;
import org.hibernate.reactive.session.ReactiveConnectionSupplier;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.Type;

import static org.hibernate.id.enhanced.TableGenerator.CONFIG_PREFER_SEGMENT_PER_ENTITY;
import static org.hibernate.id.enhanced.TableGenerator.DEF_SEGMENT_COLUMN;
import static org.hibernate.id.enhanced.TableGenerator.DEF_SEGMENT_VALUE;
import static org.hibernate.id.enhanced.TableGenerator.SEGMENT_COLUMN_PARAM;
import static org.hibernate.id.enhanced.TableGenerator.SEGMENT_VALUE_PARAM;
import static org.hibernate.id.enhanced.TableGenerator.TABLE;
import static org.hibernate.internal.util.config.ConfigurationHelper.getBoolean;
import static org.hibernate.internal.util.config.ConfigurationHelper.getInt;
import static org.hibernate.internal.util.config.ConfigurationHelper.getString;
import static org.hibernate.reactive.util.impl.CompletionStages.completedFuture;

/**
 * Support for JPA's {@link jakarta.persistence.TableGenerator}.
 * Persistence is managed via a table which may hold multiple
 * rows distinguished by a "segment" column value.
 * <p>
 * This implementation supports block allocation, but does not
 * guarantee that generated identifiers are sequential.
 */
public class TableReactiveIdentifierGenerator extends BlockingIdentifierGenerator implements IdentifierGenerator {

	private boolean storeLastUsedValue;

	protected String renderedTableName;
	protected String segmentColumnName;
	protected String valueColumnName;

	private String segmentValue;
	private long initialValue;
	private int increment;

	private String selectQuery;
	private String insertQuery;
	private String updateQuery;

	public TableReactiveIdentifierGenerator(TableGenerator generator, RuntimeModelCreationContext runtimeModelCreationContext) {
		ServiceRegistry serviceRegistry = runtimeModelCreationContext.getServiceRegistry();
		segmentColumnName = generator.getSegmentColumnName();
		valueColumnName = generator.getValueColumnName();
		segmentValue = generator.getSegmentValue();
		initialValue =  generator.getInitialValue();
		increment = generator.getIncrementSize();
		storeLastUsedValue = determineStoreLastUsedValue( serviceRegistry );
		renderedTableName = generator.getTableName();

		JdbcEnvironment jdbcEnvironment = serviceRegistry.getService( JdbcEnvironment.class );
		Dialect dialect = jdbcEnvironment.getDialect();
		selectQuery = applyLocksToSelect( dialect, "tbl", buildSelectQuery( dialect ) );
		updateQuery = buildUpdateQuery( dialect );
		insertQuery = buildInsertQuery( dialect );
	}

	public TableReactiveIdentifierGenerator(
			TableStructure structure,
			RuntimeModelCreationContext runtimeModelCreationContext) {
		ServiceRegistry serviceRegistry = runtimeModelCreationContext.getServiceRegistry();
		JdbcEnvironment jdbcEnvironment = serviceRegistry.getService( JdbcEnvironment.class );
		Dialect dialect = jdbcEnvironment.getDialect();

		valueColumnName = structure.getLogicalValueColumnNameIdentifier().render( dialect );
		initialValue =  structure.getInitialValue();
		increment = structure.getIncrementSize();
		storeLastUsedValue = determineStoreLastUsedValue( serviceRegistry );
		renderedTableName = structure.getPhysicalName().render();
		segmentColumnName = null;
		segmentValue = null;

		selectQuery = applyLocksToSelect( dialect, "tbl", buildSelectQuery( dialect ) );
		updateQuery = buildUpdateQuery( dialect );
		insertQuery = buildInsertQuery( dialect );
	}

	@Override
	public void configure(Type type, Properties params, ServiceRegistry serviceRegistry) {
		JdbcEnvironment jdbcEnvironment = serviceRegistry.getService( JdbcEnvironment.class );
		segmentColumnName = determineSegmentColumnName( params, jdbcEnvironment );
		valueColumnName = determineValueColumnNameForTable( params, jdbcEnvironment );
		segmentValue = determineSegmentValue( params );
		initialValue = determineInitialValue( params );
		increment = determineIncrement( params );
		storeLastUsedValue = determineStoreLastUsedValue( serviceRegistry );
		renderedTableName = determineTableName( type, params, serviceRegistry );

		Dialect dialect = jdbcEnvironment.getDialect();
		selectQuery = applyLocksToSelect( dialect, "tbl", buildSelectQuery( dialect ) );
		updateQuery = buildUpdateQuery( dialect );
		insertQuery = buildInsertQuery( dialect );
	}

	@Override
	protected int getBlockSize() {
		return increment;
	}

	@Override
	protected CompletionStage<Long> nextHiValue(ReactiveConnectionSupplier session) {

		// We need to read the current hi value from the table
		// and update it by the specified increment, but we
		// need to do it atomically, and without depending on
		// transaction rollback.
		final ReactiveConnection connection = session.getReactiveConnection();
		// 1) select the current hi value
		return connection
				.selectIdentifier( selectQuery, selectParameters(), Long.class )
				// 2) attempt to update the hi value
				.thenCompose( result -> {
					Object[] params;
					String sql;
					long id;
					if ( result == null ) {
						// if there is no row in the table, insert one
						// TODO: This not threadsafe, and can result in
						// multiple rows being inserted simultaneously.
						// It might be better to just throw an exception
						// here, and require that the table was populated
						// when it was created
						id = initialValue;
						long insertedValue = storeLastUsedValue ? id - increment : id;
						params = insertParameters( insertedValue );
						sql = insertQuery;
					}
					else {
						// otherwise, update the existing row
						long currentValue = result;
						long updatedValue = currentValue + increment;
						id = storeLastUsedValue ? updatedValue : currentValue;
						params = updateParameters( currentValue, updatedValue );
						sql = updateQuery;
					}
					return connection.update( sql, params )
							// 3) check the updated row count to detect simultaneous update
							.thenCompose(
									rowCount -> {
										switch ( rowCount ) {
											case 1:
												//we successfully obtained the next hi value
												return completedFuture( id );
											case 0:
												//someone else grabbed the next hi value
												//so retry everything from scratch
												return nextHiValue( session );
											default:
												throw new TooManyRowsAffectedException(
														"multiple rows in id table",
														1,
														rowCount
												);
										}
									}
							);
				} );
	}

	@Override
	public Object generate(SharedSessionContractImplementor session, Object object) throws HibernateException {
		throw new UnsupportedOperationException();
	}

	private String applyLocksToSelect(Dialect dialect, String alias, String query) {
		return dialect.applyLocksToSql(
				query,
				new LockOptions(LockMode.PESSIMISTIC_WRITE)
						.setAliasSpecificLockMode(alias, LockMode.PESSIMISTIC_WRITE),
				Collections.singletonMap(alias, new String[]{valueColumnName})
		);
	}

	protected Boolean determineStoreLastUsedValue(ServiceRegistry serviceRegistry) {
		return serviceRegistry.getService(ConfigurationService.class)
				.getSetting( Settings.TABLE_GENERATOR_STORE_LAST_USED, StandardConverters.BOOLEAN, true );
	}

	protected String determineTableName(Type type, Properties params, ServiceRegistry serviceRegistry) {
		TableGenerator ormTableGenerator = new TableGenerator();
		ormTableGenerator.configure( type, params, serviceRegistry );
		return ormTableGenerator.getTableName();
	}

	protected String determineSegmentColumnName(Properties params, JdbcEnvironment jdbcEnvironment) {
		final String name = getString( SEGMENT_COLUMN_PARAM, params, DEF_SEGMENT_COLUMN );
		return jdbcEnvironment.getIdentifierHelper().toIdentifier( name ).render( jdbcEnvironment.getDialect() );
	}

	protected String determineValueColumnNameForTable(Properties params, JdbcEnvironment jdbcEnvironment) {
		final String name = getString( TableGenerator.VALUE_COLUMN_PARAM, params, TableGenerator.DEF_VALUE_COLUMN );
		return jdbcEnvironment.getIdentifierHelper().toIdentifier( name ).render( jdbcEnvironment.getDialect() );
	}

	protected String determineSegmentValue(Properties params) {
		String segmentValue = params.getProperty( SEGMENT_VALUE_PARAM );
		if ( StringHelper.isEmpty( segmentValue ) ) {
			segmentValue = determineDefaultSegmentValue( params );
		}
		return segmentValue;
	}

	protected String determineDefaultSegmentValue(Properties params) {
		final boolean preferSegmentPerEntity = getBoolean( CONFIG_PREFER_SEGMENT_PER_ENTITY, params, false );
		return preferSegmentPerEntity ? params.getProperty( TABLE ) : DEF_SEGMENT_VALUE;
	}

	protected int determineInitialValue(Properties params) {
		return getInt( TableGenerator.INITIAL_PARAM, params, TableGenerator.DEFAULT_INITIAL_VALUE );
	}

	protected int determineIncrement(Properties params) {
		return getInt( TableGenerator.INCREMENT_PARAM, params, TableGenerator.DEFAULT_INCREMENT_SIZE );
	}

	protected Object[] updateParameters(long currentValue, long updatedValue) {
		return new Object[]{ updatedValue, currentValue, segmentValue };
	}

	protected Object[] insertParameters(long insertedValue) {
		return new Object[]{ segmentValue, insertedValue };
	}

	protected Object[] selectParameters() {
		return new Object[]{ segmentValue };
	}

	protected String buildSelectQuery(Dialect dialect) {
		final String sql = "select tbl." + valueColumnName + " from " + renderedTableName + " tbl where tbl." + segmentColumnName;
		if ( dialect instanceof PostgreSQLDialect || dialect instanceof CockroachDialect ) {
			return sql + "=$1";
		}
		if ( dialect instanceof SQLServerDialect ) {
			return sql + "=@P1";
		}
		if ( dialect instanceof OracleDialect ) {
			return sql + "=:1";
		}
		return sql + "=?";
	}

	protected String buildUpdateQuery(Dialect dialect) {
		if ( dialect instanceof PostgreSQLDialect || dialect instanceof CockroachDialect ) {
			return "update " + renderedTableName + " set " + valueColumnName + "=$1"
					+ " where " + valueColumnName + "=$2 and " + segmentColumnName + "=$3";
		}
		if ( dialect instanceof SQLServerDialect ) {
			return "update " + renderedTableName + " set " + valueColumnName + "=@P1"
					+ " where " + valueColumnName + "=@P2 and " + segmentColumnName + "=@P3";
		}
		if ( dialect instanceof OracleDialect ) {
			return "update " + renderedTableName + " set " + valueColumnName + "=:1"
					+ " where " + valueColumnName + "=:2 and " + segmentColumnName + "=:3";
		}
		return "update " + renderedTableName + " set " + valueColumnName + "=?"
				+ " where " + valueColumnName + "=?  and " + segmentColumnName + "=?";
	}

	protected String buildInsertQuery(Dialect dialect) {
		final String sql = "insert into " + renderedTableName + " (" + segmentColumnName + ", " + valueColumnName + ") ";
		if ( dialect instanceof PostgreSQLDialect || dialect instanceof CockroachDialect ) {
			return sql + " values ($1, $2)";
		}
		if ( dialect instanceof SQLServerDialect ) {
			return sql + " values (@P1, @P2)";
		}
		if ( dialect instanceof OracleDialect ) {
			return sql + " values (:1, :2)";
		}
		return sql + " values (?, ?)";
	}
}
