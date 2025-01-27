/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.sql.ast.tree.cte;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.hibernate.metamodel.mapping.Association;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.metamodel.mapping.BasicValuedMapping;
import org.hibernate.metamodel.mapping.DiscriminatedAssociationModelPart;
import org.hibernate.metamodel.mapping.EmbeddableValuedModelPart;
import org.hibernate.metamodel.mapping.EntityDiscriminatorMapping;
import org.hibernate.metamodel.mapping.EntityIdentifierMapping;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.EntityValuedModelPart;
import org.hibernate.metamodel.mapping.ForeignKeyDescriptor;
import org.hibernate.metamodel.mapping.ModelPart;
import org.hibernate.metamodel.mapping.PluralAttributeMapping;
import org.hibernate.metamodel.mapping.internal.SingleAttributeIdentifierMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.query.derived.AnonymousTupleTableGroupProducer;
import org.hibernate.query.derived.CteTupleTableGroupProducer;

/**
 * Describes the table definition for the CTE - its name amd its columns
 *
 * @author Steve Ebersole
 */
public class CteTable {
	private final String cteName;
	private final AnonymousTupleTableGroupProducer tableGroupProducer;
	private final List<CteColumn> cteColumns;

	public CteTable(String cteName, List<CteColumn> cteColumns) {
		this( cteName, null, cteColumns );
	}

	public CteTable(String cteName, CteTupleTableGroupProducer tableGroupProducer) {
		this( cteName, tableGroupProducer, tableGroupProducer.determineCteColumns() );
	}

	private CteTable(String cteName, AnonymousTupleTableGroupProducer tableGroupProducer, List<CteColumn> cteColumns) {
		assert cteName != null;
		this.cteName = cteName;
		this.tableGroupProducer = tableGroupProducer;
		this.cteColumns = List.copyOf( cteColumns );
	}

	public String getTableExpression() {
		return cteName;
	}

	public AnonymousTupleTableGroupProducer getTableGroupProducer() {
		return tableGroupProducer;
	}

	public List<CteColumn> getCteColumns() {
		return cteColumns;
	}

	public CteTable withName(String name) {
		return new CteTable( name, tableGroupProducer, cteColumns );
	}

	public static CteTable createIdTable(String cteName, EntityMappingType entityDescriptor) {
		final int numberOfColumns = entityDescriptor.getIdentifierMapping().getJdbcTypeCount();
		final List<CteColumn> columns = new ArrayList<>( numberOfColumns );
		final EntityIdentifierMapping identifierMapping = entityDescriptor.getIdentifierMapping();
		final String idName;
		if ( identifierMapping instanceof SingleAttributeIdentifierMapping ) {
			idName = ( (SingleAttributeIdentifierMapping) identifierMapping ).getAttributeName();
		}
		else {
			idName = "id";
		}
		forEachCteColumn( idName, identifierMapping, columns::add );
		return new CteTable( cteName, columns );
	}

	public static CteTable createEntityTable(String cteName, EntityMappingType entityDescriptor) {
		final int numberOfColumns = entityDescriptor.getIdentifierMapping().getJdbcTypeCount();
		final List<CteColumn> columns = new ArrayList<>( numberOfColumns );
		final EntityIdentifierMapping identifierMapping = entityDescriptor.getIdentifierMapping();
		final String idName;
		if ( identifierMapping instanceof SingleAttributeIdentifierMapping ) {
			idName = ( (SingleAttributeIdentifierMapping) identifierMapping ).getAttributeName();
		}
		else {
			idName = "id";
		}
		forEachCteColumn( idName, identifierMapping, columns::add );

		final EntityDiscriminatorMapping discriminatorMapping = entityDescriptor.getDiscriminatorMapping();
		if ( discriminatorMapping != null && discriminatorMapping.hasPhysicalColumn() && !discriminatorMapping.isFormula() ) {
			forEachCteColumn( "class", discriminatorMapping, columns::add );
		}

		// Collect all columns for all entity subtype attributes
		entityDescriptor.visitSubTypeAttributeMappings(
				attribute -> {
					if ( !( attribute instanceof PluralAttributeMapping ) ) {
						forEachCteColumn( attribute.getAttributeName(), attribute, columns::add );
					}
				}
		);
		// We add a special row number column that we can use to identify and join rows
		columns.add(
				new CteColumn(
						"rn_",
						entityDescriptor.getEntityPersister()
								.getFactory()
								.getTypeConfiguration()
								.getBasicTypeForJavaType( Integer.class )
				)
		);
		return new CteTable( cteName, columns );
	}

