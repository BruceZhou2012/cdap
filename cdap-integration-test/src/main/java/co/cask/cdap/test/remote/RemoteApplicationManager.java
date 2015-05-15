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

package co.cask.cdap.test.remote;

import co.cask.cdap.api.metrics.RuntimeMetrics;
import co.cask.cdap.api.schedule.ScheduleSpecification;
import co.cask.cdap.client.ApplicationClient;
import co.cask.cdap.client.MetricsClient;
import co.cask.cdap.client.ProgramClient;
import co.cask.cdap.client.ScheduleClient;
import co.cask.cdap.client.config.ClientConfig;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.ProgramRecord;
import co.cask.cdap.proto.ProgramType;
import co.cask.cdap.proto.RunRecord;
import co.cask.cdap.test.AbstractApplicationManager;
import co.cask.cdap.test.AbstractWorkerManager;
import co.cask.cdap.test.DataSetManager;
import co.cask.cdap.test.FlowManager;
import co.cask.cdap.test.MapReduceManager;
import co.cask.cdap.test.ScheduleManager;
import co.cask.cdap.test.ServiceManager;
import co.cask.cdap.test.SparkManager;
import co.cask.cdap.test.StreamWriter;
import co.cask.cdap.test.WorkerManager;
import co.cask.cdap.test.WorkflowManager;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 */
public class RemoteApplicationManager extends AbstractApplicationManager {

  protected final Id.Application application;

  private final ClientConfig clientConfig;
  private final MetricsClient metricsClient;

  public RemoteApplicationManager(Id.Application application, ClientConfig clientConfig) {
    this.application = application;
    ClientConfig namespacedClientConfig = new ClientConfig.Builder(clientConfig).build();
    namespacedClientConfig.setNamespace(application.getNamespace());

    this.clientConfig = namespacedClientConfig;
    this.metricsClient = new MetricsClient(namespacedClientConfig);
  }

  private ApplicationClient getApplicationClient() {
    return new ApplicationClient(clientConfig);
  }

  private ProgramClient getProgramClient() {
    return new ProgramClient(clientConfig);
  }

  private ScheduleClient getScheduleClient() {
    return new ScheduleClient(clientConfig);
  }

  @Override
  public FlowManager startFlow(final String flowName) {
    return startFlow(flowName, ImmutableMap.<String, String>of());
  }

  @Override
  public FlowManager startFlow(final String flowName, Map<String, String> arguments) {
    final Id.Program flowId = startProgram(flowName, arguments, ProgramType.FLOW);
    return new FlowManager() {
      @Override
      public void setFlowletInstances(String flowletName, int instances) {
        Preconditions.checkArgument(instances > 0, "Instance counter should be > 0.");
        try {
          getProgramClient().setFlowletInstances(application.getId(), flowName, flowletName, instances);
        } catch (Exception e) {
          throw Throwables.propagate(e);
        }
      }

      @Override
      public RuntimeMetrics getFlowletMetrics(String flowletId) {
        return metricsClient.getFlowletMetrics(
          Id.Program.from(application, ProgramType.FLOW, flowId.getId()), flowletId);
      }

      @Override
      public void stop() {
        stopProgram(flowId);
      }

      @Override
      public boolean isRunning() {
        try {
          String status = getProgramClient().getStatus(application.getId(), ProgramType.FLOW, flowName);
          return "RUNNING".equals(status);
        } catch (Exception e) {
          throw Throwables.propagate(e);
        }
      }
    };
  }

  @Override
  public MapReduceManager startMapReduce(final String programName) {
    return startMapReduce(programName, ImmutableMap.<String, String>of());
  }

  @Override
  public MapReduceManager startMapReduce(final String programName, Map<String, String> arguments) {
    return getMapReduceManager(startProgram(programName, arguments, ProgramType.MAPREDUCE));
  }

