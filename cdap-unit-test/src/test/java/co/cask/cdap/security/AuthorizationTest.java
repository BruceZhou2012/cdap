/*
 * Copyright © 2016 Cask Data, Inc.
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

package co.cask.cdap.security;

import co.cask.cdap.AllProgramsApp;
import co.cask.cdap.ConfigTestApp;
import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.dataset.DatasetManagementException;
import co.cask.cdap.api.dataset.lib.KeyValueTable;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.namespace.NamespaceAdmin;
import co.cask.cdap.common.utils.Tasks;
import co.cask.cdap.internal.test.AppJarHelper;
import co.cask.cdap.proto.NamespaceMeta;
import co.cask.cdap.proto.artifact.AppRequest;
import co.cask.cdap.proto.artifact.ArtifactSummary;
import co.cask.cdap.proto.id.ApplicationId;
import co.cask.cdap.proto.id.ArtifactId;
import co.cask.cdap.proto.id.DatasetId;
import co.cask.cdap.proto.id.EntityId;
import co.cask.cdap.proto.id.Ids;
import co.cask.cdap.proto.id.InstanceId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.proto.security.Action;
import co.cask.cdap.proto.security.Principal;
import co.cask.cdap.proto.security.Privilege;
import co.cask.cdap.security.authorization.InMemoryAuthorizer;
import co.cask.cdap.security.spi.authentication.SecurityRequestContext;
import co.cask.cdap.security.spi.authorization.Authorizer;
import co.cask.cdap.security.spi.authorization.UnauthorizedException;
import co.cask.cdap.test.ApplicationManager;
import co.cask.cdap.test.ArtifactManager;
import co.cask.cdap.test.DataSetManager;
import co.cask.cdap.test.ServiceManager;
import co.cask.cdap.test.SlowTests;
import co.cask.cdap.test.TestBase;
import co.cask.cdap.test.TestConfiguration;
import co.cask.cdap.test.app.DummyApp;
import co.cask.cdap.test.artifacts.plugins.ToStringPlugin;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.apache.twill.filesystem.LocalLocationFactory;
import org.apache.twill.filesystem.Location;
import org.apache.twill.filesystem.LocationFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests with authorization enabled.
 */
public class AuthorizationTest extends TestBase {

  @ClassRule
  public static final TestConfiguration CONFIG = new TestConfiguration(
    Constants.Explore.EXPLORE_ENABLED, false,
    Constants.Security.Authorization.CACHE_ENABLED, false
  );

  private static final String OLD_USER = SecurityRequestContext.getUserId();
  private static final Principal ALICE = new Principal("alice", Principal.PrincipalType.USER);
  private static final Principal BOB = new Principal("bob", Principal.PrincipalType.USER);
  private static final NamespaceId AUTH_NAMESPACE = new NamespaceId("authorization");
  private static final NamespaceMeta AUTH_NAMESPACE_META =
    new NamespaceMeta.Builder().setName(AUTH_NAMESPACE.getNamespace()).build();

  private static InstanceId instance;

  /**
   * An {@link ExternalResource} that wraps a {@link TemporaryFolder} and {@link TestConfiguration} to execute them in
   * a chain.
   */
  private static final class AuthTestConf extends ExternalResource {
    private final TemporaryFolder tmpFolder = new TemporaryFolder();
    private TestConfiguration testConf;

    @Override
    public Statement apply(final Statement base, final Description description) {
      // Apply the TemporaryFolder on a Statement that creates a TestConfiguration and applies on base
      return tmpFolder.apply(new Statement() {
        @Override
        public void evaluate() throws Throwable {
          testConf = new TestConfiguration(getAuthConfigs(tmpFolder.newFolder()));
          testConf.apply(base, description).evaluate();
        }
      }, description);
    }

    private static String[] getAuthConfigs(File tmpDir) throws IOException {
      LocationFactory locationFactory = new LocalLocationFactory(tmpDir);
      Location authExtensionJar = AppJarHelper.createDeploymentJar(locationFactory, InMemoryAuthorizer.class);
      return new String[] {
        Constants.Security.ENABLED, "true",
        Constants.Security.Authorization.ENABLED, "true",
        Constants.Security.Authorization.EXTENSION_JAR_PATH, authExtensionJar.toURI().getPath()
      };
    }
  }

