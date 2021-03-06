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
package org.apache.reef.runtime.common.driver.evaluator;

import org.apache.reef.annotations.audience.DriverSide;
import org.apache.reef.annotations.audience.Private;
import org.apache.reef.driver.evaluator.FailedEvaluator;
import org.apache.reef.driver.parameters.EvaluatorConfigurationProviders;
import org.apache.reef.driver.restart.DriverRestartManager;
import org.apache.reef.driver.restart.EvaluatorRestartState;
import org.apache.reef.tang.ConfigurationProvider;
import org.apache.reef.driver.context.ActiveContext;
import org.apache.reef.driver.context.FailedContext;
import org.apache.reef.driver.evaluator.AllocatedEvaluator;
import org.apache.reef.driver.evaluator.EvaluatorDescriptor;
import org.apache.reef.driver.task.FailedTask;
import org.apache.reef.exception.EvaluatorException;
import org.apache.reef.exception.EvaluatorKilledByResourceManagerException;
import org.apache.reef.io.naming.Identifiable;
import org.apache.reef.proto.EvaluatorRuntimeProtocol;
import org.apache.reef.proto.ReefServiceProtos;
import org.apache.reef.driver.evaluator.EvaluatorProcess;
import org.apache.reef.runtime.common.driver.api.ResourceLaunchEvent;
import org.apache.reef.runtime.common.driver.api.ResourceReleaseEventImpl;
import org.apache.reef.runtime.common.driver.api.ResourceLaunchHandler;
import org.apache.reef.runtime.common.driver.api.ResourceReleaseHandler;
import org.apache.reef.runtime.common.driver.context.ContextControlHandler;
import org.apache.reef.runtime.common.driver.context.ContextRepresenters;
import org.apache.reef.runtime.common.driver.idle.EventHandlerIdlenessSource;
import org.apache.reef.runtime.common.driver.resourcemanager.ResourceStatusEvent;
import org.apache.reef.runtime.common.driver.task.TaskRepresenter;
import org.apache.reef.runtime.common.utils.ExceptionCodec;
import org.apache.reef.runtime.common.utils.RemoteManager;
import org.apache.reef.tang.annotations.Name;
import org.apache.reef.tang.annotations.NamedParameter;
import org.apache.reef.tang.annotations.Parameter;
import org.apache.reef.tang.formats.ConfigurationSerializer;
import org.apache.reef.util.Optional;
import org.apache.reef.util.logging.LoggingScopeFactory;
import org.apache.reef.wake.EventHandler;
import org.apache.reef.wake.remote.RemoteMessage;
import org.apache.reef.wake.time.Clock;
import org.apache.reef.wake.time.event.Alarm;

import javax.inject.Inject;
import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages a single Evaluator instance including all lifecycle instances:
 * (AllocatedEvaluator, CompletedEvaluator, FailedEvaluator).
 * <p/>
 * A (periodic) heartbeat channel is established EvaluatorRuntime -> EvaluatorManager.
 * The EvaluatorRuntime will (periodically) send (status) messages to the EvaluatorManager using this
 * heartbeat channel.
 * <p/>
 * A (push-based) EventHandler channel is established EvaluatorManager -> EvaluatorRuntime.
 * The EvaluatorManager uses this to forward Driver messages, launch Tasks, and initiate
 * control information (e.g., shutdown, suspend).
 */
@Private
@DriverSide
public final class EvaluatorManager implements Identifiable, AutoCloseable {

  private static final Logger LOG = Logger.getLogger(EvaluatorManager.class.getName());

  private final EvaluatorHeartBeatSanityChecker sanityChecker = new EvaluatorHeartBeatSanityChecker();
  private final Clock clock;
  private final ResourceReleaseHandler resourceReleaseHandler;
  private final ResourceLaunchHandler resourceLaunchHandler;
  private final String evaluatorId;
  private final EvaluatorDescriptorImpl evaluatorDescriptor;
  private final ContextRepresenters contextRepresenters;
  private final EvaluatorMessageDispatcher messageDispatcher;
  private final EvaluatorControlHandler evaluatorControlHandler;
  private final ContextControlHandler contextControlHandler;
  private final EvaluatorStatusManager stateManager;
  private final ExceptionCodec exceptionCodec;
  private final EventHandlerIdlenessSource idlenessSource;
  private final RemoteManager remoteManager;
  private final ConfigurationSerializer configurationSerializer;
  private final LoggingScopeFactory loggingScopeFactory;
  private final Set<ConfigurationProvider> evaluatorConfigurationProviders;
  private final DriverRestartManager driverRestartManager;

