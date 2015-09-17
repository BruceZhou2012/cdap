/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.data2.metadata.lineage;

import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.dataset.lib.AbstractDataset;
import co.cask.cdap.api.dataset.table.Row;
import co.cask.cdap.api.dataset.table.Scanner;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.common.app.RunIds;
import co.cask.cdap.data2.dataset2.lib.table.MDSKey;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.ProgramType;
import co.cask.cdap.proto.codec.NamespacedIdCodec;
import co.cask.cdap.proto.metadata.MetadataRecord;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.apache.twill.api.RunId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Dataset to store/retrieve Dataset accesses of a Program.
 */
public class LineageDataset extends AbstractDataset {

  // Storage format for row keys
  // ---------------------------
  //
  // Dataset access from program:
  // -------------------------------------------------------------------------------
  // | d | <id.dataset> | <inverted-start-time> | p | <id.run>     | <access-type> |
  // -------------------------------------------------------------------------------
  // | p | <id.run>     | <inverted-start-time> | p | <id.dataset> | <access-type> |
  // -------------------------------------------------------------------------------
  //
  // Stream access from program:
  // -------------------------------------------------------------------------------
  // | s | <id.stream>  | <inverted-start-time> | p | <id.run>     | <access-type> |
  // -------------------------------------------------------------------------------
  // | p | <id.run>     | <inverted-start-time> | s | <id.stream>  | <access-type> |
  // -------------------------------------------------------------------------------

  private static final Logger LOG = LoggerFactory.getLogger(LineageDataset.class);
  private static final Gson GSON = new GsonBuilder()
    .registerTypeAdapter(Id.NamespacedId.class, new NamespacedIdCodec())
    .create();
  private static final Type SET_METADATA_RECORD_TYPE = new TypeToken<Set<MetadataRecord>>() { }.getType();

  // Column used to store program stop time
  private static final byte[] STOP_COL_BYTE = {'s'};
  // Column used to store meta data
  private static final byte[] METADATA_COLS_BYTE = {'m'};

  private static final char DATASET_MARKER = 'd';
  private static final char PROGRAM_MARKER = 'p';
  private static final char FLOWLET_MARKER = 'f';
  private static final char STREAM_MARKER = 's';
  private static final char NONE_MARKER = '0';

  private Table accessRegistryTable;

  public LineageDataset(String instanceName, Table accessRegistryTable) {
    super(instanceName, accessRegistryTable);
    this.accessRegistryTable = accessRegistryTable;
  }

  /**
   * Add a program-dataset access.
   *
   * @param run program run information
   * @param datasetInstance dataset accessed by the program
   * @param accessType access type
   * @param metadata metadata to store for the access
   */
  public void addAccess(Id.Run run, Id.DatasetInstance datasetInstance,
                        AccessType accessType, Set<MetadataRecord> metadata) {
    addAccess(run, datasetInstance, accessType, metadata, null);
  }
  /**
   * Add a program-dataset access.
   *
   * @param run program run information
   * @param datasetInstance dataset accessed by the program
   * @param accessType access type
   * @param metadata metadata to store for the access
   * @param component program component such as flowlet id, etc.
   */
  public void addAccess(Id.Run run, Id.DatasetInstance datasetInstance,
                        AccessType accessType, Set<MetadataRecord> metadata, @Nullable Id.NamespacedId component) {
    LOG.trace("Recording access run={}, dataset={}, accessType={}, metadata={}, component={}",
              run, datasetInstance, accessType, metadata, component);
    String metadataJson = GSON.toJson(metadata);
    accessRegistryTable.put(getDatasetKey(datasetInstance, run, accessType, component),
                            METADATA_COLS_BYTE, Bytes.toBytes(metadataJson));
    accessRegistryTable.put(getProgramKey(run, datasetInstance, accessType, component),
                            METADATA_COLS_BYTE, Bytes.toBytes(metadataJson));
  }

  /**
   * Add a program-stream access.
   *
   * @param run program run information
   * @param stream stream accessed by the program
   * @param accessType access type
   * @param metadata metadata to store for the access
   */
  public void addAccess(Id.Run run, Id.Stream stream,
                        AccessType accessType, Set<MetadataRecord> metadata) {
    addAccess(run, stream, accessType, metadata, null);
  }

