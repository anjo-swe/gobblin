/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package gobblin.metastore;

import static gobblin.util.HadoopUtils.FS_SCHEMES_NON_ATOMIC;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.compress.DefaultCodec;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Closer;

import gobblin.configuration.State;
import gobblin.util.HadoopUtils;


/**
 * An implementation of {@link StateStore} backed by a {@link FileSystem}.
 *
 * <p>
 *     This implementation uses Hadoop {@link org.apache.hadoop.io.SequenceFile}
 *     to store {@link State}s. Each store maps to one directory, and each
 *     table maps to one file under the store directory. Keys are state IDs
 *     (see {@link State#getId()}), and values are objects of {@link State} or
 *     any of its extensions. Keys will be empty strings if state IDs are not set
 *     (i.e., {@link State#getId()} returns <em>null</em>). In this case, the
 *     {@link FsStateStore#get(String, String, String)} method may not work.
 * </p>
 *
 * @param <T> state object type
 *
 * @author Yinan Li
 */
@Slf4j
public class FsStateStore<T extends State> implements StateStore<T> {

  public static final String TMP_FILE_PREFIX = "_tmp_";
  public static final String CURRENT_FILE_NAME = "current";
  protected static final String TABLE_PREFIX_SEPARATOR = "-";

  protected final Configuration conf;
  protected final FileSystem fs;
  protected boolean useTmpFileForPut;

  // Root directory for the task state store
  protected final String storeRootDir;

  // Class of the state objects to be put into the store
  private final Class<T> stateClass;

  private final String extension;

  public FsStateStore(String fsUri, String storeRootDir, Class<T> stateClass, String extension) throws IOException {
    this(FileSystem.get(URI.create(fsUri), new Configuration()), storeRootDir, stateClass, extension);
  }

  public FsStateStore(String storeUrl, Class<T> stateClass, String extension) throws IOException {
    this(new Path(storeUrl), stateClass, extension);
  }

  public FsStateStore(Path storePath, Class<T> stateClass, String extension) throws IOException {
    this(storePath.getFileSystem(new Configuration()), storePath.toUri().getPath(), stateClass, extension);
  }

  public FsStateStore(FileSystem fs, String storeRootDir, Class<T> stateClass, String extension) throws IOException {
    this.fs = fs;
    this.useTmpFileForPut = !FS_SCHEMES_NON_ATOMIC.contains(this.fs.getUri().getScheme());
    this.conf = this.fs.getConf();
    this.storeRootDir = storeRootDir;
    this.stateClass = stateClass;
    this.extension = normalizeExtension(extension);
  }

  /**
   * Create a new store.
   *
   * <p>
   *     A store that does not exist will be created when any put
   *     method is called against it.
   * </p>
   *
   * @param storeName store name
   * @return if the store is successfully created
   * @throws IOException
   */
  @Override
  public boolean create(String storeName) throws IOException {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(storeName), "Store name is null or empty.");