  private MapReduceManager getMapReduceManager(final Id.Program programId) {
    try {
      return new MapReduceManager() {
        @Override
        public void stop() {
          stopProgram(programId);
        }

        @Override
        public void waitForFinish(long timeout, TimeUnit timeoutUnit) throws TimeoutException, InterruptedException {
          programWaitForFinish(timeout, timeoutUnit, programId);
        }
      };
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public SparkManager startSpark(String programName) {
    return startSpark(programName, ImmutableMap.<String, String>of());
  }

  @Override
  public SparkManager startSpark(String programName, Map<String, String> arguments) {
    return getSparkManager(programName, arguments, ProgramType.SPARK);
  }

  private SparkManager getSparkManager(final String programName, Map<String, String> arguments,
                                       final ProgramType programType) {
    try {
      final Id.Program programId = startProgram(programName, arguments, programType);
      return new SparkManager() {
        @Override
        public void stop() {
          stopProgram(programId);
        }

        @Override
        public void waitForFinish(long timeout, TimeUnit timeoutUnit) throws TimeoutException, InterruptedException {
          programWaitForFinish(timeout, timeoutUnit, programId);
        }
      };
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  private Id.Program startProgram(String programName, Map<String, String> arguments, ProgramType programType) {
    ProgramClient programClient = getProgramClient();
    try {
      String status = programClient.getStatus(application.getId(), programType, programName);
      Preconditions.checkState("STOPPED".equals(status), programType + " program %s is already running", programName);
      programClient.start(application.getId(), programType, programName, arguments);
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
    return Id.Program.from(application, programType, programName);
  }

  @Override
  public WorkflowManager startWorkflow(final String workflowName, Map<String, String> arguments) {
    // currently we are using it for schedule, so not starting the workflow
    return new WorkflowManager() {
      @Override
      public List<ScheduleSpecification> getSchedules() {
        try {
          return getScheduleClient().list(application.getId(), workflowName);
        } catch (Exception e) {
          throw Throwables.propagate(e);
        }
      }

      @Override
      public List<RunRecord> getHistory() {
        try {
          return getProgramClient().getProgramRuns(application.getId(), ProgramType.WORKFLOW,
                                                   workflowName, "ALL", 0, Long.MAX_VALUE, Integer.MAX_VALUE);
        } catch (Exception e) {
          throw Throwables.propagate(e);
        }
      }

      public ScheduleManager getSchedule(final String schedName) {
        return new ScheduleManager() {
          @Override
          public void suspend() {
            try {
              getScheduleClient().suspend(application.getId(), schedName);
            } catch (Exception e) {
              throw Throwables.propagate(e);
            }
          }

          @Override
          public void resume() {
            try {
              getScheduleClient().resume(application.getId(), schedName);
            } catch (Exception e) {
              throw Throwables.propagate(e);
            }
          }

          @Override
          public String status(int expectedCode) {
            try {
              return getScheduleClient().getStatus(application.getId(), schedName);
            } catch (Exception e) {
              throw Throwables.propagate(e);
            }
          }
        };
      }

    };
  }

  @Override
  public ServiceManager startService(String serviceName) {
    return startService(serviceName, ImmutableMap.<String, String>of());
  }

  @Override
  public ServiceManager startService(final String serviceName, Map<String, String> arguments) {
    startProgram(serviceName, arguments, ProgramType.SERVICE);
    return new RemoteServiceManager(Id.Service.from(application, serviceName), clientConfig);
  }

  @Override
  public WorkerManager startWorker(final String workerName, Map<String, String> arguments) {
    final Id.Program workerId = Id.Program.from(application, ProgramType.WORKER, workerName);
    return new AbstractWorkerManager() {
      @Override
      public void setInstances(int instances) {
        Preconditions.checkArgument(instances > 0, "Instance count should be > 0.");
        try {
          getProgramClient().setWorkerInstances(application.getId(), workerName, instances);
        } catch (Exception e) {
          throw Throwables.propagate(e);
        }
      }

      @Override
      public void stop() {
        stopProgram(workerId);
      }

      @Override
      public boolean isRunning() {
        try {
          String status = getProgramClient().getStatus(application.getId(), ProgramType.WORKER, workerName);
          return "RUNNING".equals(status);
        } catch (Exception e) {
          throw Throwables.propagate(e);
        }
      }

      @Override
      public int getInstances() {
        try {
          return getProgramClient().getWorkerInstances(application.getId(), workerName);
        } catch (Exception e) {
          throw Throwables.propagate(e);
        }
      }
    };
  }

  @Override
  public WorkerManager startWorker(String workerName) {
    return startWorker(workerName, ImmutableMap.<String, String>of());
  }

  @Override
  @Deprecated
  public StreamWriter getStreamWriter(String streamName) {
    return new RemoteStreamWriter(new RemoteStreamManager(clientConfig,
                                                          Id.Stream.from(application.getNamespaceId(), streamName)));
  }

  @Override
  public <T> DataSetManager<T> getDataSet(String dataSetName) throws Exception {
    throw new UnsupportedOperationException();
  }

  @Override
  public void stopAll() {
    try {
      for (ProgramRecord programRecord : getApplicationClient().listPrograms(application.getId())) {
        // have to do a check, since mapreduce jobs could stop by themselves earlier, and appFabricServer.stop will
        // throw error when you stop something that is not running.
        Id.Program id = Id.Program.from(application, programRecord.getType(), programRecord.getName());
        if (isRunning(id)) {
          getProgramClient().stop(application.getId(), id.getType(), id.getId());
        }
      }
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  void stopProgram(Id.Program programId) {
    try {
      getProgramClient().stop(application.getId(), programId.getType(), programId.getId());
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  protected boolean isRunning(Id.Program programId) {
    try {
      String status = getProgramClient().getStatus(application.getId(), programId.getType(),
                                                   programId.getId());
      // comparing to hardcoded string is ugly, but this is how appFabricServer works now to support legacy UI
      return "STARTING".equals(status) || "RUNNING".equals(status);
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }
}