/**
 * Copyright (C) 2014 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stratio.cassandra.lucene;

import com.stratio.cassandra.lucene.index.DocumentIterator;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.filter.ClusteringIndexFilter;
import org.apache.cassandra.db.filter.ClusteringIndexNamesFilter;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.utils.Pair;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.ScoreDoc;

import java.util.NavigableSet;

/**
 * {@link IndexReader} for wide rows.
 *
 * @author Andres de la Pena {@literal <adelapena@stratio.com>}
 */
class IndexReaderWide extends IndexReader {

    private final IndexServiceWide service;
    private final ClusteringComparator comparator;
    private Pair<Document, ScoreDoc> nextDoc;

    /**
     * Constructor taking the Cassandra read data and the Lucene results iterator.
     *
     * @param service the index service
     * @param command the read command
     * @param table the base table
     * @param executionController the Cassandra execution controller
     * @param documents the documents iterator
     */
    IndexReaderWide(IndexServiceWide service,
                    ReadCommand command,
                    ColumnFamilyStore table,
                    ReadExecutionController executionController,
                    DocumentIterator documents) {
        super(command, table, executionController, documents);
        this.service = service;
        this.comparator = service.metadata.comparator;
    }

    @Override
    protected boolean prepareNext() {

        if (next != null) {
            return true;
        }

        if (nextDoc == null) {
            if (!documents.hasNext()) {
                return false;
            }
            nextDoc = documents.next();
        }

        DecoratedKey key = service.decoratedKey(nextDoc.left);
        NavigableSet<Clustering> clusterings = clusterings(key);

        if (clusterings.isEmpty()) {
            return prepareNext();
        }

        ClusteringIndexFilter filter = new ClusteringIndexNamesFilter(clusterings, false);
        UnfilteredRowIterator data = read(key, filter);

        if (data.isEmpty()) {
            data.close();
            return prepareNext();
        }

        next = data;
        return true;
    }

    private NavigableSet<Clustering> clusterings(DecoratedKey key) {

        NavigableSet<Clustering> clusterings = service.clusterings();
        Clustering clustering = service.clustering(nextDoc.left);

        Clustering lastClustering = null;
        while (nextDoc != null && key.getKey().equals(service.decoratedKey(nextDoc.left).getKey()) &&
               (lastClustering == null || comparator.compare(lastClustering, clustering) < 0)) {
            if (command.selectsKey(key) && command.selectsClustering(key, clustering)) {
                lastClustering = clustering;
                clusterings.add(clustering);
            }
            if (documents.hasNext()) {
                nextDoc = documents.next();
                clustering = service.clustering(nextDoc.left);
            } else {
                nextDoc = null;
            }
            if (documents.needsFetch()) {
                break;
            }
        }
        return clusterings;
    }
}
