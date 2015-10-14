/**
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
package org.apache.hadoop.hbase.ipc;

import org.apache.hadoop.hbase.SmallTests;
import org.apache.hadoop.hbase.security.UserProvider;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;

@Category(SmallTests.class)
public class TestCallRunner {
  /**
   * Does nothing but exercise a {@link CallRunner} outside of {@link RpcServer} context.
   */
  @Test
  public void testSimpleCall() {
    RpcServerInterface mockRpcServer = Mockito.mock(RpcServerInterface.class);
    Mockito.when(mockRpcServer.isStarted()).thenReturn(true);
    RpcServer.Call mockCall = Mockito.mock(RpcServer.Call.class);
    mockCall.connection = Mockito.mock(RpcServer.Connection.class);
    CallRunner cr = new CallRunner(mockRpcServer, mockCall, new UserProvider());
    cr.run();
  }
}