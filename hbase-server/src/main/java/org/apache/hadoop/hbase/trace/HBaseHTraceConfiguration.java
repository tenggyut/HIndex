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

package org.apache.hadoop.hbase.trace;

import org.apache.hadoop.conf.Configuration;
import org.cloudera.htrace.HTraceConfiguration;

public class HBaseHTraceConfiguration extends HTraceConfiguration {

  public static final String KEY_PREFIX = "hbase.";
  private Configuration conf;

  public HBaseHTraceConfiguration(Configuration conf) {
    this.conf = conf;
  }

  @Override
  public String get(String key) {
    return conf.get(KEY_PREFIX +key);
  }

  @Override
  public String get(String key, String defaultValue) {
    return conf.get(KEY_PREFIX + key,defaultValue);

  }

  @Override
  public boolean getBoolean(String key, boolean defaultValue) {
    return conf.getBoolean(KEY_PREFIX + key, defaultValue);
  }
}