  /**
   * Add a program-stream access.
   *
   * @param run program run information
   * @param stream stream accessed by the program
   * @param accessType access type
   * @param metadata metadata to store for the access
   * @param component program component such as flowlet id, etc.
   */
  public void addAccess(Id.Run run, Id.Stream stream,
                        AccessType accessType, Set<MetadataRecord> metadata, @Nullable Id.NamespacedId component) {
    LOG.trace("Recording access run={}, stream={}, accessType={}, metadata={}, component={}",
              run, stream, accessType, metadata, component);
    String metadataJson = GSON.toJson(metadata);
    accessRegistryTable.put(getStreamKey(stream, run, accessType, component),
                            METADATA_COLS_BYTE, Bytes.toBytes(metadataJson));
    accessRegistryTable.put(getProgramKey(run, stream, accessType, component),
                            METADATA_COLS_BYTE, Bytes.toBytes(metadataJson));
  }

  /**
   * @return a set of {@link MetadataRecord}s (associated with both program and data it accesses)
   * for a given program run.
   */
  public Set<MetadataRecord> getRunMetadata(Id.Run run) {
    ImmutableSet.Builder<MetadataRecord> recordBuilder = ImmutableSet.builder();
    byte[] startKey = getRunScanStartKey(run);
    Scanner scanner = accessRegistryTable.scan(startKey, Bytes.stopKeyForPrefix(startKey));
    try {
      Row row;
      while ((row = scanner.next()) != null) {
        String metadata = Bytes.toString(row.get(METADATA_COLS_BYTE));
        if (LOG.isTraceEnabled()) {
          LOG.trace("Got row key = {}, metadata = {}", Bytes.toString(row.getRow()),
                    metadata);
        }
        String runId = getRunId(row);
        if (run.getId().equals(runId)) {
          recordBuilder.addAll(GSON.<Set<MetadataRecord>>fromJson(metadata, SET_METADATA_RECORD_TYPE));
        }
      }
    } finally {
      scanner.close();
    }
    return recordBuilder.build();
  }

  /**
   * Fetch program-dataset access information for a dataset for a given period.
   *
   * @param datasetInstance dataset for which to fetch access information
   * @param start start time period
   * @param end end time period
   * @return program-dataset access information
   */
  public Set<Relation> getRelations(Id.DatasetInstance datasetInstance, long start, long end) {
    return scanRelations(getDatasetScanStartKey(datasetInstance, end),
                         getDatasetScanEndKey(datasetInstance),
                         start);
  }

  /**
   * Fetch program-stream access information for a dataset for a given period.
   *
   * @param stream stream for which to fetch access information
   * @param start start time period
   * @param end end time period
   * @return program-dataset access information
   */
  public Set<Relation> getRelations(Id.Stream stream, long start, long end) {
    return scanRelations(getStreamScanStartKey(stream, end),
                         getStreamScanEndKey(stream),
                         start);
  }

  /**
   * Fetch program-dataset access information for a program for a given period.
   *
   * @param program program for which to fetch access information
   * @param start start time period
   * @param end end time period
   * @return program-dataset access information
   */
  public Set<Relation> getRelations(Id.Program program, long start, long end) {
    return scanRelations(getProgramScanStartKey(program, end),
                         getProgramScanEndKey(program),
                         start);
  }

  private Set<Relation> scanRelations(byte[] startKey, byte[] endKey, long startTime) {
    ImmutableSet.Builder<Relation> relationsBuilder = ImmutableSet.builder();
    Scanner scanner = accessRegistryTable.scan(startKey, endKey);
    try {
      Row row;
      while ((row = scanner.next()) != null) {
        if (LOG.isTraceEnabled()) {
          LOG.trace("Got row key = {}", Bytes.toString(row.getRow()));
        }
        Long stopTime = row.getLong(STOP_COL_BYTE);
        // TODO: convert this check into a scan filter for performance reasons
        if (stopTime == null || stopTime > startTime) {
          Relation relation = toRelation(row);
          relationsBuilder.add(relation);
        }
      }
    } finally {
      scanner.close();
    }
    return relationsBuilder.build();
  }

  private byte[] getDatasetKey(Id.DatasetInstance datasetInstance, Id.Run run,
                               AccessType accessType, @Nullable Id.NamespacedId component) {
    MDSKey.Builder builder = new MDSKey.Builder();
    addDataset(builder, datasetInstance);
    addDataKey(builder, run, accessType, component);
    return builder.build().getKey();
  }

  private byte[] getStreamKey(Id.Stream stream, Id.Run run,
                              AccessType accessType, @Nullable Id.NamespacedId component) {
    MDSKey.Builder builder = new MDSKey.Builder();
    addStream(builder, stream);
    addDataKey(builder, run, accessType, component);
    return builder.build().getKey();
  }