    Path storePath = new Path(this.storeRootDir, storeName);
    return this.fs.exists(storePath) || this.fs.mkdirs(storePath);
  }

  /**
   * Create a new table in a store.
   *
   * <p>
   *     A table that does not exist will be created when any put
   *     method is called against it.
   * </p>
   *
   * @param storeName store name
   * @param tableName table name
   * @return if the table is successfully created
   * @throws IOException
   */
  @Override
  public boolean create(String storeName, String tableName) throws IOException {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(storeName), "Store name is null or empty.");
    Preconditions.checkArgument(!Strings.isNullOrEmpty(tableName), "Table name is null or empty.");
    Preconditions.checkArgument(!isCurrentFile(tableName),
        String.format("Table name is %s or %s%s", CURRENT_FILE_NAME, CURRENT_FILE_NAME, this.extension));

    Path storePath = new Path(this.storeRootDir, storeName);
    if (!this.fs.exists(storePath) && !create(storeName)) {
      return false;
    }

    Path tablePath = new Path(storePath, normalizeTableName(tableName));
    if (this.fs.exists(tablePath)) {
      throw new IOException(String.format("State file %s already exists for table %s", tablePath, tableName));
    }

    return this.fs.createNewFile(tablePath);
  }

  /**
   * Check whether a given table exists.
   *
   * @param storeName store name
   * @param tableName table name
   * @return whether the given table exists
   * @throws IOException
   */
  @Override
  public boolean exists(String storeName, String tableName) throws IOException {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(storeName), "Store name is null or empty.");
    Preconditions.checkArgument(!Strings.isNullOrEmpty(tableName), "Table name is null or empty.");

    Path tablePath = getTablePath(storeName, tableName);
    return tablePath != null && this.fs.exists(tablePath);
  }

    /**
   * See {@link StateStore#put(String, String, T)}.
   *
   * <p>
   *   This implementation does not support putting the state object into an existing store as
   *   append is to be supported by the Hadoop SequenceFile (HADOOP-7139).
   * </p>
   */
  @Override
  public void put(String storeName, String tableName, T state) throws IOException {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(storeName), "Store name is null or empty.");
    Preconditions.checkArgument(!Strings.isNullOrEmpty(tableName), "Table name is null or empty.");
    Preconditions.checkArgument(!isCurrentFile(tableName),
        String.format("Table name is %s or %s%s", CURRENT_FILE_NAME, CURRENT_FILE_NAME, this.extension));
    Preconditions.checkNotNull(state, "State is null.");

    this.putAll(storeName, tableName, ImmutableList.of(state));
  }

  /**
   * See {@link StateStore#putAll(String, String, Collection)}.
   *
   * <p>
   *   This implementation does not support putting the state objects into an existing store as
   *   append is to be supported by the Hadoop SequenceFile (HADOOP-7139).
   * </p>
   */
  @Override
  public void putAll(String storeName, String tableName, Collection<T> states) throws IOException {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(storeName), "Store name is null or empty.");
    Preconditions.checkArgument(!Strings.isNullOrEmpty(tableName), "Table name is null or empty.");
    Preconditions.checkArgument(!isCurrentFile(tableName),
        String.format("Table name is %s or %s%s", CURRENT_FILE_NAME, CURRENT_FILE_NAME, this.extension));
    Preconditions.checkNotNull(states, "States is null.");

    String normalizedTableName = normalizeTableName(tableName);
    String tmpTableName = this.useTmpFileForPut ? TMP_FILE_PREFIX + normalizedTableName : normalizedTableName;
    Path tmpTablePath = new Path(new Path(this.storeRootDir, storeName), tmpTableName);

    if (!this.fs.exists(tmpTablePath) && !create(storeName, tmpTableName)) {
      throw new IOException("Failed to create a state file for table " + tmpTableName);
    }

    Closer closer = Closer.create();
    try {
      SequenceFile.Writer writer = closer.register(SequenceFile.createWriter(this.fs, this.conf, tmpTablePath,
          Text.class, this.stateClass, SequenceFile.CompressionType.BLOCK, new DefaultCodec()));
      for (T state : states) {
        writer.append(new Text(Strings.nullToEmpty(state.getId())), state);
      }
    } catch (Throwable t) {
      throw closer.rethrow(t);
    } finally {
      closer.close();
    }

    if (this.useTmpFileForPut) {
      Path tablePath = new Path(new Path(this.storeRootDir, storeName), normalizedTableName);
      HadoopUtils.renamePath(this.fs, tmpTablePath, tablePath);
    }
  }

  /**
   * Get all {@link State}s from a table.
   *
   * @param storeName store name
   * @param tableName table name
   * @return (possibly empty) list of {@link State}s from the given table
   * @throws IOException
   */
  @Override
  public T get(String storeName, String tableName, String stateId) throws IOException {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(storeName), "Store name is null or empty.");
    Preconditions.checkArgument(!Strings.isNullOrEmpty(tableName), "Table name is null or empty.");
    Preconditions.checkArgument(!Strings.isNullOrEmpty(stateId), "State id is null or empty.");

    Path tablePath = getTablePath(storeName, tableName);
    if (tablePath == null || !this.fs.exists(tablePath)) {
      return null;
    }

    Closer closer = Closer.create();
    try {
      @SuppressWarnings("deprecation")
      SequenceFile.Reader reader = closer.register(new SequenceFile.Reader(this.fs, tablePath, this.conf));
      try {
        Text key = new Text();
        T state = this.stateClass.newInstance();
        while (reader.next(key, state)) {
          if (key.toString().equals(stateId)) {
            return state;
          }
        }
      } catch (Exception e) {
        throw new IOException(e);
      }
    } catch (Throwable t) {
      throw closer.rethrow(t);
    } finally {
      closer.close();
    }

    return null;
  }

  /**
   * Get all {@link State}s from the current table.
   *
   * @param storeName store name
   * @return (possibly empty) list of {@link State}s from the current table
   * @throws IOException
   */
  @Override
  public List<T> getAllCurrent(String storeName) throws IOException {
    return getAll(storeName, CURRENT_FILE_NAME);
  }

    /**
   * Get a {@link State} with a given state ID from a the current table.
   *
   * @param storeName store name
   * @param stateId state ID
   * @return {@link State} with the given state ID or <em>null</em>
   *         if the state with the given state ID does not exist
   * @throws IOException
   */
  @Override
  public T getCurrent(String storeName, String stateId) throws IOException {
    return get(storeName, CURRENT_FILE_NAME, stateId);
  }

  @Override
  public List<T> getAll(String storeName, String tableName) throws IOException {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(storeName), "Store name is null or empty.");
    Preconditions.checkArgument(!Strings.isNullOrEmpty(tableName), "Table name is null or empty.");

    List<T> states = Lists.newArrayList();

    Path tablePath = getTablePath(storeName, tableName);
    if (tablePath == null || !this.fs.exists(tablePath)) {
      return states;
    }

    Closer closer = Closer.create();
    try {
      @SuppressWarnings("deprecation")
      SequenceFile.Reader reader = closer.register(new SequenceFile.Reader(this.fs, tablePath, this.conf));
      try {
        Text key = new Text();
        T state = this.stateClass.newInstance();
        while (reader.next(key, state)) {
          states.add(state);
          // We need a new object for each read state
          state = this.stateClass.newInstance();
        }
      } catch (Exception e) {
        throw new IOException(e);
      }
    } catch (Throwable t) {
      throw closer.rethrow(t);
    } finally {
      closer.close();
    }

    return states;
  }

  @Override
  public List<T> getAll(String storeName) throws IOException {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(storeName), "Store name is null or empty.");

    List<T> states = Lists.newArrayList();

    Path storePath = new Path(this.storeRootDir, storeName);
    if (!this.fs.exists(storePath)) {
      return states;
    }

    for (FileStatus status : this.fs.listStatus(storePath)) {
      states.addAll(getAll(storeName, status.getPath().getName()));
    }

    return states;
  }

  @Override
  public void delete(String storeName, String tableName) throws IOException {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(storeName), "Store name is null or empty.");
    Preconditions.checkArgument(!Strings.isNullOrEmpty(tableName), "Table name is null or empty.");
    Preconditions.checkArgument(!isCurrentFile(tableName),
          String.format("Table name is %s or %s%s", CURRENT_FILE_NAME, CURRENT_FILE_NAME, this.extension));

    Path tablePath = getTablePath(storeName, tableName);
    if (tablePath != null && this.fs.exists(tablePath)) {
      this.fs.delete(tablePath, false);
    }
  }


  @Override
  public void delete(String storeName) throws IOException {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(storeName), "Store name is null or empty.");

    Path storePath = new Path(this.storeRootDir, storeName);
    if (this.fs.exists(storePath)) {
      this.fs.delete(storePath, true);
    }
  }

  protected Path getTablePath(String storeName, String tableName) throws IOException {
    TableInfo tableInfo = getTableInfo(tableName);
    if (tableInfo.isCurrent()) {
      return getLatestStateFilePath(storeName, tableInfo.getPrefix());
    }
    return new Path(new Path(this.storeRootDir, storeName), normalizeTableName(tableName));
  }

  private Path getLatestStateFilePath(String storeName, final String tableNamePrefix) throws IOException {
    Path stateStorePath = new Path(this.storeRootDir, storeName);
    if (!this.fs.exists(stateStorePath)) {
      return null;
    }

    FileStatus[] stateStoreFileStatuses = this.fs.listStatus(stateStorePath, new PathFilter() {
      @Override
      public boolean accept(Path path) {
        String name = path.getName();
        return (Strings.isNullOrEmpty(tableNamePrefix) || name.startsWith(tableNamePrefix + TABLE_PREFIX_SEPARATOR)) &&
            name.endsWith(extension);
      }
    });

    String latestStateFilePath = null;
    for (FileStatus fileStatus : stateStoreFileStatuses) {
      String tableName = fileStatus.getPath().getName();
      if (latestStateFilePath == null) {
        log.debug("Latest table for {}/{}* set to {}", storeName, tableNamePrefix, tableName);
        latestStateFilePath = tableName;
      } else if (tableName.compareTo(latestStateFilePath) > 0) {
        log.debug("Latest table for {}/{}* set to {} instead of {}", storeName, tableNamePrefix, tableName, latestStateFilePath);
        latestStateFilePath = tableName;
      } else {
        log.debug("Latest table for {}/{}* left as {}. Previous table {} is being ignored", storeName, tableNamePrefix, latestStateFilePath, tableName);
      }
    }

    return latestStateFilePath == null ? null : new Path(new Path(this.storeRootDir, storeName), latestStateFilePath);
  }

  private TableInfo getTableInfo(String tableName) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(tableName), "Table name is null or empty.");
    String name = tableName;
    if (name.endsWith(this.extension)) {
      name = name.substring(0, tableName.length() - this.extension.length());
    }
    if (CURRENT_FILE_NAME.equalsIgnoreCase(name)) {
      return new TableInfo("", true);
    }
    int suffixIndex = name.lastIndexOf(TABLE_PREFIX_SEPARATOR);
    if (suffixIndex >= 0) {
      String prefix = suffixIndex > 0 ? name.substring(0, suffixIndex) : "";
      String suffix = suffixIndex < name.length() - 1 ? name.substring(suffixIndex + 1) : "";
      if (CURRENT_FILE_NAME.equalsIgnoreCase(suffix)) {
        return new TableInfo(prefix, true);
      } else {
        return new TableInfo(prefix, false);
      }
    }
    return new TableInfo("", false);
  }

  private boolean isCurrentFile(String tableName) {
    TableInfo tableInfo = getTableInfo(tableName);
    return tableInfo.isCurrent();
  }

  private String normalizeExtension(String extension) {
    return extension.startsWith(".") ? extension : "." + extension;
  }

  private String normalizeTableName(String tableName) {
    return tableName.endsWith(this.extension) ? tableName : tableName + this.extension;
  }

  @AllArgsConstructor
  private class TableInfo {
    @Getter
    private String prefix;

    @Getter
    private boolean isCurrent;
  }
}