  // Mutable fields
  private Optional<TaskRepresenter> task = Optional.empty();
  private boolean isResourceReleased = false;
  private boolean allocationFired = false;

  @Inject
  private EvaluatorManager(
      final Clock clock,
      final RemoteManager remoteManager,
      final ResourceReleaseHandler resourceReleaseHandler,
      final ResourceLaunchHandler resourceLaunchHandler,
      @Parameter(EvaluatorIdentifier.class) final String evaluatorId,
      @Parameter(EvaluatorDescriptorName.class) final EvaluatorDescriptorImpl evaluatorDescriptor,
      final ContextRepresenters contextRepresenters,
      final ConfigurationSerializer configurationSerializer,
      final EvaluatorMessageDispatcher messageDispatcher,
      final EvaluatorControlHandler evaluatorControlHandler,
      final ContextControlHandler contextControlHandler,
      final EvaluatorStatusManager stateManager,
      final ExceptionCodec exceptionCodec,
      final EventHandlerIdlenessSource idlenessSource,
      final LoggingScopeFactory loggingScopeFactory,
      @Parameter(EvaluatorConfigurationProviders.class)
      final Set<ConfigurationProvider> evaluatorConfigurationProviders,
      final DriverRestartManager driverRestartManager) {
    this.contextRepresenters = contextRepresenters;
    this.idlenessSource = idlenessSource;
    LOG.log(Level.FINEST, "Instantiating 'EvaluatorManager' for evaluator: {0}", evaluatorId);
    this.clock = clock;
    this.resourceReleaseHandler = resourceReleaseHandler;
    this.resourceLaunchHandler = resourceLaunchHandler;
    this.evaluatorId = evaluatorId;
    this.evaluatorDescriptor = evaluatorDescriptor;

    this.messageDispatcher = messageDispatcher;
    this.evaluatorControlHandler = evaluatorControlHandler;
    this.contextControlHandler = contextControlHandler;
    this.stateManager = stateManager;
    this.exceptionCodec = exceptionCodec;

    this.remoteManager = remoteManager;
    this.configurationSerializer = configurationSerializer;
    this.loggingScopeFactory = loggingScopeFactory;
    this.evaluatorConfigurationProviders = evaluatorConfigurationProviders;
    this.driverRestartManager = driverRestartManager;

    LOG.log(Level.FINEST, "Instantiated 'EvaluatorManager' for evaluator: [{0}]", this.getId());
  }

  /**
   * Get the id of current job/application.
   */
  public static String getJobIdentifier() {
    // TODO: currently we obtain the job id directly by parsing execution (container) directory path
    // #845 is open to get the id from RM properly
    for (File directory = new File(System.getProperty("user.dir"));
         directory != null; directory = directory.getParentFile()) {
      final String currentDirectoryName = directory.getName();
      if (currentDirectoryName.toLowerCase().contains("application_")) {
        return currentDirectoryName;
      }
    }
    // cannot find a directory that contains application_, presumably we are on local runtime
    // again, this is a hack for now, we need #845 as a proper solution
    return "REEF_LOCAL_RUNTIME";
  }

  /**
   * Fires the EvaluatorAllocatedEvent to the handlers. Can only be done once.
   */
  public synchronized void fireEvaluatorAllocatedEvent() {
    if (!allocationFired && stateManager.isAllocated()) {
      final AllocatedEvaluator allocatedEvaluator =
          new AllocatedEvaluatorImpl(this,
              remoteManager.getMyIdentifier(),
              configurationSerializer,
              getJobIdentifier(),
              loggingScopeFactory,
              evaluatorConfigurationProviders);
      LOG.log(Level.FINEST, "Firing AllocatedEvaluator event for Evaluator with ID [{0}]", evaluatorId);
      messageDispatcher.onEvaluatorAllocated(allocatedEvaluator);
      allocationFired = true;
    } else {
      LOG.log(Level.WARNING, "Evaluator allocated event fired more than once.");
    }
  }