  @ClassRule
  public static final AuthTestConf AUTH_TEST_CONF = new AuthTestConf();

  @BeforeClass
  public static void setup() {
    instance = new InstanceId(getConfiguration().get(Constants.INSTANCE_NAME));
    SecurityRequestContext.setUserId(ALICE.getName());
  }

  @Before
  public void setupTest() throws Exception {
    Assert.assertEquals(ImmutableSet.of(), getAuthorizer().listPrivileges(ALICE));
  }

  @Test
  public void testNamespaces() throws Exception {
    NamespaceAdmin namespaceAdmin = getNamespaceAdmin();
    Authorizer authorizer = getAuthorizer();
    try {
      namespaceAdmin.create(AUTH_NAMESPACE_META);
      Assert.fail("Namespace create should have failed because alice is not authorized on " + instance);
    } catch (UnauthorizedException expected) {
      // expected
    }
    createAuthNamespace();
    // No authorization currently for listing and retrieving namespace
    namespaceAdmin.list();
    namespaceAdmin.get(AUTH_NAMESPACE.toId());
    // revoke privileges
    authorizer.revoke(AUTH_NAMESPACE);
    Assert.assertEquals(ImmutableSet.of(new Privilege(instance, Action.ADMIN)), authorizer.listPrivileges(ALICE));
    try {
      namespaceAdmin.deleteDatasets(AUTH_NAMESPACE.toId());
      Assert.fail("Namespace delete datasets should have failed because alice's privileges on the namespace have " +
                    "been revoked");
    } catch (UnauthorizedException expected) {
      // expected
    }
    // grant privileges again
    authorizer.grant(AUTH_NAMESPACE, ALICE, ImmutableSet.of(Action.ADMIN));
    Assert.assertEquals(
      ImmutableSet.of(new Privilege(instance, Action.ADMIN), new Privilege(AUTH_NAMESPACE, Action.ADMIN)),
      authorizer.listPrivileges(ALICE)
    );
    namespaceAdmin.deleteDatasets(AUTH_NAMESPACE.toId());
    // deleting datasets does not revoke privileges.
    Assert.assertEquals(
      ImmutableSet.of(new Privilege(instance, Action.ADMIN), new Privilege(AUTH_NAMESPACE, Action.ADMIN)),
      authorizer.listPrivileges(ALICE)
    );
    NamespaceMeta updated = new NamespaceMeta.Builder(AUTH_NAMESPACE_META).setDescription("new desc").build();
    namespaceAdmin.updateProperties(AUTH_NAMESPACE.toId(), updated);
  }

