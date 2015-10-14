/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.security.token;

import static org.apache.hadoop.fs.CommonConfigurationKeys.HADOOP_SECURITY_AUTHORIZATION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.ClusterId;
import org.apache.hadoop.hbase.Coprocessor;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.catalog.CatalogTracker;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.ipc.BlockingRpcCallback;
import org.apache.hadoop.hbase.ipc.FifoRpcScheduler;
import org.apache.hadoop.hbase.ipc.RequestContext;
import org.apache.hadoop.hbase.ipc.RpcClient;
import org.apache.hadoop.hbase.ipc.RpcServer;
import org.apache.hadoop.hbase.ipc.RpcServer.BlockingServiceAndInterface;
import org.apache.hadoop.hbase.ipc.RpcServerInterface;
import org.apache.hadoop.hbase.ipc.ServerRpcController;
import org.apache.hadoop.hbase.protobuf.generated.AuthenticationProtos;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.RegionServerServices;
import org.apache.hadoop.hbase.security.SecurityInfo;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.Sleeper;
import org.apache.hadoop.hbase.util.Strings;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.util.Writables;
import org.apache.hadoop.hbase.zookeeper.ZKClusterId;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.hadoop.net.DNS;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.PolicyProvider;
import org.apache.hadoop.security.authorize.Service;
import org.apache.hadoop.security.token.SecretManager;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.google.protobuf.BlockingRpcChannel;
import com.google.protobuf.BlockingService;
import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;

/**
 * Tests for authentication token creation and usage
 */
@Category(MediumTests.class)
public class TestTokenAuthentication {
  static {
    // Setting whatever system properties after recommendation from
    // http://docs.oracle.com/javase/6/docs/technotes/guides/security/jgss/tutorials/KerberosReq.html
    System.setProperty("java.security.krb5.realm", "hbase");
    System.setProperty("java.security.krb5.kdc", "blah");
  }
  private static Log LOG = LogFactory.getLog(TestTokenAuthentication.class);

  public interface AuthenticationServiceSecurityInfo {}