  private static boolean isDoneOrFailedOrKilled(final ResourceStatusEvent resourceStatusEvent) {
    return resourceStatusEvent.getState() == ReefServiceProtos.State.DONE ||
        resourceStatusEvent.getState() == ReefServiceProtos.State.FAILED ||
        resourceStatusEvent.getState() == ReefServiceProtos.State.KILLED;
  }

  @Override
  public String getId() {
    return this.evaluatorId;
  }

  public void setProcess(final EvaluatorProcess process) {
    this.evaluatorDescriptor.setProcess(process);
  }

  public EvaluatorDescriptor getEvaluatorDescriptor() {
    return this.evaluatorDescriptor;
  }

  @Override
  public void close() {
    synchronized (this.evaluatorDescriptor) {
      if (this.stateManager.isAllocatedOrSubmittedOrRunning()) {
        LOG.log(Level.WARNING, "Dirty shutdown of running evaluator id[{0}]", getId());
        try {
          if (this.stateManager.isRunning()){
            // Killing the evaluator means that it doesn't need to send a confirmation; it just dies.
            final EvaluatorRuntimeProtocol.EvaluatorControlProto evaluatorControlProto =
                EvaluatorRuntimeProtocol.EvaluatorControlProto.newBuilder()
                    .setTimestamp(System.currentTimeMillis())
                    .setIdentifier(getId())
                    .setKillEvaluator(EvaluatorRuntimeProtocol.KillEvaluatorProto.newBuilder().build())
                    .build();
            sendEvaluatorControlMessage(evaluatorControlProto);
          }
        } finally {
          this.stateManager.setKilled();
        }
      }


      if (!this.isResourceReleased) {
        this.isResourceReleased = true;
        try {
        /* We need to wait awhile before returning the container to the RM in order to
         * give the EvaluatorRuntime (and Launcher) time to cleanly exit. */
          this.clock.scheduleAlarm(100, new EventHandler<Alarm>() {
            @Override
            public void onNext(final Alarm alarm) {
              EvaluatorManager.this.resourceReleaseHandler.onNext(
                  ResourceReleaseEventImpl.newBuilder()
                      .setIdentifier(EvaluatorManager.this.evaluatorId).build()
              );
            }
          });
        } catch (final IllegalStateException e) {
          LOG.log(Level.WARNING, "Force resource release because the client closed the clock.", e);
          EvaluatorManager.this.resourceReleaseHandler.onNext(
              ResourceReleaseEventImpl.newBuilder()
                  .setIdentifier(EvaluatorManager.this.evaluatorId).build()
          );
        }
      }
    }
    this.idlenessSource.check();
  }

  /**
   * Return true if the state is DONE, FAILED, or KILLED,
   * <em>and</em> there are no messages queued or in processing.
   */
  public boolean isClosed() {
    return this.messageDispatcher.isEmpty() &&
        (this.stateManager.isDoneOrFailedOrKilled());
  }