  private void addDataKey(MDSKey.Builder builder, Id.Run run,
                          AccessType accessType, @Nullable Id.NamespacedId component) {
    long invertedStartTime = getInvertedStartTime(run);
    builder.add(invertedStartTime);
    addProgram(builder, run.getProgram());
    builder.add(run.getId());
    builder.add(accessType.getType());
    addComponent(builder, component);
  }

  private byte[] getProgramKey(Id.Run run, Id.DatasetInstance datasetInstance,
                               AccessType accessType, @Nullable Id.NamespacedId component) {
    long invertedStartTime = getInvertedStartTime(run);
    MDSKey.Builder builder = new MDSKey.Builder();
    addProgram(builder, run.getProgram());
    builder.add(invertedStartTime);
    addDataset(builder, datasetInstance);
    builder.add(run.getId());
    builder.add(accessType.getType());
    addComponent(builder, component);

    return builder.build().getKey();
  }

  private byte[] getProgramKey(Id.Run run, Id.Stream stream,
                               AccessType accessType, @Nullable Id.NamespacedId component) {
    long invertedStartTime = getInvertedStartTime(run);
    MDSKey.Builder builder = new MDSKey.Builder();
    addProgram(builder, run.getProgram());
    builder.add(invertedStartTime);
    addStream(builder, stream);
    builder.add(run.getId());
    builder.add(accessType.getType());
    addComponent(builder, component);

    return builder.build().getKey();
  }

  private String getRunId(Row row) {
    MDSKey.Splitter splitter = new MDSKey(row.getRow()).split();
    char marker = (char) splitter.getInt();
    LOG.trace("Got marker {}", marker);
    switch (marker) {
      case PROGRAM_MARKER:
        toId(splitter, marker);
        splitter.skipLong(); // inverted start time
        marker = (char) splitter.getInt();
        toId(splitter, marker);  // data
        return splitter.getString();

      case DATASET_MARKER:
      case STREAM_MARKER:
        toId(splitter, marker);
        splitter.skipLong(); // inverted start time
        marker = (char) splitter.getInt();
        toId(splitter, marker);  // program
        return splitter.getString();

      default:
        throw new IllegalStateException("Invalid row with marker " +  marker);
    }
  }

  private byte[] getDatasetScanStartKey(Id.DatasetInstance datasetInstance, long end) {
    long invertedStartTime = invertTime(end);
    MDSKey.Builder builder = new MDSKey.Builder();
    addDataset(builder, datasetInstance);
    builder.add(invertedStartTime);

    return builder.build().getKey();
  }

  private byte[] getDatasetScanEndKey(Id.DatasetInstance datasetInstance) {
    // TODO: the scan space can be further reduced by using min(all running program's start time) instead of 0L.
    long invertedTime = invertTime(0L);
    MDSKey.Builder builder = new MDSKey.Builder();
    addDataset(builder, datasetInstance);
    builder.add(invertedTime);

    return builder.build().getKey();
  }

  private byte[] getStreamScanStartKey(Id.Stream stream, long end) {
    long invertedStartTime = invertTime(end);
    MDSKey.Builder builder = new MDSKey.Builder();
    addStream(builder, stream);
    builder.add(invertedStartTime);

    return builder.build().getKey();
  }

  private byte[] getStreamScanEndKey(Id.Stream stream) {
    // TODO: the scan space can be further reduced by using min(all running program's start time) instead of 0L.
    long invertedTime = invertTime(0L);
    MDSKey.Builder builder = new MDSKey.Builder();
    addStream(builder, stream);
    builder.add(invertedTime);

    return builder.build().getKey();
  }

  private byte[] getProgramScanStartKey(Id.Program program, long end) {
    long invertedStartTime = invertTime(end);
    MDSKey.Builder builder = new MDSKey.Builder();
    addProgram(builder, program);
    builder.add(invertedStartTime);

    return builder.build().getKey();
  }

  private byte[] getProgramScanEndKey(Id.Program program) {
    // TODO: the scan space can be further reduced by using min(all running program's start time) instead of 0L.
    long invertedTime = invertTime(0L);
    MDSKey.Builder builder = new MDSKey.Builder();
    addProgram(builder, program);
    builder.add(invertedTime);

    return builder.build().getKey();
  }