  @Test
  @Category(SlowTests.class)
  public void testApps() throws Exception {
    try {
      deployApplication(NamespaceId.DEFAULT.toId(), DummyApp.class);
      Assert.fail("App deployment should fail because alice does not have WRITE access on the default namespace");
    } catch (RuntimeException e) {
      Assert.assertTrue(e.getCause() instanceof UnauthorizedException);
    }
    createAuthNamespace();
    Authorizer authorizer = getAuthorizer();
    // deployment should succeed in the authorized namespace because alice has all privileges on it
    ApplicationManager appManager = deployApplication(AUTH_NAMESPACE.toId(), DummyApp.class);
    // alice should get all privileges on the app after deployment succeeds
    ApplicationId dummyAppId = AUTH_NAMESPACE.app(DummyApp.class.getSimpleName());
    ArtifactSummary artifact = appManager.getInfo().getArtifact();
    ArtifactId dummyArtifact =
      Ids.namespace(dummyAppId.getNamespace()).artifact(artifact.getName(), artifact.getVersion());
    ProgramId greetingServiceId = dummyAppId.service(DummyApp.Greeting.SERVICE_NAME);
    DatasetId dsId = AUTH_NAMESPACE.dataset("whom");
    Assert.assertEquals(
      ImmutableSet.of(
        new Privilege(instance, Action.ADMIN),
        new Privilege(AUTH_NAMESPACE, Action.ALL),
        new Privilege(dummyAppId, Action.ALL),
        new Privilege(dummyArtifact, Action.ALL),
        new Privilege(greetingServiceId, Action.ALL),
        new Privilege(dsId, Action.ALL)
      ),
      authorizer.listPrivileges(ALICE)
    );
    // Bob should not have any privileges on Alice's app
    Assert.assertTrue("Bob should not have any privileges on alice's app", authorizer.listPrivileges(BOB).isEmpty());
    // This is necessary because in tests, artifacts have auto-generated versions when an app is deployed without
    // first creating an artifact
    String version = artifact.getVersion();
    // update should succeed because alice has admin privileges on the app
    appManager.update(new AppRequest(artifact));
    // Update should fail for Bob
    SecurityRequestContext.setUserId(BOB.getName());
    try {
      appManager.update(new AppRequest(new ArtifactSummary(DummyApp.class.getSimpleName(), version)));
      Assert.fail("App update should have failed because Alice does not have admin privileges on the app.");
    } catch (UnauthorizedException expected) {
      // expected
    }
    // grant READ and WRITE to Bob
    authorizer.grant(dummyAppId, BOB, ImmutableSet.of(Action.READ, Action.WRITE));
    // delete should fail
    try {
      appManager.delete();
    } catch (UnauthorizedException expected) {
      // expected
    }
    // grant ADMIN to Bob. Now delete should succeed
    authorizer.grant(dummyAppId, BOB, ImmutableSet.of(Action.ADMIN));
    Assert.assertEquals(
      ImmutableSet.of(
        new Privilege(dummyAppId, Action.READ),
        new Privilege(dummyAppId, Action.WRITE),
        new Privilege(dummyAppId, Action.ADMIN)
      ),
      authorizer.listPrivileges(BOB)
    );
    appManager.delete();
    // All privileges on the app should have been revoked
    Assert.assertEquals(
      ImmutableSet.of(
        new Privilege(instance, Action.ADMIN),
        new Privilege(AUTH_NAMESPACE, Action.ALL),
        new Privilege(dummyArtifact, Action.ALL),
        new Privilege(dsId, Action.ALL)
      ),
      authorizer.listPrivileges(ALICE)
    );
    Assert.assertTrue("Bob should not have any privileges because all privileges on the app have been revoked " +
                        "since the app got deleted", authorizer.listPrivileges(BOB).isEmpty());
    // switch back to Alice
    SecurityRequestContext.setUserId(ALICE.getName());
    // Deploy a couple of apps in the namespace
    appManager = deployApplication(AUTH_NAMESPACE.toId(), DummyApp.class);
    artifact = appManager.getInfo().getArtifact();
    ArtifactId updatedDummyArtifact = AUTH_NAMESPACE.artifact(artifact.getName(), artifact.getVersion());
    appManager = deployApplication(AUTH_NAMESPACE.toId(), AllProgramsApp.class);
    artifact = appManager.getInfo().getArtifact();
    ArtifactId workflowArtifact = AUTH_NAMESPACE.artifact(artifact.getName(), artifact.getVersion());
    ApplicationId workflowAppId = AUTH_NAMESPACE.app(AllProgramsApp.NAME);

    ProgramId flowId = workflowAppId.flow(AllProgramsApp.NoOpFlow.NAME);
    ProgramId classicMapReduceId = workflowAppId.mr(AllProgramsApp.NoOpMR.NAME);
    ProgramId mapReduceId = workflowAppId.mr(AllProgramsApp.NoOpMR2.NAME);
    ProgramId sparkId = workflowAppId.spark(AllProgramsApp.NoOpSpark.NAME);
    ProgramId workflowId = workflowAppId.workflow(AllProgramsApp.NoOpWorkflow.NAME);
    ProgramId serviceId = workflowAppId.service(AllProgramsApp.NoOpService.NAME);
    ProgramId workerId = workflowAppId.worker(AllProgramsApp.NoOpWorker.NAME);
    DatasetId kvt = AUTH_NAMESPACE.dataset(AllProgramsApp.DATASET_NAME);
    DatasetId kvt2 = AUTH_NAMESPACE.dataset(AllProgramsApp.DATASET_NAME2);
    DatasetId kvt3 = AUTH_NAMESPACE.dataset(AllProgramsApp.DATASET_NAME3);
    DatasetId dsWithSchema = AUTH_NAMESPACE.dataset(AllProgramsApp.DS_WITH_SCHEMA_NAME);

    Assert.assertEquals(
      ImmutableSet.of(
        new Privilege(instance, Action.ADMIN),
        new Privilege(AUTH_NAMESPACE, Action.ALL),
        new Privilege(dummyArtifact, Action.ALL),
        new Privilege(updatedDummyArtifact, Action.ALL),
        new Privilege(workflowArtifact, Action.ALL),
        new Privilege(dummyAppId, Action.ALL),
        new Privilege(workflowAppId, Action.ALL),
        new Privilege(greetingServiceId, Action.ALL),
        new Privilege(flowId, Action.ALL),
        new Privilege(classicMapReduceId, Action.ALL),
        new Privilege(mapReduceId, Action.ALL),
        new Privilege(sparkId, Action.ALL),
        new Privilege(workflowId, Action.ALL),
        new Privilege(serviceId, Action.ALL),
        new Privilege(workerId, Action.ALL),
        new Privilege(dsId, Action.ALL),
        new Privilege(kvt, Action.ALL),
        new Privilege(kvt2, Action.ALL),
        new Privilege(kvt3, Action.ALL),
        new Privilege(dsWithSchema, Action.ALL)
      ),
      authorizer.listPrivileges(ALICE)
    );
    // revoke all privileges on an app.
    authorizer.revoke(workflowAppId);
    // TODO: CDAP-5428 Revoking privileges on an app should revoke privileges on the contents of the app
    authorizer.revoke(flowId);
    authorizer.revoke(classicMapReduceId);
    authorizer.revoke(mapReduceId);
    authorizer.revoke(sparkId);
    authorizer.revoke(workflowId);
    authorizer.revoke(serviceId);
    authorizer.revoke(workerId);

    Assert.assertEquals(
      ImmutableSet.of(
        new Privilege(instance, Action.ADMIN),
        new Privilege(AUTH_NAMESPACE, Action.ALL),
        new Privilege(dummyArtifact, Action.ALL),
        new Privilege(updatedDummyArtifact, Action.ALL),
        new Privilege(workflowArtifact, Action.ALL),
        new Privilege(dummyAppId, Action.ALL),
        new Privilege(greetingServiceId, Action.ALL),
        new Privilege(dsId, Action.ALL),
        new Privilege(kvt, Action.ALL),
        new Privilege(kvt2, Action.ALL),
        new Privilege(kvt3, Action.ALL),
        new Privilege(dsWithSchema, Action.ALL)
      ),
      authorizer.listPrivileges(ALICE)
    );
    // deleting all apps should fail because alice does not have admin privileges on the Workflow app
    try {
      deleteAllApplications(AUTH_NAMESPACE);
      Assert.fail("Deleting all applications in the namespace should have failed because alice does not have ADMIN " +
                    "privilege on the workflow app.");
    } catch (UnauthorizedException expected) {
      // expected
    }
    // grant admin privilege on the WorkflowApp. deleting all applications should succeed.
    authorizer.grant(workflowAppId, ALICE, ImmutableSet.of(Action.ADMIN));
    deleteAllApplications(AUTH_NAMESPACE);
    // deleting all apps should remove all privileges on all apps, but the privilege on the namespace should still exist
    Assert.assertEquals(
      ImmutableSet.of(
        new Privilege(instance, Action.ADMIN),
        new Privilege(AUTH_NAMESPACE, Action.ALL),
        new Privilege(dummyArtifact, Action.ALL),
        new Privilege(updatedDummyArtifact, Action.ALL),
        new Privilege(workflowArtifact, Action.ALL),
        new Privilege(dsId, Action.ALL),
        new Privilege(kvt, Action.ALL),
        new Privilege(kvt2, Action.ALL),
        new Privilege(kvt3, Action.ALL),
        new Privilege(dsWithSchema, Action.ALL)
      ),
      authorizer.listPrivileges(ALICE)
    );
  }

