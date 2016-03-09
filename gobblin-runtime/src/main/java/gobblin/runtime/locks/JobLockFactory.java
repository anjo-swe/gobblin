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

package gobblin.runtime.locks;

import java.util.Properties;

import gobblin.configuration.ConfigurationKeys;


/**
 * The factory used to create instances of {@link JobLock}.
 *
 * @author joelbaranick
 */
public class JobLockFactory {
  private JobLockFactory() {
  }

  /**
   * Gets an instance of {@link JobLock}.
   *
   * @param properties the properties used to determine which instance of {@link JobLock} to create and the
   *                   relevant settings
   * @param jobLockEventListener the {@link JobLock} event listener
   * @return an instance of {@link JobLock}
   * @throws JobLockException throw when the {@link JobLock} fails to initialize
   */
  public static JobLock getJobLock(Properties properties, JobLockEventListener jobLockEventListener)
          throws JobLockException {
    JobLock jobLock;
    if (properties.containsKey(ConfigurationKeys.JOB_LOCK_TYPE)) {
      try {
        Class<?> jobLockClass = Class.forName(
                properties.getProperty(ConfigurationKeys.JOB_LOCK_TYPE, FileBasedJobLock.class.getSimpleName()));
        jobLock = (JobLock) jobLockClass.newInstance();
      } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
        jobLock = new FileBasedJobLock();
      }
    } else {
      jobLock = new FileBasedJobLock();
    }
    jobLock.initialize(properties, jobLockEventListener);
    return jobLock;
  }
}