  /**
   * EvaluatorException will trigger is FailedEvaluator and state transition to FAILED.
   *
   * @param exception on the EvaluatorRuntime
   */
  public void onEvaluatorException(final EvaluatorException exception) {
    synchronized (this.evaluatorDescriptor) {
      if (this.stateManager.isDoneOrFailedOrKilled()) {
        LOG.log(Level.FINE, "Ignoring an exception received for Evaluator {0} which is already in state {1}.",
            new Object[]{this.getId(), this.stateManager});
        return;
      }

      LOG.log(Level.WARNING, "Failed evaluator: " + getId(), exception);

      try {

        final List<FailedContext> failedContextList = this.contextRepresenters.getFailedContextsForEvaluatorFailure();

        final Optional<FailedTask> failedTaskOptional;
        if (this.task.isPresent()) {
          final String taskId = this.task.get().getId();
          final Optional<ActiveContext> evaluatorContext = Optional.empty();
          final Optional<byte[]> bytes = Optional.empty();
          final Optional<Throwable> taskException = Optional.<Throwable>of(new Exception("Evaluator crash"));
          final String message = "Evaluator crash";
          final Optional<String> description = Optional.empty();
          final FailedTask failedTask =
              new FailedTask(taskId, message, description, taskException, bytes, evaluatorContext);
          failedTaskOptional = Optional.of(failedTask);
        } else {
          failedTaskOptional = Optional.empty();
        }

        final FailedEvaluator failedEvaluator = new FailedEvaluatorImpl(exception, failedContextList,
            failedTaskOptional, this.evaluatorId);

        if (driverRestartManager.getEvaluatorRestartState(evaluatorId).isFailedOrExpired()) {
          this.messageDispatcher.onDriverRestartEvaluatorFailed(failedEvaluator);
        } else {
          this.messageDispatcher.onEvaluatorFailed(failedEvaluator);
        }

      } catch (final Exception e) {
        LOG.log(Level.SEVERE, "Exception while handling FailedEvaluator", e);
      } finally {
        this.stateManager.setFailed();
        close();
      }
    }
  }

  /**
   * Process an evaluator heartbeat message.
   */
  public void onEvaluatorHeartbeatMessage(
      final RemoteMessage<EvaluatorRuntimeProtocol.EvaluatorHeartbeatProto> evaluatorHeartbeatProtoRemoteMessage) {

    final EvaluatorRuntimeProtocol.EvaluatorHeartbeatProto evaluatorHeartbeatProto =
        evaluatorHeartbeatProtoRemoteMessage.getMessage();
    LOG.log(Level.FINEST, "Evaluator heartbeat: {0}", evaluatorHeartbeatProto);

    synchronized (this.evaluatorDescriptor) {
      if (this.stateManager.isDoneOrFailedOrKilled()) {
        LOG.log(Level.FINE, "Ignoring an heartbeat received for Evaluator {0} which is already in state {1}.",
            new Object[]{this.getId(), this.stateManager});
        return;
      }

      this.sanityChecker.check(evaluatorId, evaluatorHeartbeatProto.getTimestamp());
      final String evaluatorRID = evaluatorHeartbeatProtoRemoteMessage.getIdentifier().toString();

      final EvaluatorRestartState evaluatorRestartState = driverRestartManager.getEvaluatorRestartState(evaluatorId);

      /*
       * First message from a running evaluator. The evaluator can be a new evaluator or be a previous evaluator
       * from a separate application attempt. In the case of a previous evaluator, if the restart period has not
       * yet expired, we should register it and trigger context active and task events. If the restart period has
       * expired, we should return immediately after setting its remote ID in order to close it.
       */
      if (this.stateManager.isSubmitted() ||
          evaluatorRestartState == EvaluatorRestartState.REPORTED ||
          evaluatorRestartState == EvaluatorRestartState.EXPIRED) {

        this.evaluatorControlHandler.setRemoteID(evaluatorRID);

        if (evaluatorRestartState == EvaluatorRestartState.EXPIRED) {
          // Don't do anything if evaluator has expired. Close it immediately upon exit of this method.
          return;
        }

        this.stateManager.setRunning();
        LOG.log(Level.FINEST, "Evaluator {0} is running", this.evaluatorId);

        if (evaluatorRestartState == EvaluatorRestartState.REPORTED) {
          driverRestartManager.setEvaluatorReregistered(evaluatorId);
        }
      }

      // Process the Evaluator status message
      if (evaluatorHeartbeatProto.hasEvaluatorStatus()) {
        this.onEvaluatorStatusMessage(evaluatorHeartbeatProto.getEvaluatorStatus());
      }

      // Process the Context status message(s)
      final boolean informClientOfNewContexts = !evaluatorHeartbeatProto.hasTaskStatus();
      this.contextRepresenters.onContextStatusMessages(evaluatorHeartbeatProto.getContextStatusList(),
          informClientOfNewContexts);

      // Process the Task status message
      if (evaluatorHeartbeatProto.hasTaskStatus()) {
        this.onTaskStatusMessage(evaluatorHeartbeatProto.getTaskStatus());
      }
      LOG.log(Level.FINE, "DONE with evaluator heartbeat from Evaluator {0}", this.getId());
    }
  }