  @Test
  public void testArtifacts() throws Exception {
    String appArtifactName = "app-artifact";
    String appArtifactVersion = "1.1.1";
    try {
      ArtifactId defaultNsArtifact = NamespaceId.DEFAULT.artifact(appArtifactName, appArtifactVersion);
      addAppArtifact(defaultNsArtifact, ConfigTestApp.class);
      Assert.fail("Should not be able to add an app artifact to the default namespace because alice does not have " +
                    "write privileges on the default namespace.");
    } catch (UnauthorizedException expected) {
      // expected
    }
    String pluginArtifactName = "plugin-artifact";
    String pluginArtifactVersion = "1.2.3";
    try {
      ArtifactId defaultNsArtifact = NamespaceId.DEFAULT.artifact(pluginArtifactName, pluginArtifactVersion);
      addAppArtifact(defaultNsArtifact, ToStringPlugin.class);
      Assert.fail("Should not be able to add a plugin artifact to the default namespace because alice does not have " +
                    "write privileges on the default namespace.");
    } catch (UnauthorizedException expected) {
      // expected
    }
    // create a new namespace, alice should get ALL privileges on the namespace
    createAuthNamespace();
    Authorizer authorizer = getAuthorizer();
    // artifact deployment in this namespace should now succeed, and alice should have ALL privileges on the artifacts
    ArtifactId appArtifactId = new ArtifactId(AUTH_NAMESPACE.getNamespace(), appArtifactName, appArtifactVersion);
    ArtifactManager appArtifactManager = addAppArtifact(appArtifactId, ConfigTestApp.class);
    ArtifactId pluginArtifactId =
      new ArtifactId(AUTH_NAMESPACE.getNamespace(), pluginArtifactName, pluginArtifactVersion);
    ArtifactManager pluginArtifactManager = addPluginArtifact(pluginArtifactId, appArtifactId, ToStringPlugin.class);
    Assert.assertEquals(
      ImmutableSet.of(
        new Privilege(instance, Action.ADMIN),
        new Privilege(AUTH_NAMESPACE, Action.ALL),
        new Privilege(appArtifactId, Action.ALL),
        new Privilege(pluginArtifactId, Action.ALL)
      ),
      authorizer.listPrivileges(ALICE)
    );
    // Bob should not be able to delete artifacts that he does not have ADMIN permission on
    SecurityRequestContext.setUserId(BOB.getName());
    try {
      appArtifactManager.writeProperties(ImmutableMap.of("authorized", "no"));
      Assert.fail("Writing properties to artifact should have failed because Bob does not have admin privileges on " +
                    "the artifact");
    } catch (UnauthorizedException expected) {
      // expected
    }

    try {
      appArtifactManager.delete();
      Assert.fail("Deleting artifact should have failed because Bob does not have admin privileges on the artifact");
    } catch (UnauthorizedException expected) {
      // expected
    }

    try {
      pluginArtifactManager.writeProperties(ImmutableMap.of("authorized", "no"));
      Assert.fail("Writing properties to artifact should have failed because Bob does not have admin privileges on " +
                    "the artifact");
    } catch (UnauthorizedException expected) {
      // expected
    }

    try {
      pluginArtifactManager.removeProperties();
      Assert.fail("Removing properties to artifact should have failed because Bob does not have admin privileges on " +
                    "the artifact");
    } catch (UnauthorizedException expected) {
      // expected
    }

    try {
      pluginArtifactManager.delete();
      Assert.fail("Deleting artifact should have failed because Bob does not have admin privileges on the artifact");
    } catch (UnauthorizedException expected) {
      // expected
    }
    // alice should be permitted to update properties/delete artifact
    SecurityRequestContext.setUserId(ALICE.getName());
    appArtifactManager.writeProperties(ImmutableMap.of("authorized", "yes"));
    appArtifactManager.removeProperties();
    appArtifactManager.delete();
    pluginArtifactManager.delete();
    // upon successful deletion, alice should lose all privileges on the artifact
    Assert.assertEquals(
      ImmutableSet.of(
        new Privilege(instance, Action.ADMIN),
        new Privilege(AUTH_NAMESPACE, Action.ALL)
      ),
      authorizer.listPrivileges(ALICE)
    );
  }