	public static void forEachCteColumn(String prefix, ModelPart modelPart, Consumer<CteColumn> consumer) {
		if ( modelPart instanceof BasicValuedMapping ) {
			consumer.accept( new CteColumn( prefix, ( (BasicValuedMapping) modelPart ).getJdbcMapping() ) );
		}
		else if ( modelPart instanceof EntityValuedModelPart ) {
			final EntityValuedModelPart entityPart = ( EntityValuedModelPart ) modelPart;
			final ModelPart targetPart;
			if ( modelPart instanceof Association ) {
				final Association association = (Association) modelPart;
				if ( association.getForeignKeyDescriptor() == null ) {
					// This is expected to happen when processing a
					// PostInitCallbackEntry because the callbacks
					// are not ordered. The exception is caught in
					// MappingModelCreationProcess.executePostInitCallbacks()
					// and the callback is re-queued.
					throw new IllegalStateException( "ForeignKeyDescriptor not ready for [" + association.getPartName() + "] on entity: " + modelPart.findContainingEntityMapping().getEntityName() );
				}
				if ( association.getSideNature() != ForeignKeyDescriptor.Nature.KEY ) {
					// Inverse one-to-one receives no column
					return;
				}
				targetPart = association.getForeignKeyDescriptor().getTargetPart();
			}
			else {
				targetPart = entityPart.getEntityMappingType().getIdentifierMapping();
			}
			forEachCteColumn( prefix + "_" + entityPart.getPartName(), targetPart, consumer );
		}
		else if ( modelPart instanceof DiscriminatedAssociationModelPart ) {
			final DiscriminatedAssociationModelPart discriminatedPart = (DiscriminatedAssociationModelPart) modelPart;
			final String newPrefix = prefix + "_" + discriminatedPart.getPartName() + "_";
			forEachCteColumn(
					newPrefix + "discriminator",
					discriminatedPart.getDiscriminatorPart(),
					consumer
			);
			forEachCteColumn(
					newPrefix + "key",
					discriminatedPart.getKeyPart(),
					consumer
			);
		}
		else {
			final EmbeddableValuedModelPart embeddablePart = ( EmbeddableValuedModelPart ) modelPart;
			for ( AttributeMapping mapping : embeddablePart.getEmbeddableTypeDescriptor().getAttributeMappings() ) {
				if ( !( mapping instanceof PluralAttributeMapping ) ) {
					forEachCteColumn( prefix + "_" + mapping.getAttributeName(), mapping, consumer );
				}
			}
		}
	}

	public static int determineModelPartStartIndex(EntityPersister entityDescriptor, ModelPart modelPart) {
		int offset = 0;
		final EntityIdentifierMapping identifierMapping = entityDescriptor.getIdentifierMapping();
		if ( modelPart == identifierMapping ) {
			return offset;
		}
		offset += identifierMapping.getJdbcTypeCount();
		final EntityDiscriminatorMapping discriminatorMapping = entityDescriptor.getDiscriminatorMapping();
		if ( discriminatorMapping != null ) {
			if ( modelPart == discriminatorMapping ) {
				return offset;
			}
			offset += discriminatorMapping.getJdbcTypeCount();
		}
		for ( AttributeMapping attribute : entityDescriptor.getAttributeMappings() ) {
			if ( !( attribute instanceof PluralAttributeMapping ) ) {
				final int result = determineModelPartStartIndex( offset, attribute, modelPart );
				if ( result < 0 ) {
					return -result;
				}
				offset = result;
			}
		}
		return -1;
	}

	private static int determineModelPartStartIndex(int offset, ModelPart modelPart, ModelPart modelPartToFind) {
		if ( modelPart == modelPartToFind ) {
			return -offset;
		}
		if ( modelPart instanceof EntityValuedModelPart ) {
			final ModelPart keyPart;
			if ( modelPart instanceof Association ) {
				keyPart = ( (Association) modelPart ).getForeignKeyDescriptor();
			}
			else {
				keyPart = ( (EntityValuedModelPart) modelPart ).getEntityMappingType().getIdentifierMapping();
			}
			return determineModelPartStartIndex( offset, keyPart, modelPartToFind );
		}
		else if ( modelPart instanceof EmbeddableValuedModelPart ) {
			final EmbeddableValuedModelPart embeddablePart = ( EmbeddableValuedModelPart ) modelPart;
			for ( AttributeMapping mapping : embeddablePart.getEmbeddableTypeDescriptor().getAttributeMappings() ) {
				final int result = determineModelPartStartIndex( offset, mapping, modelPartToFind );
				if ( result < 0 ) {
					return result;
				}
				offset = result;
			}
			return offset;
		}
		return offset + modelPart.getJdbcTypeCount();
	}
}
