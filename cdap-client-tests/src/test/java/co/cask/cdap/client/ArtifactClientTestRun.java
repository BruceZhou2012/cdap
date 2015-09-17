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

package co.cask.cdap.client;

import co.cask.cdap.api.artifact.ApplicationClass;
import co.cask.cdap.api.artifact.ArtifactClasses;
import co.cask.cdap.api.artifact.ArtifactScope;
import co.cask.cdap.api.artifact.ArtifactVersion;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.plugin.PluginClass;
import co.cask.cdap.api.plugin.PluginPropertyField;
import co.cask.cdap.app.program.ManifestFields;
import co.cask.cdap.client.artifact.MyApp;
import co.cask.cdap.client.artifact.plugin.Plugin1;
import co.cask.cdap.client.common.ClientTestBase;
import co.cask.cdap.client.util.RESTClient;
import co.cask.cdap.common.ArtifactNotFoundException;
import co.cask.cdap.common.BadRequestException;
import co.cask.cdap.common.NotFoundException;
import co.cask.cdap.internal.io.ReflectionSchemaGenerator;
import co.cask.cdap.internal.test.AppJarHelper;
import co.cask.cdap.internal.test.PluginJarHelper;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.artifact.ApplicationClassInfo;
import co.cask.cdap.proto.artifact.ApplicationClassSummary;
import co.cask.cdap.proto.artifact.ArtifactInfo;
import co.cask.cdap.proto.artifact.ArtifactRange;
import co.cask.cdap.proto.artifact.ArtifactSummary;
import co.cask.cdap.proto.artifact.PluginInfo;
import co.cask.cdap.proto.artifact.PluginSummary;
import co.cask.cdap.test.XSlowTests;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.InputSupplier;
import org.apache.twill.filesystem.LocalLocationFactory;
import org.apache.twill.filesystem.Location;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;

/**
 * Test for {@link ArtifactClient}
 */
@Category(XSlowTests.class)
public class ArtifactClientTestRun extends ClientTestBase {
  private static final InputSupplier<InputStream> DUMMY_SUPPLIER = new InputSupplier<InputStream>() {
    @Override
    public InputStream getInput() throws IOException {
      return new ByteArrayInputStream(new byte[]{});
    }
  };

  @ClassRule
  public static TemporaryFolder tmpFolder = new TemporaryFolder();

  private ArtifactClient artifactClient;

  @Before
  public void setUp() throws Throwable {
    super.setUp();
    artifactClient = new ArtifactClient(clientConfig, new RESTClient(clientConfig));
    for (ArtifactSummary artifactSummary : artifactClient.list(Id.Namespace.DEFAULT)) {
      artifactClient.delete(
        Id.Artifact.from(Id.Namespace.DEFAULT, artifactSummary.getName(), artifactSummary.getVersion()));
    }
  }

  @Test
  public void testNotFound() throws Exception {
    try {
      artifactClient.list(Id.Namespace.from("ghost"));
      Assert.fail();
    } catch (NotFoundException e) {
      // expected
    }

    try {
      artifactClient.getArtifactInfo(Id.Artifact.from(Id.Namespace.DEFAULT, "ghost", "1.0.0"));
      Assert.fail();
    } catch (ArtifactNotFoundException e) {
      // expected
    }

    try {
      artifactClient.listVersions(Id.Namespace.DEFAULT, "ghost");
      Assert.fail();
    } catch (ArtifactNotFoundException e) {
      // expected
    }

    // test adding an artifact that extends a non-existent artifact
    Set<ArtifactRange> parents = Sets.newHashSet(
      new ArtifactRange(Id.Namespace.DEFAULT, "ghost", new ArtifactVersion("1"), new ArtifactVersion("2")));
    try {
      artifactClient.add(Id.Namespace.DEFAULT, "abc", DUMMY_SUPPLIER, "1.0.0", parents);
      Assert.fail();
    } catch (NotFoundException e) {
      // expected
    }
  }

  @Test
  public void testAddArtifactBadIds() throws Exception {
    // test bad version
    try {
      artifactClient.add(Id.Namespace.DEFAULT, "abc", DUMMY_SUPPLIER, "1/0.0");
      Assert.fail();
    } catch (BadRequestException e) {
      // expected
    }

    // test bad name
    try {
      artifactClient.add(Id.Namespace.DEFAULT, "ab:c", DUMMY_SUPPLIER, "1.0.0");
      Assert.fail();
    } catch (BadRequestException e) {
      // expected
    }
  }