  @Test
  public void testPrograms() throws Exception {
    createAuthNamespace();
    Authorizer authorizer = getAuthorizer();
    final ApplicationManager dummyAppManager = deployApplication(AUTH_NAMESPACE.toId(), DummyApp.class);
    ArtifactSummary dummyArtifactSummary = dummyAppManager.getInfo().getArtifact();
    ArtifactId dummyArtifact = AUTH_NAMESPACE.artifact(dummyArtifactSummary.getName(),
                                                                 dummyArtifactSummary.getVersion());
    ApplicationId appId = AUTH_NAMESPACE.app(DummyApp.class.getSimpleName());
    final ProgramId serviceId = appId.service(DummyApp.Greeting.SERVICE_NAME);
    DatasetId dsId = AUTH_NAMESPACE.dataset("whom");
    Assert.assertEquals(
      ImmutableSet.of(
        new Privilege(instance, Action.ADMIN),
        new Privilege(AUTH_NAMESPACE, Action.ALL),
        new Privilege(dummyArtifact, Action.ALL),
        new Privilege(appId, Action.ALL),
        new Privilege(serviceId, Action.ALL),
        new Privilege(dsId, Action.ALL)
      ),
      authorizer.listPrivileges(ALICE)
    );
    // alice should be able to start and stop programs in the app she deployed
    dummyAppManager.startProgram(serviceId.toId());
    Tasks.waitFor(true, new Callable<Boolean>() {
      @Override
      public Boolean call() throws Exception {
        return dummyAppManager.isRunning(serviceId.toId());
      }
    }, 5, TimeUnit.SECONDS);
    ServiceManager greetingService = dummyAppManager.getServiceManager(serviceId.getProgram());
    // alice should be able to set instances for the program
    greetingService.setInstances(2);
    Assert.assertEquals(2, greetingService.getProvisionedInstances());
    // alice should also be able to save runtime arguments for all future runs of the program
    Map<String, String> args = ImmutableMap.of("key", "value");
    greetingService.setRuntimeArgs(args);
    dummyAppManager.stopProgram(serviceId.toId());
    Tasks.waitFor(false, new Callable<Boolean>() {
      @Override
      public Boolean call() throws Exception {
        return dummyAppManager.isRunning(serviceId.toId());
      }
    }, 5, TimeUnit.SECONDS);
    // Bob should not be able to start programs in dummy app because he does not have privileges on it
    SecurityRequestContext.setUserId(BOB.getName());
    try {
      dummyAppManager.startProgram(serviceId.toId());
      Assert.fail("Bob should not be able to start the service because he does not have admin privileges on it.");
    } catch (RuntimeException expected) {
      //noinspection ThrowableResultOfMethodCallIgnored
      Assert.assertTrue(Throwables.getRootCause(expected) instanceof UnauthorizedException);
    }
    // TODO: CDAP-5452 can't verify running programs in this case, because DefaultApplicationManager maintains an
    // in-memory map of running processes that does not use ApplicationLifecycleService to get the runtime status.
    // So no matter if the start/stop call succeeds or fails, it updates its running state in the in-memory map.
    // Also have to switch back to being alice, start the program, and then stop it as Bob because otherwise AppManager
    // doesn't send the request to the app fabric service, but just makes decisions based on an in-memory
    // ConcurrentHashMap.
    // Also add a test for stopping with unauthorized user after the above bug is fixed
    
    // setting instances should fail because Bob does not have admin privileges on the program
    try {
      greetingService.setInstances(3);
      Assert.fail("Setting instances should have failed because bob does not have admin privileges on the service.");
    } catch (RuntimeException expected) {
      //noinspection ThrowableResultOfMethodCallIgnored
      Assert.assertTrue(Throwables.getRootCause(expected) instanceof UnauthorizedException);
    }
    try {
      greetingService.setRuntimeArgs(args);
      Assert.fail("Setting runtime arguments should have failed because bob does not have admin privileges on the " +
                    "service");
    } catch (UnauthorizedException expected) {
      // expected
    }
    SecurityRequestContext.setUserId(ALICE.getName());
    dummyAppManager.delete();
    Assert.assertEquals(
      ImmutableSet.of(
        new Privilege(instance, Action.ADMIN),
        new Privilege(AUTH_NAMESPACE, Action.ALL),
        new Privilege(dummyArtifact, Action.ALL),
        new Privilege(dsId, Action.ALL)
      ),
      authorizer.listPrivileges(ALICE)
    );
  }

