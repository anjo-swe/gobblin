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

package gobblin.yarn.token;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Master;
import org.apache.hadoop.mapreduce.security.TokenCache;
import org.apache.hadoop.mapreduce.security.token.delegation.DelegationTokenIdentifier;
import org.apache.hadoop.mapreduce.server.jobtracker.JTConfig;
import org.apache.hadoop.mapreduce.v2.api.HSClientProtocol;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetDelegationTokenRequest;
import org.apache.hadoop.mapreduce.v2.jobhistory.JHAdminConfig;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.ipc.YarnRPC;
import org.apache.hadoop.yarn.util.ConverterUtils;

import com.google.common.base.Preconditions;

import gobblin.configuration.State;

import lombok.extern.slf4j.Slf4j;


/**
 * A utility class for obtain Hadoop tokens and Hive metastore tokens for Azkaban jobs.
 *
 * <p>
 *   This class is compatible with Hadoop 2.
 * </p>
 *
 * @author ziliu
 */
@Slf4j
public class TokenUtils {
  private static final String USER_TO_PROXY = "user.to.proxy";
  private static final String OTHER_NAMENODES = "other.namenodes";
  private static final String KEYTAB_USER = "keytab.user";
  private static final String KEYTAB_LOCATION = "keytab.location";
  private static final String HADOOP_SECURITY_AUTHENTICATION = "hadoop.security.authentication";
  private static final String KERBEROS = "kerberos";
  private static final String YARN_RESOURCEMANAGER_PRINCIPAL = "yarn.resourcemanager.principal";
  private static final String YARN_RESOURCEMANAGER_ADDRESS = "yarn.resourcemanager.address";
  private static final String MAPRED_JOB_TRACKER = "mapred.job.tracker";
  private static final String MAPREDUCE_JOBTRACKER_ADDRESS = "mapreduce.jobtracker.address";

  /**
   * Get Hadoop tokens (tokens for job history server, job tracker and HDFS).
   *
   * @param state A {@link State} object that should contain property {@link #USER_TO_PROXY},
   * {@link #KEYTAB_USER} and {@link #KEYTAB_LOCATION}. To obtain tokens for
   * other namenodes, use property {@link #OTHER_NAMENODES} with comma separated HDFS URIs.
   */
  public static void getHadoopTokens(final State state) throws IOException, InterruptedException {

    Preconditions.checkArgument(state.contains(USER_TO_PROXY), "Missing required property " + USER_TO_PROXY);
    Preconditions.checkArgument(state.contains(KEYTAB_USER), "Missing required property " + KEYTAB_USER);
    Preconditions.checkArgument(state.contains(KEYTAB_LOCATION), "Missing required property " + KEYTAB_LOCATION);

    UserGroupInformation.loginUserFromKeytab(state.getProp(KEYTAB_USER), state.getProp(KEYTAB_LOCATION));

    final String userToProxy = state.getProp(USER_TO_PROXY);
    final Configuration conf = new Configuration();
    final Credentials cred = new Credentials();

    log.info("Getting tokens for " + userToProxy);

    getJhToken(conf, userToProxy, cred);
    getFsAndJtTokens(state, conf, userToProxy, cred);
    persisteTokens(cred);
  }

  private static void getJhToken(Configuration conf, String userToProxy, Credentials cred) throws IOException {
    YarnRPC rpc = YarnRPC.create(conf);
    final String serviceAddr = conf.get(JHAdminConfig.MR_HISTORY_ADDRESS);

    log.debug("Connecting to HistoryServer at: " + serviceAddr);
    HSClientProtocol hsProxy =
        (HSClientProtocol) rpc.getProxy(HSClientProtocol.class, NetUtils.createSocketAddr(serviceAddr), conf);
    log.info("Pre-fetching JH token from job history server");

    Token<?> jhToken = null;
    try {
      jhToken = getDelegationTokenFromHS(hsProxy, conf);
    } catch (Exception e) {
      log.error("Failed to fetch JH token", e);
      throw new IOException("Failed to fetch JH token for " + userToProxy);
    }

    if (jhToken == null) {
      log.error("getDelegationTokenFromHS() returned null");
      throw new IOException("Unable to fetch JH token for " + userToProxy);
    }

    log.info("Created JH token: " + jhToken.toString());
    log.info("Token kind: " + jhToken.getKind());
    log.info("Token id: " + jhToken.getIdentifier());
    log.info("Token service: " + jhToken.getService());

    cred.addToken(jhToken.getService(), jhToken);
  }

  private static void getFsAndJtTokens(final State state, final Configuration conf, final String userToProxy,
      final Credentials cred) throws IOException, InterruptedException {
    UserGroupInformation.createProxyUser(userToProxy, UserGroupInformation.getLoginUser())
        .doAs(new PrivilegedExceptionAction<Void>() {
          @Override
          public Void run() throws Exception {
            getToken(userToProxy);
            return null;
          }

          private void getToken(String userToProxy) throws InterruptedException, IOException {

            getHdfsToken(conf, userToProxy, cred);
            if (state.contains(OTHER_NAMENODES)) {
              getOtherNamenodesToken(state.getPropAsList(OTHER_NAMENODES), conf, userToProxy, cred);
            }
            getJtToken(userToProxy, cred);

          }
        });
  }

  private static void getHdfsToken(Configuration conf, String userToProxy, Credentials cred) throws IOException {
    FileSystem fs = FileSystem.get(conf);
    log.info("Getting DFS token from " + fs.getUri());
    Token<?> fsToken = fs.getDelegationToken(getMRTokenRenewerInternal(new JobConf()).toString());
    if (fsToken == null) {
      log.error("Failed to fetch DFS token for ");
      throw new IOException("Failed to fetch DFS token for " + userToProxy);
    }
    log.info("Created DFS token: " + fsToken.toString());
    log.info("Token kind: " + fsToken.getKind());
    log.info("Token id: " + fsToken.getIdentifier());
    log.info("Token service: " + fsToken.getService());

    cred.addToken(fsToken.getService(), fsToken);
  }