  /**
   * Basic server process for RPC authentication testing
   */
  private static class TokenServer extends TokenProvider
  implements AuthenticationProtos.AuthenticationService.BlockingInterface, Runnable, Server {
    private static Log LOG = LogFactory.getLog(TokenServer.class);
    private Configuration conf;
    private RpcServerInterface rpcServer;
    private InetSocketAddress isa;
    private ZooKeeperWatcher zookeeper;
    private Sleeper sleeper;
    private boolean started = false;
    private boolean aborted = false;
    private boolean stopped = false;
    private long startcode;

    public TokenServer(Configuration conf) throws IOException {
      this.conf = conf;
      this.startcode = EnvironmentEdgeManager.currentTimeMillis();
      // Server to handle client requests.
      String hostname =
        Strings.domainNamePointerToHostName(DNS.getDefaultHost("default", "default"));
      int port = 0;
      // Creation of an ISA will force a resolve.
      InetSocketAddress initialIsa = new InetSocketAddress(hostname, port);
      if (initialIsa.getAddress() == null) {
        throw new IllegalArgumentException("Failed resolve of " + initialIsa);
      }
      final List<BlockingServiceAndInterface> sai =
        new ArrayList<BlockingServiceAndInterface>(1);
      BlockingService service =
        AuthenticationProtos.AuthenticationService.newReflectiveBlockingService(this);
      sai.add(new BlockingServiceAndInterface(service,
        AuthenticationProtos.AuthenticationService.BlockingInterface.class));
      this.rpcServer =
        new RpcServer(this, "tokenServer", sai, initialIsa, conf, new FifoRpcScheduler(conf, 1));
      this.isa = this.rpcServer.getListenerAddress();
      this.sleeper = new Sleeper(1000, this);
    }

    @Override
    public Configuration getConfiguration() {
      return conf;
    }

    @Override
    public CatalogTracker getCatalogTracker() {
      return null;
    }

    @Override
    public ZooKeeperWatcher getZooKeeper() {
      return zookeeper;
    }

    @Override
    public boolean isAborted() {
      return aborted;
    }

    @Override
    public ServerName getServerName() {
      return ServerName.valueOf(isa.getHostName(), isa.getPort(), startcode);
    }

    @Override
    public void abort(String reason, Throwable error) {
      LOG.fatal("Aborting on: "+reason, error);
      this.aborted = true;
      this.stopped = true;
      sleeper.skipSleepCycle();
    }

    private void initialize() throws IOException {
      // ZK configuration must _not_ have hbase.security.authentication or it will require SASL auth
      Configuration zkConf = new Configuration(conf);
      zkConf.set(User.HBASE_SECURITY_CONF_KEY, "simple");
      this.zookeeper = new ZooKeeperWatcher(zkConf, TokenServer.class.getSimpleName(),
          this, true);
      this.rpcServer.start();

      // mock RegionServerServices to provide to coprocessor environment
      final RegionServerServices mockServices = TEST_UTIL.createMockRegionServerService(rpcServer);

      // mock up coprocessor environment
      super.start(new RegionCoprocessorEnvironment() {
        @Override
        public HRegion getRegion() { return null; }

        @Override
        public RegionServerServices getRegionServerServices() {
          return mockServices;
        }

        @Override
        public ConcurrentMap<String, Object> getSharedData() { return null; }

        @Override
        public int getVersion() { return 0; }

        @Override
        public String getHBaseVersion() { return null; }

        @Override
        public Coprocessor getInstance() { return null; }

        @Override
        public int getPriority() { return 0; }

        @Override
        public int getLoadSequence() { return 0; }

        @Override
        public Configuration getConfiguration() { return conf; }

        @Override
        public HTableInterface getTable(TableName tableName) throws IOException
          { return null; }

        @Override
        public HTableInterface getTable(TableName tableName, ExecutorService service)
            throws IOException {
          return null;
        }

        @Override
        public ClassLoader getClassLoader() {
          return Thread.currentThread().getContextClassLoader();
        }
      });

      started = true;
    }

    public void run() {
      try {
        initialize();
        while (!stopped) {
          this.sleeper.sleep();
        }
      } catch (Exception e) {
        abort(e.getMessage(), e);
      }
      this.rpcServer.stop();
    }

    public boolean isStarted() {
      return started;
    }

    @Override
    public void stop(String reason) {
      LOG.info("Stopping due to: "+reason);
      this.stopped = true;
      sleeper.skipSleepCycle();
    }

    @Override
    public boolean isStopped() {
      return stopped;
    }

    public InetSocketAddress getAddress() {
      return isa;
    }

    public SecretManager<? extends TokenIdentifier> getSecretManager() {
      return ((RpcServer)rpcServer).getSecretManager();
    }

    @Override
    public AuthenticationProtos.GetAuthenticationTokenResponse getAuthenticationToken(
        RpcController controller, AuthenticationProtos.GetAuthenticationTokenRequest request)
      throws ServiceException {
      LOG.debug("Authentication token request from "+RequestContext.getRequestUserName());
      // ignore passed in controller -- it's always null
      ServerRpcController serverController = new ServerRpcController();
      BlockingRpcCallback<AuthenticationProtos.GetAuthenticationTokenResponse> callback =
          new BlockingRpcCallback<AuthenticationProtos.GetAuthenticationTokenResponse>();
      getAuthenticationToken(serverController, request, callback);
      try {
        serverController.checkFailed();
        return callback.get();
      } catch (IOException ioe) {
        throw new ServiceException(ioe);
      }
    }

    @Override
    public AuthenticationProtos.WhoAmIResponse whoAmI(
        RpcController controller, AuthenticationProtos.WhoAmIRequest request)
      throws ServiceException {
      LOG.debug("whoAmI() request from "+RequestContext.getRequestUserName());
      // ignore passed in controller -- it's always null
      ServerRpcController serverController = new ServerRpcController();
      BlockingRpcCallback<AuthenticationProtos.WhoAmIResponse> callback =
          new BlockingRpcCallback<AuthenticationProtos.WhoAmIResponse>();
      whoAmI(serverController, request, callback);
      try {
        serverController.checkFailed();
        return callback.get();
      } catch (IOException ioe) {
        throw new ServiceException(ioe);
      }
    }
  }