  @Test
  public void testDatasets() throws Exception {
    createAuthNamespace();
    final DatasetId mydsId = AUTH_NAMESPACE.dataset("myds");
    final DatasetId mydsId1 = AUTH_NAMESPACE.dataset("myds1");
    final DatasetId mydsId2 = AUTH_NAMESPACE.dataset("myds2");
    // grant alice write access to the namespace
    grantAndAssertSuccess(AUTH_NAMESPACE, ALICE, ImmutableSet.of(Action.WRITE));
    addDatasetInstance(AUTH_NAMESPACE.toId(), Table.class.getName(), "myds");
    // should be able to get
    final DataSetManager<Table> myds = getDataset(AUTH_NAMESPACE.toId(), "myds");
    Table table = myds.get();
    table.put(Bytes.toBytes("row"), Bytes.toBytes("column"), Bytes.toBytes("value"));
    myds.flush();
    SecurityRequestContext.setUserId(BOB.getName());
    assertAuthorizationFailure(new UnauthorizedOperationExecutor() {
      @Override
      public void execute() throws Exception {
        addDatasetInstance(AUTH_NAMESPACE.toId(), Table.class.getName(), "myds");
      }
    }, String.format("Expected %s to not be have %s privilege on %s.", BOB, Action.WRITE, AUTH_NAMESPACE));
    assertAuthorizationFailure(new UnauthorizedOperationExecutor() {
      @Override
      public void execute() throws Exception {
        getDataset(AUTH_NAMESPACE.toId(), "myds");
      }
    }, String.format("Expected %s to not be have %s privilege on %s.", BOB, Action.READ, mydsId));
    SecurityRequestContext.setUserId(ALICE.getName());
    revokeAndAssertSuccess(mydsId, ALICE, EnumSet.allOf(Action.class));
    assertAuthorizationFailure(new UnauthorizedOperationExecutor() {
      @Override
      public void execute() throws Exception {
        removeDatasetInstance(mydsId);
      }
    }, String.format("Expected %s to not be have %s on %s.", ALICE, Action.ADMIN, mydsId));
    grantAndAssertSuccess(mydsId, ALICE, ImmutableSet.of(Action.ADMIN));
    addDatasetInstance(AUTH_NAMESPACE.toId(), KeyValueTable.class.getName(), "myds1");
    addDatasetInstance(AUTH_NAMESPACE.toId(), Table.class.getName(), "myds2");
    grantAndAssertSuccess(mydsId1, BOB, ImmutableSet.of(Action.ADMIN));
    SecurityRequestContext.setUserId(BOB.getName());
    getDataset(AUTH_NAMESPACE.toId(), "myds1");
    assertAuthorizationFailure(new UnauthorizedOperationExecutor() {
      @Override
      public void execute() throws Exception {
        getDataset(AUTH_NAMESPACE.toId(), "myds2");
      }
    }, String.format("Expected %s to not have access to %s.", BOB, mydsId2));
    Assert.assertEquals(ImmutableList.of(mydsId1), listDatasets(AUTH_NAMESPACE));
    removeDatasetInstance(mydsId1);
    SecurityRequestContext.setUserId(ALICE.getName());
    removeDatasetInstance(mydsId);
    removeDatasetInstance(mydsId2);
  }