  private static void getOtherNamenodesToken(List<String> otherNamenodes, Configuration conf, String userToProxy,
      Credentials cred) throws IOException {
    log.info(OTHER_NAMENODES + ": " + otherNamenodes);
    Path[] ps = new Path[otherNamenodes.size()];
    for (int i = 0; i < ps.length; i++) {
      ps[i] = new Path(otherNamenodes.get(i).trim());
    }
    TokenCache.obtainTokensForNamenodes(cred, ps, conf);
    log.info("Successfully fetched tokens for: " + otherNamenodes);
  }

  private static void getJtToken(String userToProxy, Credentials cred) throws IOException, InterruptedException {
    JobConf jobConf = new JobConf();
    JobClient jobClient = new JobClient(jobConf);
    log.info("Pre-fetching JT token from JobTracker");

    Token<DelegationTokenIdentifier> mrdt = jobClient.getDelegationToken(getMRTokenRenewerInternal(jobConf));
    if (mrdt == null) {
      log.error("Failed to fetch JT token");
      throw new IOException("Failed to fetch JT token for " + userToProxy);
    }
    log.info("Created JT token: " + mrdt.toString());
    log.info("Token kind: " + mrdt.getKind());
    log.info("Token id: " + mrdt.getIdentifier());
    log.info("Token service: " + mrdt.getService());
    cred.addToken(mrdt.getService(), mrdt);
  }

  private static void persisteTokens(Credentials cred) throws IOException {

    FileOutputStream fos = null;
    DataOutputStream dos = null;
    File tokenFile;
    try {
      tokenFile = File.createTempFile("mr-azkaban", ".token");
      fos = new FileOutputStream(tokenFile);
      dos = new DataOutputStream(fos);
      cred.writeTokenStorageToStream(dos);
    } finally {
      if (dos != null) {
        try {
          dos.close();
        } catch (Throwable t) {
          log.error("encountered exception while closing DataOutputStream of the tokenFile", t);
        }
      }
      if (fos != null) {
        fos.close();
      }
    }
    log.info("Tokens loaded in " + tokenFile.getAbsolutePath());
  }

  private static Token<?> getDelegationTokenFromHS(HSClientProtocol hsProxy, Configuration conf)
      throws IOException, InterruptedException {
    GetDelegationTokenRequest request =
        RecordFactoryProvider.getRecordFactory(null).newRecordInstance(GetDelegationTokenRequest.class);
    request.setRenewer(Master.getMasterPrincipal(conf));
    org.apache.hadoop.yarn.api.records.Token mrDelegationToken;
    mrDelegationToken = hsProxy.getDelegationToken(request).getDelegationToken();
    return ConverterUtils.convertFromYarn(mrDelegationToken, hsProxy.getConnectAddress());
  }

  private static Text getMRTokenRenewerInternal(JobConf jobConf) throws IOException {
    String servicePrincipal = jobConf.get(YARN_RESOURCEMANAGER_PRINCIPAL, jobConf.get(JTConfig.JT_USER_NAME));
    Text renewer;
    if (servicePrincipal != null) {
      String target = jobConf.get(YARN_RESOURCEMANAGER_ADDRESS, jobConf.get(MAPREDUCE_JOBTRACKER_ADDRESS));
      if (target == null) {
        target = jobConf.get(MAPRED_JOB_TRACKER);
      }

      String addr = NetUtils.createSocketAddr(target).getHostName();
      renewer = new Text(SecurityUtil.getServerPrincipal(servicePrincipal, addr));
    } else {
      // No security
      renewer = new Text("azkaban mr tokens");
    }

    return renewer;
  }

  /**
   * Get a {@link HiveConf} using the specified metastore Uri.
   *
   * @param state A {@link State} object that should contain properties {@link #KEYTAB_USER} and
   * {@link #KEYTAB_LOCATION}.
   * @param metastoreUri Uri of the Hive metastore
   * @return a {@link HiveConf} for the specified Hive metastore.
   */
  public static HiveConf getHiveConf(State state, String metastoreUri) throws IOException {
    Preconditions.checkArgument(state.contains(KEYTAB_USER), "Missing required property " + KEYTAB_USER);
    Preconditions.checkArgument(state.contains(KEYTAB_LOCATION), "Missing required property " + KEYTAB_LOCATION);

    return getHiveConf(state.getProp(KEYTAB_USER), state.getProp(KEYTAB_LOCATION), metastoreUri);
  }

  /**
   * Get a {@link HiveConf} using the specified metastore Uri.
   *
   * @param keytabUser user name of the keytab used to log in via {@link UserGroupInformation}.
   * @param keytabLocation location of the keytab used to log in via {@link UserGroupInformation}.
   * @param metastoreUri Uri of the Hive metastore
   * @return a {@link HiveConf} for the specified Hive metastore.
   */
  public static HiveConf getHiveConf(String keytabUser, String keytabLocation, String metastoreUri) throws IOException {
    HiveConf hiveConf = new HiveConf();
    hiveConf.set(HiveConf.ConfVars.METASTOREURIS.varname, metastoreUri);
    hiveConf.set(HADOOP_SECURITY_AUTHENTICATION, KERBEROS);
    UserGroupInformation.setConfiguration(hiveConf);
    UserGroupInformation.loginUserFromKeytab(keytabUser, keytabLocation);
    return hiveConf;
  }
}