  /**
   * Process a evaluator status message.
   *
   * @param message
   */
  private synchronized void onEvaluatorStatusMessage(final ReefServiceProtos.EvaluatorStatusProto message) {

    switch (message.getState()) {
    case DONE:
      this.onEvaluatorDone(message);
      break;
    case FAILED:
      this.onEvaluatorFailed(message);
      break;
    case INIT:
    case KILLED:
    case RUNNING:
    case SUSPEND:
      break;
    default:
      throw new RuntimeException("Unknown state: " + message.getState());
    }
  }

  /**
   * Process an evaluator message that indicates that the evaluator shut down cleanly.
   *
   * @param message
   */
  private synchronized void onEvaluatorDone(final ReefServiceProtos.EvaluatorStatusProto message) {
    assert (message.getState() == ReefServiceProtos.State.DONE);
    LOG.log(Level.FINEST, "Evaluator {0} done.", getId());
    this.stateManager.setDone();
    this.messageDispatcher.onEvaluatorCompleted(new CompletedEvaluatorImpl(this.evaluatorId));
    close();
  }

  /**
   * Process an evaluator message that indicates a crash.
   *
   * @param evaluatorStatusProto
   */
  private synchronized void onEvaluatorFailed(final ReefServiceProtos.EvaluatorStatusProto evaluatorStatusProto) {
    assert (evaluatorStatusProto.getState() == ReefServiceProtos.State.FAILED);
    final EvaluatorException evaluatorException;
    if (evaluatorStatusProto.hasError()) {
      final Optional<Throwable> exception =
          this.exceptionCodec.fromBytes(evaluatorStatusProto.getError().toByteArray());
      if (exception.isPresent()) {
        evaluatorException = new EvaluatorException(getId(), exception.get());
      } else {
        evaluatorException = new EvaluatorException(getId(),
            new Exception("Exception sent, but can't be deserialized"));
      }
    } else {
      evaluatorException = new EvaluatorException(getId(), new Exception("No exception sent"));
    }
    onEvaluatorException(evaluatorException);
  }

  public void onResourceLaunch(final ResourceLaunchEvent resourceLaunchEvent) {
    synchronized (this.evaluatorDescriptor) {
      if (this.stateManager.isAllocated()) {
        this.stateManager.setSubmitted();
        this.resourceLaunchHandler.onNext(resourceLaunchEvent);
      } else {
        throw new RuntimeException("Evaluator manager expected " + EvaluatorState.ALLOCATED +
            " state but instead is in state " + this.stateManager);
      }
    }
  }

  /**
   * Packages the ContextControlProto in an EvaluatorControlProto and forward it to the EvaluatorRuntime.
   *
   * @param contextControlProto message contains context control info.
   */
  public void sendContextControlMessage(final EvaluatorRuntimeProtocol.ContextControlProto contextControlProto) {
    synchronized (this.evaluatorDescriptor) {
      LOG.log(Level.FINEST, "Context control message to {0}", this.evaluatorId);
      this.contextControlHandler.send(contextControlProto);
    }
  }

  /**
   * Forward the EvaluatorControlProto to the EvaluatorRuntime.
   *
   * @param evaluatorControlProto message contains evaluator control information.
   */
  void sendEvaluatorControlMessage(final EvaluatorRuntimeProtocol.EvaluatorControlProto evaluatorControlProto) {
    synchronized (this.evaluatorDescriptor) {
      this.evaluatorControlHandler.send(evaluatorControlProto);
    }
  }