  @Test
  public void testAddSelfExtendingThrowsBadRequest() throws Exception {
    try {
      artifactClient.add(Id.Namespace.DEFAULT, "abc", DUMMY_SUPPLIER, "1.0.0", Sets.newHashSet(
        new ArtifactRange(Id.Namespace.DEFAULT, "abc", new ArtifactVersion("1.0.0"), new ArtifactVersion("2.0.0"))
      ));
      Assert.fail();
    } catch (BadRequestException e) {
      // expected
    }
  }

  @Test
  public void testArtifacts() throws Exception {
    // add 2 versions of an artifact with an application
    Id.Artifact myapp1Id = Id.Artifact.from(Id.Namespace.DEFAULT, "myapp", "1.0.0");
    Id.Artifact myapp2Id = Id.Artifact.from(Id.Namespace.DEFAULT, "myapp", "2.0.0");
    LocalLocationFactory locationFactory = new LocalLocationFactory(tmpFolder.newFolder());
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(ManifestFields.BUNDLE_VERSION, "2.0.0");
    final Location appJarLoc = AppJarHelper.createDeploymentJar(locationFactory, MyApp.class, manifest);

    InputSupplier<InputStream> inputSupplier = new InputSupplier<InputStream>() {
      @Override
      public InputStream getInput() throws IOException {
        return appJarLoc.getInputStream();
      }
    };
    artifactClient.add(myapp1Id.getNamespace(), myapp1Id.getName(),
                       inputSupplier, myapp1Id.getVersion().getVersion());
    // let it derive version from jar manifest, which has bundle-version at 2.0.0
    artifactClient.add(myapp2Id.getNamespace(), myapp2Id.getName(), inputSupplier, null, null);

    // add an artifact that contains a plugin, but only extends myapp-2.0.0
    Id.Artifact pluginId = Id.Artifact.from(Id.Namespace.DEFAULT, "myapp-plugins", "2.0.0");
    manifest = new Manifest();
    manifest.getMainAttributes().put(ManifestFields.EXPORT_PACKAGE, Plugin1.class.getPackage().getName());
    final Location pluginJarLoc = PluginJarHelper.createPluginJar(locationFactory, manifest, Plugin1.class);
    inputSupplier = new InputSupplier<InputStream>() {
      @Override
      public InputStream getInput() throws IOException {
        return pluginJarLoc.getInputStream();
      }
    };
    Set<ArtifactRange> parents = Sets.newHashSet(new ArtifactRange(
      myapp2Id.getNamespace(), myapp2Id.getName(), myapp2Id.getVersion(), new ArtifactVersion("3.0.0")));
    Set<PluginClass> additionalPlugins = Sets.newHashSet(new PluginClass(
      "jdbc", "mysql", "", "com.mysql.jdbc.Driver", null, Collections.<String, PluginPropertyField>emptyMap()));
    artifactClient.add(pluginId.getNamespace(), pluginId.getName(), inputSupplier,
                       pluginId.getVersion().getVersion(), parents, additionalPlugins);

    ArtifactSummary myapp1Summary = new ArtifactSummary(myapp1Id.getName(), myapp1Id.getVersion().getVersion());
    ArtifactSummary myapp2Summary = new ArtifactSummary(myapp2Id.getName(), myapp2Id.getVersion().getVersion());
    ArtifactSummary pluginArtifactSummary = new ArtifactSummary(pluginId.getName(), pluginId.getVersion().getVersion());

    Set<ArtifactSummary> artifacts = Sets.newHashSet(artifactClient.list(Id.Namespace.DEFAULT));
    Assert.assertEquals(Sets.newHashSet(myapp1Summary, myapp2Summary, pluginArtifactSummary), artifacts);

    // list all artifacts named 'myapp'
    Assert.assertEquals(Sets.newHashSet(myapp1Summary, myapp2Summary),
                        Sets.newHashSet(artifactClient.listVersions(Id.Namespace.DEFAULT, myapp1Id.getName())));
    // list all artifacts named 'myapp-plugins'
    Assert.assertEquals(Sets.newHashSet(pluginArtifactSummary),
                        Sets.newHashSet(artifactClient.listVersions(Id.Namespace.DEFAULT, pluginId.getName())));
    // artifacts should be in user scope
    try {
      artifactClient.listVersions(Id.Namespace.DEFAULT, pluginId.getName(), ArtifactScope.SYSTEM);
      Assert.fail();
    } catch (ArtifactNotFoundException e) {
      // expected
    }

    // get info about specific artifacts
    Schema myAppConfigSchema = new ReflectionSchemaGenerator().generate(MyApp.Conf.class);
    ArtifactClasses myAppClasses = ArtifactClasses.builder()
      .addApp(new ApplicationClass(MyApp.class.getName(), "", myAppConfigSchema))
      .build();

    // test get myapp-1.0.0
    ArtifactInfo myapp1Info =
      new ArtifactInfo(myapp1Id.getName(), myapp1Id.getVersion().getVersion(), ArtifactScope.USER, myAppClasses);
    Assert.assertEquals(myapp1Info, artifactClient.getArtifactInfo(myapp1Id));

    // test get myapp-2.0.0
    ArtifactInfo myapp2Info =
      new ArtifactInfo(myapp2Id.getName(), myapp2Id.getVersion().getVersion(), ArtifactScope.USER, myAppClasses);
    Assert.assertEquals(myapp2Info, artifactClient.getArtifactInfo(myapp2Id));

    // test get myapp-plugins-2.0.0
    Map<String, PluginPropertyField> props = ImmutableMap.of(
      "x", new PluginPropertyField("x", "", "int", true));
    ArtifactClasses pluginClasses = ArtifactClasses.builder()
      .addPlugin(new PluginClass("callable", "plugin1", "p1 description", Plugin1.class.getName(), "conf", props))
      .addPlugins(additionalPlugins)
      .build();
    ArtifactInfo pluginArtifactInfo =
      new ArtifactInfo(pluginId.getName(), pluginId.getVersion().getVersion(), ArtifactScope.USER, pluginClasses);
    Assert.assertEquals(pluginArtifactInfo, artifactClient.getArtifactInfo(pluginId));

    // test get all app classes in namespace
    Set<ApplicationClassSummary> expectedSummaries = ImmutableSet.of(
      new ApplicationClassSummary(myapp1Summary, MyApp.class.getName()),
      new ApplicationClassSummary(myapp2Summary, MyApp.class.getName())
    );
    Set<ApplicationClassSummary> appClassSummaries = Sets.newHashSet(
      artifactClient.getApplicationClasses(Id.Namespace.DEFAULT));
    Assert.assertEquals(expectedSummaries, appClassSummaries);

    // test get all app classes in namespace with name MyApp.class.getName()
    Set<ApplicationClassInfo> appClassInfos = Sets.newHashSet(
      artifactClient.getApplicationClasses(Id.Namespace.DEFAULT, MyApp.class.getName()));
    Set<ApplicationClassInfo> expectedInfos = ImmutableSet.of(
      new ApplicationClassInfo(myapp1Summary, MyApp.class.getName(), myAppConfigSchema),
      new ApplicationClassInfo(myapp2Summary, MyApp.class.getName(), myAppConfigSchema)
    );
    Assert.assertEquals(expectedInfos, appClassInfos);

    // test get plugin types for myapp-1.0.0. should be empty, since plugins only extends versions [2.0.0 - 3.0.0)
    Assert.assertTrue(artifactClient.getPluginTypes(myapp1Id).isEmpty());

    // test get plugin types for myapp-2.0.0
    Assert.assertEquals(Lists.newArrayList("callable", "jdbc"), artifactClient.getPluginTypes(myapp2Id));

    // test get plugins of type callable for myapp-2.0.0
    PluginSummary pluginSummary =
      new PluginSummary("plugin1", "callable", "p1 description", Plugin1.class.getName(), pluginArtifactSummary);
    Assert.assertEquals(Sets.newHashSet(pluginSummary),
                        Sets.newHashSet(artifactClient.getPluginSummaries(myapp2Id, "callable")));
    // no plugins of type "runnable"
    Assert.assertTrue(artifactClient.getPluginSummaries(myapp2Id, "runnable").isEmpty());

    // test get plugin details for plugin1 for myapp-2.0.0
    PluginInfo pluginInfo = new PluginInfo("plugin1", "callable", "p1 description", Plugin1.class.getName(),
      pluginArtifactSummary, props);
    Assert.assertEquals(Sets.newHashSet(pluginInfo),
                        Sets.newHashSet(artifactClient.getPluginInfo(myapp2Id, "callable", "plugin1")));
  }

}