  private static HBaseTestingUtility TEST_UTIL;
  private static TokenServer server;
  private static Thread serverThread;
  private static AuthenticationTokenSecretManager secretManager;
  private static ClusterId clusterId = new ClusterId();

  @BeforeClass
  public static void setupBeforeClass() throws Exception {
    TEST_UTIL = new HBaseTestingUtility();
    TEST_UTIL.startMiniZKCluster();
    // register token type for protocol
    SecurityInfo.addInfo(AuthenticationProtos.AuthenticationService.getDescriptor().getName(),
      new SecurityInfo("hbase.test.kerberos.principal",
        AuthenticationProtos.TokenIdentifier.Kind.HBASE_AUTH_TOKEN));
    // security settings only added after startup so that ZK does not require SASL
    Configuration conf = TEST_UTIL.getConfiguration();
    conf.set("hadoop.security.authentication", "kerberos");
    conf.set("hbase.security.authentication", "kerberos");
    conf.setBoolean(HADOOP_SECURITY_AUTHORIZATION, true);
    server = new TokenServer(conf);
    serverThread = new Thread(server);
    Threads.setDaemonThreadRunning(serverThread, "TokenServer:"+server.getServerName().toString());
    // wait for startup
    while (!server.isStarted() && !server.isStopped()) {
      Thread.sleep(10);
    }
    server.rpcServer.refreshAuthManager(new PolicyProvider() {
      @Override
      public Service[] getServices() {
        return new Service [] {
          new Service("security.client.protocol.acl",
            AuthenticationProtos.AuthenticationService.BlockingInterface.class)};
      }
    });
    ZKClusterId.setClusterId(server.getZooKeeper(), clusterId);
    secretManager = (AuthenticationTokenSecretManager)server.getSecretManager();
    while(secretManager.getCurrentKey() == null) {
      Thread.sleep(1);
    }
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    server.stop("Test complete");
    Threads.shutdown(serverThread);
    TEST_UTIL.shutdownMiniZKCluster();
  }

  @Test
  public void testTokenCreation() throws Exception {
    Token<AuthenticationTokenIdentifier> token =
        secretManager.generateToken("testuser");

    AuthenticationTokenIdentifier ident = new AuthenticationTokenIdentifier();
    Writables.getWritable(token.getIdentifier(), ident);
    assertEquals("Token username should match", "testuser",
        ident.getUsername());
    byte[] passwd = secretManager.retrievePassword(ident);
    assertTrue("Token password and password from secret manager should match",
        Bytes.equals(token.getPassword(), passwd));
  }

  @Test
  public void testTokenAuthentication() throws Exception {
    UserGroupInformation testuser =
        UserGroupInformation.createUserForTesting("testuser", new String[]{"testgroup"});

    testuser.setAuthenticationMethod(
        UserGroupInformation.AuthenticationMethod.TOKEN);
    final Configuration conf = TEST_UTIL.getConfiguration();
    UserGroupInformation.setConfiguration(conf);
    Token<AuthenticationTokenIdentifier> token =
        secretManager.generateToken("testuser");
    LOG.debug("Got token: " + token.toString());
    testuser.addToken(token);

    // verify the server authenticates us as this token user
    testuser.doAs(new PrivilegedExceptionAction<Object>() {
      public Object run() throws Exception {
        Configuration c = server.getConfiguration();
        RpcClient rpcClient = new RpcClient(c, clusterId.toString());
        ServerName sn =
            ServerName.valueOf(server.getAddress().getHostName(), server.getAddress().getPort(),
                System.currentTimeMillis());
        try {
          BlockingRpcChannel channel = rpcClient.createBlockingRpcChannel(sn,
            User.getCurrent(), HConstants.DEFAULT_HBASE_RPC_TIMEOUT);
          AuthenticationProtos.AuthenticationService.BlockingInterface stub =
            AuthenticationProtos.AuthenticationService.newBlockingStub(channel);
          AuthenticationProtos.WhoAmIResponse response =
            stub.whoAmI(null, AuthenticationProtos.WhoAmIRequest.getDefaultInstance());
          String myname = response.getUsername();
          assertEquals("testuser", myname);
          String authMethod = response.getAuthMethod();
          assertEquals("TOKEN", authMethod);
        } finally {
          rpcClient.stop();
        }
        return null;
      }
    });
  }
}