  private byte[] getRunScanStartKey(Id.Run run) {
    MDSKey.Builder builder = new MDSKey.Builder();
    addProgram(builder, run.getProgram());
    builder.add(getInvertedStartTime(run));
    return builder.build().getKey();
  }

  private void addDataset(MDSKey.Builder keyBuilder, Id.DatasetInstance datasetInstance) {
    keyBuilder.add(DATASET_MARKER)
      .add(datasetInstance.getNamespaceId())
      .add(datasetInstance.getId());
  }

  private void addStream(MDSKey.Builder keyBuilder, Id.Stream stream) {
    keyBuilder.add(STREAM_MARKER)
      .add(stream.getNamespaceId())
      .add(stream.getId());
  }

  private void addProgram(MDSKey.Builder keyBuilder, Id.Program program) {
    keyBuilder.add(PROGRAM_MARKER)
      .add(program.getNamespaceId())
      .add(program.getApplicationId())
      .add(program.getType().getCategoryName())
      .add(program.getId());
  }

  private void addComponent(MDSKey.Builder keyBuilder, Id component) {
    if (component instanceof Id.Flow.Flowlet) {
      keyBuilder.add(FLOWLET_MARKER)
        .add(component.getId());
    } else {
      keyBuilder.add(NONE_MARKER);
    }
  }

  private Id toId(MDSKey.Splitter splitter, char marker) {
    switch (marker) {
      case DATASET_MARKER:
        return Id.DatasetInstance.from(splitter.getString(), splitter.getString());

      case STREAM_MARKER:
        return Id.Stream.from(splitter.getString(), splitter.getString());

      case PROGRAM_MARKER:
        return Id.Program.from(splitter.getString(), splitter.getString(),
                               ProgramType.valueOfCategoryName(splitter.getString()),
                               splitter.getString());

      default: throw new IllegalStateException("Invalid row with marker " +  marker);
    }
  }

  private Id.NamespacedId toComponent(MDSKey.Splitter splitter, Id.Program program) {
    char marker = (char) splitter.getInt();
    switch (marker) {
      case NONE_MARKER:
        return null;

      case FLOWLET_MARKER :
        return Id.Flow.Flowlet.from(program.getApplication(), program.getId(), splitter.getString());

      default:
        throw new IllegalStateException("Invalid row with component marker " + marker);
    }
  }

  private long invertTime(long time) {
    return Long.MAX_VALUE - time;
  }

  private long getInvertedStartTime(Id.Run run) {
    return invertTime(RunIds.getTime(RunIds.fromString(run.getId()), TimeUnit.MILLISECONDS));
  }

  private Relation toRelation(Row row) {
    Map<Character, Id> rowInfo = new HashMap<>(4);

    MDSKey.Splitter splitter = new MDSKey(row.getRow()).split();
    char marker = (char) splitter.getInt();
    LOG.trace("Got marker {}", marker);
    Id id1 = toId(splitter, marker);
    LOG.trace("Got id1 {}", id1);
    rowInfo.put(marker, id1);

    splitter.skipLong(); // inverted time - not required for relation

    marker = (char) splitter.getInt();
    LOG.trace("Got marker {}", marker);
    Id id2 = toId(splitter, marker);
    LOG.trace("Got id2 {}", id1);
    rowInfo.put(marker, id2);

    RunId runId = RunIds.fromString(splitter.getString());
    LOG.trace("Got runId {}", runId);
    AccessType accessType = AccessType.fromType((char) splitter.getInt());
    LOG.trace("Got access type {}", accessType);

    Id.DatasetInstance datasetInstance = (Id.DatasetInstance) rowInfo.get(DATASET_MARKER);
    LOG.trace("Got datasetInstance {}", datasetInstance);
    Id.Stream stream = (Id.Stream) rowInfo.get(STREAM_MARKER);
    LOG.trace("Got stream {}", stream);

    Id.Program program = (Id.Program) rowInfo.get(PROGRAM_MARKER);
    LOG.trace("Got program {}", program);
    Id.NamespacedId component = toComponent(splitter, program);
    LOG.trace("Got component {}", component);

    if (stream == null) {
      return new Relation(datasetInstance,
                          program,
                          accessType,
                          ImmutableSet.of(runId),
                          component == null ? ImmutableSet.<Id.NamespacedId>of() : ImmutableSet.of(component));
    }

    return new Relation(stream,
                        program,
                        accessType,
                        ImmutableSet.of(runId),
                        component == null ? ImmutableSet.<Id.NamespacedId>of() : ImmutableSet.of(component));
  }
}