  /**
   * Handle task status messages.
   *
   * @param taskStatusProto message contains the current task status.
   */
  private void onTaskStatusMessage(final ReefServiceProtos.TaskStatusProto taskStatusProto) {

    if (!(this.task.isPresent() && this.task.get().getId().equals(taskStatusProto.getTaskId()))) {
      if (taskStatusProto.getState() == ReefServiceProtos.State.INIT ||
          taskStatusProto.getState() == ReefServiceProtos.State.FAILED ||
          taskStatusProto.getState() == ReefServiceProtos.State.RUNNING ||
          driverRestartManager.getEvaluatorRestartState(evaluatorId) == EvaluatorRestartState.REREGISTERED) {

        // [REEF-308] exposes a bug where the .NET evaluator does not send its states in the right order
        // [REEF-289] is a related item which may fix the issue
        if (taskStatusProto.getState() == ReefServiceProtos.State.RUNNING) {
          LOG.log(Level.WARNING,
                  "Received a message of state " + ReefServiceProtos.State.RUNNING +
                  " for Task " + taskStatusProto.getTaskId() +
                  " before receiving its " + ReefServiceProtos.State.INIT + " state");
        }

        // FAILED is a legal first state of a Task as it could have failed during construction.
        this.task = Optional.of(
            new TaskRepresenter(taskStatusProto.getTaskId(),
                this.contextRepresenters.getContext(taskStatusProto.getContextId()),
                this.messageDispatcher,
                this,
                this.exceptionCodec,
                this.driverRestartManager));
      } else {
        throw new RuntimeException("Received a message of state " + taskStatusProto.getState() +
            ", not INIT, RUNNING, or FAILED for Task " + taskStatusProto.getTaskId() +
            " which we haven't heard from before.");
      }
    }
    this.task.get().onTaskStatusMessage(taskStatusProto);

    if (this.task.get().isNotRunning()) {
      LOG.log(Level.FINEST, "Task no longer running. De-registering it.");
      this.task = Optional.empty();
    }
  }

  /**
   * Resource status information from the (actual) resource manager.
   */
  public void onResourceStatusMessage(final ResourceStatusEvent resourceStatusEvent) {
    synchronized (this.evaluatorDescriptor) {
      LOG.log(Level.FINEST, "Resource manager state update: {0}", resourceStatusEvent.getState());
      if (this.stateManager.isDoneOrFailedOrKilled()) {
        LOG.log(Level.FINE, "Ignoring resource status update for Evaluator {0} which is already in state {1}.",
            new Object[]{this.getId(), this.stateManager});
      } else if (isDoneOrFailedOrKilled(resourceStatusEvent) && this.stateManager.isAllocatedOrSubmittedOrRunning()) {
        // something is wrong. The resource manager reports that the Evaluator is done or failed, but the Driver assumes
        // it to be alive.
        final StringBuilder messageBuilder = new StringBuilder("Evaluator [")
            .append(this.evaluatorId)
            .append("] is assumed to be in state [")
            .append(this.stateManager.toString())
            .append("]. But the resource manager reports it to be in state [")
            .append(resourceStatusEvent.getState())
            .append("].");

        if (this.stateManager.isSubmitted()) {
          messageBuilder
              .append(" This most likely means that the Evaluator suffered a failure before establishing " +
                  "a communications link to the driver.");
        } else if (this.stateManager.isAllocated()) {
          messageBuilder.append(" This most likely means that the Evaluator suffered a failure before being used.");
        } else if (this.stateManager.isRunning()) {
          messageBuilder.append(" This means that the Evaluator failed but wasn't able to send an error message " +
              "back to the driver.");
        }
        if (this.task.isPresent()) {
          messageBuilder.append(" Task [")
              .append(this.task.get().getId())
              .append("] was running when the Evaluator crashed.");
        }

        if (resourceStatusEvent.getState() == ReefServiceProtos.State.KILLED) {
          this.onEvaluatorException(new EvaluatorKilledByResourceManagerException(this.evaluatorId,
              messageBuilder.toString()));
        } else {
          this.onEvaluatorException(new EvaluatorException(this.evaluatorId, messageBuilder.toString()));
        }
      }
    }
  }

  @Override
  public String toString() {
    return "EvaluatorManager:"
        + " id=" + this.evaluatorId
        + " state=" + this.stateManager
        + " task=" + this.task;
  }

  // Dynamic Parameters
  @NamedParameter(doc = "The Evaluator Identifier.")
  public static final class EvaluatorIdentifier implements Name<String> {
  }

  @NamedParameter(doc = "The Evaluator Host.")
  public static final class EvaluatorDescriptorName implements Name<EvaluatorDescriptorImpl> {
  }
}
