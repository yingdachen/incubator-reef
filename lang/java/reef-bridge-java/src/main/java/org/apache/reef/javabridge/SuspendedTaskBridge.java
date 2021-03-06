/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.reef.javabridge;

import org.apache.reef.driver.task.SuspendedTask;
import org.apache.reef.io.Message;
import org.apache.reef.io.naming.Identifiable;

/**
 * The Java-CLR bridge object for {@link org.apache.reef.driver.task.SuspendedTask}.
 */
public final class SuspendedTaskBridge extends NativeBridge implements Identifiable, Message {

  private final SuspendedTask jsuspendedTask;
  private final String taskId;
  private final ActiveContextBridge jactiveContext;

  public SuspendedTaskBridge(final SuspendedTask suspendedTask,
                             final ActiveContextBridgeFactory activeContextBridgeFactory) {
    jsuspendedTask = suspendedTask;
    taskId = suspendedTask.getId();
    jactiveContext = activeContextBridgeFactory.getActiveContextBridge(jsuspendedTask.getActiveContext());
  }

  public ActiveContextBridge getActiveContext() {
    return jactiveContext;
  }

  @Override
  public void close() {
  }

  @Override
  public String getId() {
    return taskId;
  }

  @Override
  public byte[] get() {
    return jsuspendedTask.get();
  }
}