  @After
  public void cleanupTest() throws Exception {
    Authorizer authorizer = getAuthorizer();
    // clean up. remove the namespace. all privileges on the namespace should be revoked
    getNamespaceAdmin().delete(AUTH_NAMESPACE.toId());
    Assert.assertEquals(ImmutableSet.of(new Privilege(instance, Action.ADMIN)), authorizer.listPrivileges(ALICE));
    // revoke privileges on the instance
    revokeAndAssertSuccess(instance);
  }

  @AfterClass
  public static void cleanup() throws Exception {
    // we want to execute TestBase's @AfterClass after unsetting userid, because the old userid has been granted ADMIN
    // on default namespace in TestBase so it can clean the namespace.
    SecurityRequestContext.setUserId(OLD_USER);
    finish();
  }

  private void createAuthNamespace() throws Exception {
    Authorizer authorizer = getAuthorizer();
    grantAndAssertSuccess(instance, ALICE, ImmutableSet.of(Action.ADMIN));
    getNamespaceAdmin().create(AUTH_NAMESPACE_META);
    Assert.assertEquals(
      ImmutableSet.of(new Privilege(instance, Action.ADMIN), new Privilege(AUTH_NAMESPACE, Action.ALL)),
      authorizer.listPrivileges(ALICE)
    );
  }

