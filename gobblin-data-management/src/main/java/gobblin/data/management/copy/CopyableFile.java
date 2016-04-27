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

package gobblin.data.management.copy;

import gobblin.data.management.partition.File;
import gobblin.data.management.copy.PreserveAttributes.Option;
import gobblin.util.PathUtils;
import gobblin.util.concurrent.GobblinCallable;
import gobblin.util.guid.Guid;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.apache.hadoop.fs.FileChecksum;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;

import com.google.common.collect.Lists;


/**
 * Abstraction for a file to copy from {@link #origin} to {@link #destination}. {@link CopyableFile}s should be
 * created using a {@link CopyableFile.Builder} obtained with the method {@link CopyableFile#builder}.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(callSuper = true)
public class CopyableFile extends CopyEntity implements File {

  /** {@link FileStatus} of the existing origin file. */
  private FileStatus origin;

  /** Complete destination {@link Path} of the file. */
  private Path destination;

  /** Desired {@link OwnerAndPermission} of the destination path. */
  private OwnerAndPermission destinationOwnerAndPermission;

  /**
   * Desired {@link OwnerAndPermission} of the ancestor directories of the destination path. The list is ordered from
   * deepest to highest directory.
   *
   * <p>
   * For example, if {@link #destination} is /a/b/c/file, then the first element of this list is the desired owner and
   * permission for directory /a/b/c, the second is the desired owner and permission for directory /a/b, and so on.
   * </p>
   *
   * <p>
   * If there are fewer elements in the list than ancestor directories in {@link #destination}, it is understood that
   * extra directories are allowed to have any owner and permission.
   * </p>
   */
  private List<OwnerAndPermission> ancestorsOwnerAndPermission;

  /** Checksum of the origin file. */
  private byte[] checksum;
  /** Attributes to be preserved. */
  private PreserveAttributes preserve;
  /** Timestamp of file at its origin source. */
  private long originTimestamp;
  /** Timestamp of file as in upstream. */
  private long upstreamTimestamp;

  @lombok.Builder(builderClassName = "Builder", builderMethodName = "_hiddenBuilder")
  public CopyableFile(FileStatus origin, Path destination, OwnerAndPermission destinationOwnerAndPermission,
      List<OwnerAndPermission> ancestorsOwnerAndPermission, byte[] checksum, PreserveAttributes preserve,
      String fileSet, long originTimestamp, long upstreamTimestamp, Map<String, Object> additionalMetadata) {
    super(fileSet, additionalMetadata);
    this.origin = origin;
    this.destination = destination;
    this.destinationOwnerAndPermission = destinationOwnerAndPermission;
    this.ancestorsOwnerAndPermission = ancestorsOwnerAndPermission;
    this.checksum = checksum;
    this.preserve = preserve;
    this.originTimestamp = originTimestamp;
    this.upstreamTimestamp = upstreamTimestamp;
  }

  /**
   * Get a {@link CopyableFile.Builder}.
   *
   * @param originFs {@link FileSystem} where original file exists.
   * @param origin {@link FileStatus} of the original file.
   * @param datasetRoot Value of {@link CopyableDataset#datasetRoot} of the dataset creating this {@link CopyableFile}.
   * @param copyConfiguration {@link CopyConfiguration} for the copy job.
   * @return a {@link CopyableFile.Builder}.
   * @deprecated use {@link #fromOriginAndDestination}. This method was changed to remove reliance on dataset root
   *             which is not standard of all datasets. The old functionality on inferring destinations cannot be
   *             achieved without dataset root and common dataset root, so this is an approximation. Copyable datasets
   *             should compute file destinations themselves.
   */
  @Deprecated
  public static Builder builder(FileSystem originFs, FileStatus origin, Path datasetRoot,
      CopyConfiguration copyConfiguration) {

    Path relativePath = PathUtils.relativizePath(origin.getPath(), datasetRoot);

    Path targetRoot = new Path(copyConfiguration.getPublishDir(), datasetRoot.getName());
    Path targetPath = new Path(targetRoot, relativePath);

    return _hiddenBuilder().originFS(originFs).
        origin(origin).destination(targetPath).
        preserve(copyConfiguration.getPreserve()).configuration(copyConfiguration);
  }

  public static Builder fromOriginAndDestination(FileSystem originFs, FileStatus origin, Path destination,
      CopyConfiguration copyConfiguration) {
    return _hiddenBuilder().originFS(originFs).origin(origin).destination(destination).
        configuration(copyConfiguration).preserve(copyConfiguration.getPreserve());
  }

  /**
   * Builder for creating {@link CopyableFile}s.
   *
   * Allows the {@link CopyableDataset} to set any field of the {@link CopyableFile}, but infers any unset fields
   * to facilitate creation of custom {@link CopyableDataset}s. See javadoc for {@link CopyableFile.Builder#build} for
   * inference information.
   */
  public static class Builder {

    private CopyConfiguration configuration;
    private FileSystem originFs;
    private Map<String, Object> additionalMetadata;

    private Builder originFS(FileSystem originFs) {
      this.originFs = originFs;
      return this;
    }

    private Builder configuration(CopyConfiguration configuration) {
      this.configuration = configuration;
      return this;
    }

    /**
     * Builds a {@link CopyableFile} using fields set by the {@link CopyableDataset} and inferring unset fields.
     * If the {@link Builder} was obtained through {@link CopyableFile#builder}, it is safe to call this method
     * even without setting any other fields (they will all be inferred).
     *
     * <p>
     *   The inferred fields are as follows:
     *   * {@link CopyableFile#destinationOwnerAndPermission}: Copy attributes from origin {@link FileStatus} depending
     *       on the {@link PreserveAttributes} flags {@link #preserve}. Non-preserved attributes are left null,
     *       allowing Gobblin distcp to use defaults for the target {@link FileSystem}.
     *   * {@link CopyableFile#ancestorsOwnerAndPermission}: Copy attributes from ancestors of origin path depending
     *       on the {@link PreserveAttributes} flags {@link #preserve}. Non-preserved attributes are left null,
     *       allowing Gobblin distcp to use defaults for the target {@link FileSystem}.
     *   * {@link CopyableFile#checksum}: the checksum of the origin {@link FileStatus} obtained using the origin
     *       {@link FileSystem}.
     *   * {@link CopyableFile#fileSet}: empty string. Used as default file set per dataset.
     * </p>
     *
     * @return A {@link CopyableFile}.
     * @throws IOException
     */
    public CopyableFile build() throws IOException {

      if (!this.destination.isAbsolute()) {
        throw new IOException("Destination must be absolute: " + this.destination);
      }

      if (this.destinationOwnerAndPermission == null) {
        String owner = this.preserve.preserve(Option.OWNER) ? this.origin.getOwner() : null;

        String group = null;
        if (this.preserve.preserve(Option.GROUP)) {
          group = this.origin.getGroup();
        } else if (this.configuration.getTargetGroup().isPresent()) {
          group = this.configuration.getTargetGroup().get();
        }

        FsPermission permission = this.preserve.preserve(Option.PERMISSION) ? this.origin.getPermission() : null;

        this.destinationOwnerAndPermission =
            new OwnerAndPermission(owner, group, permission);
      }
      if (this.ancestorsOwnerAndPermission == null) {
        this.ancestorsOwnerAndPermission = replicateOwnerAndPermission(this.originFs, this.origin.getPath(), this.preserve);
      }
      if (this.checksum == null) {
        FileChecksum checksumTmp = this.originFs.getFileChecksum(origin.getPath());
        this.checksum = checksumTmp == null ? new byte[0] : checksumTmp.getBytes();
      }
      if (this.fileSet == null) {
        // Default file set per dataset
        this.fileSet = "";
      }
      if (this.originTimestamp == 0) {
        this.originTimestamp = origin.getModificationTime();
      }
      if (this.upstreamTimestamp == 0) {
        this.upstreamTimestamp = origin.getModificationTime();
      }

      return new CopyableFile(this.origin, this.destination,
          this.destinationOwnerAndPermission, this.ancestorsOwnerAndPermission, this.checksum, this.preserve, this.fileSet,
          this.originTimestamp, this.upstreamTimestamp, this.additionalMetadata);
    }

    private List<OwnerAndPermission> replicateOwnerAndPermission(final FileSystem originFs, final Path path,
        PreserveAttributes preserve) throws IOException {

      List<OwnerAndPermission> ancestorOwnerAndPermissions = Lists.newArrayList();
      try {
        Path currentPath = PathUtils.getPathWithoutSchemeAndAuthority(path);
        while (currentPath != null && currentPath.getParent() != null) {
          currentPath = currentPath.getParent();
          final Path thisPath = currentPath;
          OwnerAndPermission ownerAndPermission = this.configuration.getCopyContext().getOwnerAndPermissionCache()
              .get(originFs.makeQualified(currentPath),
                  new GobblinCallable<OwnerAndPermission>() {
                    @Override public OwnerAndPermission callImpl() throws Exception {
                      FileStatus fs = originFs.getFileStatus(thisPath);
                      return new OwnerAndPermission(fs.getOwner(), fs.getGroup(), fs.getPermission());
                    }
                  });

          String group = null;
          if (this.preserve.preserve(Option.GROUP)) {
            group = ownerAndPermission.getGroup();
          } else if (this.configuration.getTargetGroup().isPresent()) {
            group = this.configuration.getTargetGroup().get();
          }
          ancestorOwnerAndPermissions.add(new OwnerAndPermission(
              preserve.preserve(Option.OWNER) ? ownerAndPermission.getOwner() : null, group,
              preserve.preserve(Option.PERMISSION) ? ownerAndPermission.getFsPermission() : null));
        }
      } catch (ExecutionException ee) {
        throw new IOException(ee.getCause());
      }
      return ancestorOwnerAndPermissions;
    }

  }

  @Override
  public FileStatus getFileStatus() {
    return this.origin;
  }

  /**
   * Generates a replicable guid to uniquely identify the origin of this {@link CopyableFile}.
   * @return a guid uniquely identifying the origin file.
   */
  public Guid guid() throws IOException {
    StringBuilder uniqueString = new StringBuilder();
    uniqueString.append(getFileStatus().getModificationTime());
    uniqueString.append(getFileStatus().getLen());
    uniqueString.append(getFileStatus().getPath());
    return Guid.fromStrings(uniqueString.toString());
  }

}
