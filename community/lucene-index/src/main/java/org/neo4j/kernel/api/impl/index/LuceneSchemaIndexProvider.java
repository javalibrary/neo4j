/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.impl.index;

import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.store.Directory;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.api.impl.index.populator.DeferredConstraintVerificationUniqueLuceneIndexPopulator;
import org.neo4j.kernel.api.impl.index.populator.NonUniqueLuceneIndexPopulator;
import org.neo4j.kernel.api.impl.index.storage.DirectoryFactory;
import org.neo4j.kernel.api.impl.index.storage.IndexStorage;
import org.neo4j.kernel.api.impl.index.storage.IndexStorageFactory;
import org.neo4j.kernel.api.index.IndexAccessor;
import org.neo4j.kernel.api.index.IndexConfiguration;
import org.neo4j.kernel.api.index.IndexDescriptor;
import org.neo4j.kernel.api.index.IndexPopulator;
import org.neo4j.kernel.api.index.InternalIndexState;
import org.neo4j.kernel.api.index.SchemaIndexProvider;
import org.neo4j.kernel.impl.api.index.sampling.IndexSamplingConfig;
import org.neo4j.kernel.impl.api.scan.LabelScanStoreProvider;
import org.neo4j.kernel.impl.storemigration.StoreMigrationParticipant;
import org.neo4j.kernel.impl.storemigration.participant.SchemaIndexMigrator;

public class LuceneSchemaIndexProvider extends SchemaIndexProvider
{
    private final LuceneDocumentStructure documentStructure = new LuceneDocumentStructure();
    private final Map<Long,String> failures = new HashMap<>();
    private final IndexStorageFactory indexStorageFactory;

    public LuceneSchemaIndexProvider( FileSystemAbstraction fileSystem, DirectoryFactory directoryFactory,
            File storeDir )
    {
        super( LuceneSchemaIndexProviderFactory.PROVIDER_DESCRIPTOR, 1 );
        File schemaIndexStoreFolder = getSchemaIndexStoreDirectory( storeDir );
        this.indexStorageFactory = new IndexStorageFactory( directoryFactory, fileSystem, schemaIndexStoreFolder );
    }

    @Override
    public IndexPopulator getPopulator( long indexId, IndexDescriptor descriptor,
            IndexConfiguration config, IndexSamplingConfig samplingConfig )
    {
        IndexStorage populatorStorage = getIndexStorage( indexId );
        if ( config.isUnique() )
        {
            return new DeferredConstraintVerificationUniqueLuceneIndexPopulator( documentStructure,
                    IndexWriterFactories.standard(), SearcherManagerFactories.standard(),
                    populatorStorage, descriptor );
        }
        else
        {
            return new NonUniqueLuceneIndexPopulator( documentStructure, IndexWriterFactories.standard(),
                    populatorStorage, samplingConfig );
        }
    }

    @Override
    public IndexAccessor getOnlineAccessor( long indexId, IndexConfiguration config,
            IndexSamplingConfig samplingConfig ) throws IOException
    {
        IndexStorage indexStorage = getIndexStorage( indexId );
        indexStorage.openDirectory();
        if ( config.isUnique() )
        {
            return new UniqueLuceneIndexAccessor( documentStructure, IndexWriterFactories.standard(),
                    indexStorage );
        }
        else
        {
            return new NonUniqueLuceneIndexAccessor( documentStructure, IndexWriterFactories.standard(),
                    indexStorage, samplingConfig.bufferSize() );
        }
    }

    @Override
    public void shutdown() throws Throwable
    {   // Nothing to shut down
        indexStorageFactory.shutdown();
    }

    @Override
    public InternalIndexState getInitialState( long indexId )
    {
        try
        {
            IndexStorage indexStorage = getIndexStorage( indexId );
            String failure = indexStorage.getStoredIndexFailure();
            if ( failure != null )
            {
                failures.put( indexId, failure );
                return InternalIndexState.FAILED;
            }

            indexStorage.openDirectory();
            try ( Directory directory = indexStorage.getDirectory() )
            {
                boolean status = LuceneIndexWriter.isOnline( directory );
                return status ? InternalIndexState.ONLINE : InternalIndexState.POPULATING;
            }
        }
        catch ( CorruptIndexException e )
        {
            return InternalIndexState.FAILED;
        }
        catch ( FileNotFoundException e )
        {
            failures.put( indexId, "File not found: " + e.getMessage() );
            return InternalIndexState.FAILED;
        }
        catch ( EOFException e )
        {
            failures.put( indexId, "EOF encountered: " + e.getMessage() );
            return InternalIndexState.FAILED;
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
    }

    @Override
    public StoreMigrationParticipant storeMigrationParticipant( final FileSystemAbstraction fs, PageCache pageCache,
            LabelScanStoreProvider labelScanStoreProvider )
    {
        return new SchemaIndexMigrator( fs, this, labelScanStoreProvider );
    }

    @Override
    public String getPopulationFailure( long indexId ) throws IllegalStateException
    {
        String failure = getIndexStorage( indexId ).getStoredIndexFailure();
        if ( failure == null )
        {
            failure = failures.get( indexId );
        }
        if ( failure == null )
        {
            throw new IllegalStateException( "Index " + indexId + " isn't failed" );
        }
        return failure;
    }

    private IndexStorage getIndexStorage( long indexId )
    {
        return indexStorageFactory.indexStorageOf( indexId );
    }
}