  private void assertAuthorizationFailure(UnauthorizedOperationExecutor operation, String failureMsg) throws Exception {
    try {
      operation.execute();
      Assert.fail(failureMsg);
    } catch (UnauthorizedException expected) {
      // expected
    } catch (DatasetManagementException e) {
      // sigh! no other way to detect errors from DatasetServiceClient
      Assert.assertTrue(e.getMessage().contains("Response code: 403, message: 'Forbidden'"));
    }
  }

  private void grantAndAssertSuccess(EntityId entityId, Principal principal, Set<Action> actions) throws Exception {
    Authorizer authorizer = getAuthorizer();
    Set<Privilege> existingPrivileges = authorizer.listPrivileges(principal);
    authorizer.grant(entityId, principal, actions);
    ImmutableSet.Builder<Privilege> expectedPrivilegesAfterGrant = ImmutableSet.builder();
    for (Action action : actions) {
      expectedPrivilegesAfterGrant.add(new Privilege(entityId, action));
    }
    Assert.assertEquals(Sets.union(existingPrivileges, expectedPrivilegesAfterGrant.build()),
                        authorizer.listPrivileges(principal));
  }

  private void revokeAndAssertSuccess(EntityId entityId, Principal principal, Set<Action> actions) throws Exception {
    Authorizer authorizer = getAuthorizer();
    Set<Privilege> existingPrivileges = authorizer.listPrivileges(principal);
    authorizer.revoke(entityId, principal, actions);
    ImmutableSet.Builder<Privilege> expectedPrivilegesAfterRevoke = ImmutableSet.builder();
    for (Action action : actions) {
      expectedPrivilegesAfterRevoke.add(new Privilege(entityId, action));
    }
    Assert.assertEquals(Sets.difference(existingPrivileges, expectedPrivilegesAfterRevoke.build()),
                        authorizer.listPrivileges(principal));
  }

  private void revokeAndAssertSuccess(final EntityId entityId) throws Exception {
    Authorizer authorizer = getAuthorizer();
    authorizer.revoke(entityId);
    Predicate<Privilege> entityFilter = new Predicate<Privilege>() {
      @Override
      public boolean apply(Privilege input) {
        return entityId.equals(input.getEntity());
      }
    };
    Assert.assertTrue(Sets.filter(authorizer.listPrivileges(ALICE), entityFilter).isEmpty());
    Assert.assertTrue(Sets.filter(authorizer.listPrivileges(BOB), entityFilter).isEmpty());
  }

  private interface UnauthorizedOperationExecutor {
    void execute() throws Exception;
  }
}
