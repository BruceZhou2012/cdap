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

package co.cask.cdap.metadata;

import co.cask.cdap.AppWithDataset;
import co.cask.cdap.WordCountApp;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.ProgramType;
import co.cask.cdap.proto.metadata.MetadataRecord;
import co.cask.cdap.proto.metadata.MetadataScope;
import co.cask.common.http.HttpResponse;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Tests for {@link MetadataHttpHandler}
 */
public class MetadataHttpHandlerTest extends MetadataTestBase {

  private final Id.Application application =
    Id.Application.from(Id.Namespace.DEFAULT, AppWithDataset.class.getSimpleName());
  private final Id.Program pingService = Id.Program.from(application, ProgramType.SERVICE, "PingService");
  private final Id.DatasetInstance myds = Id.DatasetInstance.from(Id.Namespace.DEFAULT, "myds");
  private final Id.Stream mystream = Id.Stream.from(Id.Namespace.DEFAULT, "mystream");
  private final Id.Application nonExistingApp = Id.Application.from("blah", AppWithDataset.class.getSimpleName());
  private final Id.Service nonExistingService = Id.Service.from(nonExistingApp, "PingService");
  private final Id.DatasetInstance nonExistingDataset = Id.DatasetInstance.from("blah", "myds");
  private final Id.Stream nonExistingStream = Id.Stream.from("blah", "mystream");

  @Before
  public void before() throws Exception {
    Assert.assertEquals(200, deploy(AppWithDataset.class).getStatusLine().getStatusCode());
  }

  @After
  public void after() throws Exception {
    deleteApp(application, 200);
  }

  @Test
  public void testProperties() throws IOException {
    // should fail because we haven't provided any metadata in the request
    Assert.assertEquals(400, addProperties(application).getResponseCode());
    Map<String, String> appProperties = ImmutableMap.of("aKey", "aValue", "aK", "aV");
    Assert.assertEquals(200, addProperties(application, appProperties).getResponseCode());
    // should fail because we haven't provided any metadata in the request
    Assert.assertEquals(400, addProperties(pingService).getResponseCode());
    Map<String, String> serviceProperties = ImmutableMap.of("sKey", "sValue", "sK", "sV");
    Assert.assertEquals(200, addProperties(pingService, serviceProperties).getResponseCode());
    Assert.assertEquals(400, addProperties(myds).getResponseCode());
    Map<String, String> datasetProperties = ImmutableMap.of("dKey", "dValue", "dK", "dV");
    Assert.assertEquals(200, addProperties(myds, datasetProperties).getResponseCode());
    Assert.assertEquals(400, addProperties(mystream).getResponseCode());
    Map<String, String> streamProperties = ImmutableMap.of("stKey", "stValue", "stK", "stV");
    Assert.assertEquals(200, addProperties(mystream, streamProperties).getResponseCode());
    // retrieve properties and verify
    Map<String, String> properties = getProperties(application);
    Assert.assertEquals(appProperties, properties);
    properties = getProperties(pingService);
    Assert.assertEquals(serviceProperties, properties);
    properties = getProperties(myds);
    Assert.assertEquals(datasetProperties, properties);
    properties = getProperties(mystream);
    Assert.assertEquals(streamProperties, properties);
    // test removal
    removeProperties(application);
    Assert.assertTrue(getProperties(application).isEmpty());
    removeProperties(pingService, "sKey");
    removeProperties(pingService, "sK");
    Assert.assertTrue(getProperties(pingService).isEmpty());
    removeProperties(myds, "dKey");
    Assert.assertEquals(ImmutableMap.of("dK", "dV"), getProperties(myds));
    removeProperties(mystream, "stK");
    Assert.assertEquals(ImmutableMap.of("stKey", "stValue"), getProperties(mystream));
    // cleanup
    removeProperties(application);
    removeProperties(pingService);
    removeProperties(myds);
    removeProperties(mystream);
    Assert.assertTrue(getProperties(application).isEmpty());
    Assert.assertTrue(getProperties(pingService).isEmpty());
    Assert.assertTrue(getProperties(myds).isEmpty());
    Assert.assertTrue(getProperties(mystream).isEmpty());

    // non-existing namespace
    Assert.assertEquals(404, addProperties(nonExistingApp, appProperties).getResponseCode());
    Assert.assertEquals(404, addProperties(nonExistingService, serviceProperties).getResponseCode());
    Assert.assertEquals(404, addProperties(nonExistingDataset, datasetProperties).getResponseCode());
    Assert.assertEquals(404, addProperties(nonExistingStream, streamProperties).getResponseCode());
  }

  @Test
  public void testTags() throws IOException {
    // should fail because we haven't provided any metadata in the request
    Assert.assertEquals(400, addTags(application).getResponseCode());
    Set<String> appTags = ImmutableSet.of("aTag", "aT");
    Assert.assertEquals(200, addTags(application, appTags).getResponseCode());
    // should fail because we haven't provided any metadata in the request
    Assert.assertEquals(400, addTags(pingService).getResponseCode());
    Set<String> serviceTags = ImmutableSet.of("sTag", "sT");
    Assert.assertEquals(200, addTags(pingService, serviceTags).getResponseCode());
    Assert.assertEquals(400, addTags(myds).getResponseCode());
    Set<String> datasetTags = ImmutableSet.of("dTag", "dT");
    Assert.assertEquals(200, addTags(myds, datasetTags).getResponseCode());
    Assert.assertEquals(400, addTags(mystream).getResponseCode());
    Set<String> streamTags = ImmutableSet.of("stTag", "stT");
    Assert.assertEquals(200, addTags(mystream, streamTags).getResponseCode());
    // retrieve tags and verify
    Set<String> tags = getTags(application);
    Assert.assertTrue(tags.containsAll(appTags));
    Assert.assertTrue(appTags.containsAll(tags));
    tags = getTags(pingService);
    Assert.assertTrue(tags.containsAll(serviceTags));
    Assert.assertTrue(serviceTags.containsAll(tags));
    tags = getTags(myds);
    Assert.assertTrue(tags.containsAll(datasetTags));
    Assert.assertTrue(datasetTags.containsAll(tags));
    tags = getTags(mystream);
    Assert.assertTrue(tags.containsAll(streamTags));
    Assert.assertTrue(streamTags.containsAll(tags));
    // test removal
    removeTags(application, "aTag");
    Assert.assertEquals(ImmutableSet.of("aT"), getTags(application));
    removeTags(pingService);
    Assert.assertTrue(getTags(pingService).isEmpty());
    removeTags(pingService);
    Assert.assertTrue(getTags(pingService).isEmpty());
    removeTags(myds, "dT");
    Assert.assertEquals(ImmutableSet.of("dTag"), getTags(myds));
    removeTags(mystream, "stT");
    removeTags(mystream, "stTag");
    Assert.assertTrue(getTags(mystream).isEmpty());
    // cleanup
    removeTags(application);
    removeTags(pingService);
    removeTags(myds);
    removeTags(mystream);
    Assert.assertTrue(getTags(application).isEmpty());
    Assert.assertTrue(getTags(pingService).isEmpty());
    Assert.assertTrue(getTags(myds).isEmpty());
    Assert.assertTrue(getTags(mystream).isEmpty());
    // non-existing namespace
    Assert.assertEquals(404, addTags(nonExistingApp, appTags).getResponseCode());
    Assert.assertEquals(404, addTags(nonExistingService, serviceTags).getResponseCode());
    Assert.assertEquals(404, addTags(nonExistingDataset, datasetTags).getResponseCode());
    Assert.assertEquals(404, addTags(nonExistingStream, streamTags).getResponseCode());
  }

  @Test
  public void testMetadata() throws IOException {
    assertCleanState();
    // Remove when nothing exists
    removeAllMetadata();
    assertCleanState();
    // Add some properties and tags
    Map<String, String> appProperties = ImmutableMap.of("aKey", "aValue");
    Map<String, String> serviceProperties = ImmutableMap.of("sKey", "sValue");
    Map<String, String> datasetProperties = ImmutableMap.of("dKey", "dValue");
    Map<String, String> streamProperties = ImmutableMap.of("stKey", "stValue");
    Set<String> appTags = ImmutableSet.of("aTag");
    Set<String> serviceTags = ImmutableSet.of("sTag");
    Set<String> datasetTags = ImmutableSet.of("dTag");
    Set<String> streamTags = ImmutableSet.of("stTag");
    Assert.assertEquals(200, addProperties(application, appProperties).getResponseCode());
    Assert.assertEquals(200, addProperties(pingService, serviceProperties).getResponseCode());
    Assert.assertEquals(200, addProperties(myds, datasetProperties).getResponseCode());
    Assert.assertEquals(200, addProperties(mystream, streamProperties).getResponseCode());
    Assert.assertEquals(200, addTags(application, appTags).getResponseCode());
    Assert.assertEquals(200, addTags(pingService, serviceTags).getResponseCode());
    Assert.assertEquals(200, addTags(myds, datasetTags).getResponseCode());
    Assert.assertEquals(200, addTags(mystream, streamTags).getResponseCode());
    // verify app
    Set<MetadataRecord> metadataRecords = getMetadata(application);
    Assert.assertEquals(1, metadataRecords.size());
    MetadataRecord metadata = metadataRecords.iterator().next();
    Assert.assertEquals(MetadataScope.USER, metadata.getScope());
    Assert.assertEquals(application, metadata.getTargetId());
    Assert.assertEquals(appProperties, metadata.getProperties());
    Assert.assertEquals(appTags, metadata.getTags());
    // verify service
    metadataRecords = getMetadata(pingService);
    Assert.assertEquals(1, metadataRecords.size());
    metadata = metadataRecords.iterator().next();
    Assert.assertEquals(MetadataScope.USER, metadata.getScope());
    Assert.assertEquals(pingService, metadata.getTargetId());
    Assert.assertEquals(serviceProperties, metadata.getProperties());
    Assert.assertEquals(serviceTags, metadata.getTags());
    // verify dataset
    metadataRecords = getMetadata(myds);
    Assert.assertEquals(1, metadataRecords.size());
    metadata = metadataRecords.iterator().next();
    Assert.assertEquals(MetadataScope.USER, metadata.getScope());
    Assert.assertEquals(myds, metadata.getTargetId());
    Assert.assertEquals(datasetProperties, metadata.getProperties());
    Assert.assertEquals(datasetTags, metadata.getTags());
    // verify service
    metadataRecords = getMetadata(mystream);
    Assert.assertEquals(1, metadataRecords.size());
    metadata = metadataRecords.iterator().next();
    Assert.assertEquals(MetadataScope.USER, metadata.getScope());
    Assert.assertEquals(mystream, metadata.getTargetId());
    Assert.assertEquals(streamProperties, metadata.getProperties());
    Assert.assertEquals(streamTags, metadata.getTags());
    // cleanup
    removeAllMetadata();
    assertCleanState();
  }

  @Test
  public void testLineage() throws Exception {
    Id.DatasetInstance datasetInstance = Id.DatasetInstance.from("default", "dummy");
    Assert.assertEquals(200, fetchLineage(datasetInstance, 100, 200, 10).getResponseCode());
    Assert.assertEquals(400, fetchLineage(datasetInstance, -100, 200, 10).getResponseCode());
    Assert.assertEquals(400, fetchLineage(datasetInstance, 100, -200, 10).getResponseCode());
    Assert.assertEquals(400, fetchLineage(datasetInstance, 200, 100, 10).getResponseCode());
    Assert.assertEquals(400, fetchLineage(datasetInstance, 100, 200, -10).getResponseCode());
  }

  @Test
  public void testDeleteApplication() throws Exception {
    deploy(WordCountApp.class, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);
    Id.Program program = Id.Program.from(TEST_NAMESPACE1, "WordCountApp", ProgramType.FLOW, "WordCountFlow");

    // Set some properties metadata
    Map<String, String> flowProperties = ImmutableMap.of("sKey", "sValue", "sK", "sV");
    addProperties(program, flowProperties);

    // Get properties
    Map<String, String> properties = getProperties(program);
    Assert.assertEquals(2, properties.size());

    //Delete the App after stopping the flow
    org.apache.http.HttpResponse response =
      doDelete(getVersionedAPIPath("apps/WordCountApp/", Constants.Gateway.API_VERSION_3_TOKEN,
                                   TEST_NAMESPACE1));
    Assert.assertEquals(200, response.getStatusLine().getStatusCode());
    response = doDelete(getVersionedAPIPath("apps/WordCountApp/", Constants.Gateway.API_VERSION_3_TOKEN,
                                            TEST_NAMESPACE1));
    Assert.assertEquals(404, response.getStatusLine().getStatusCode());

    // Check metadata, should be empty
    properties = getProperties(program);
    Assert.assertEquals(0, properties.size());
  }

  private void removeAllMetadata() throws IOException {
    removeMetadata(application);
    removeMetadata(pingService);
    removeMetadata(myds);
    removeMetadata(mystream);
  }

  private void assertCleanState() throws IOException {
    // Assert clean state
    Set<MetadataRecord> appMetadatas = getMetadata(application);
    // only user metadata right now.
    Assert.assertEquals(1, appMetadatas.size());
    MetadataRecord appMetadata = appMetadatas.iterator().next();
    Assert.assertTrue(appMetadata.getProperties().isEmpty());
    Assert.assertTrue(appMetadata.getTags().isEmpty());
    Set<MetadataRecord> serviceMetadatas = getMetadata(pingService);
    // only user metadata right now.
    Assert.assertEquals(1, serviceMetadatas.size());
    MetadataRecord serviceMetadata = serviceMetadatas.iterator().next();
    Assert.assertTrue(serviceMetadata.getProperties().isEmpty());
    Assert.assertTrue(serviceMetadata.getTags().isEmpty());
    Set<MetadataRecord> datasetMetadatas = getMetadata(myds);
    // only user metadata right now.
    Assert.assertEquals(1, datasetMetadatas.size());
    MetadataRecord datasetMetadata = datasetMetadatas.iterator().next();
    Assert.assertTrue(datasetMetadata.getProperties().isEmpty());
    Assert.assertTrue(datasetMetadata.getTags().isEmpty());
    Set<MetadataRecord> streamMetadatas = getMetadata(mystream);
    // only user metadata right now.
    Assert.assertEquals(1, streamMetadatas.size());
    MetadataRecord streamMetadata = streamMetadatas.iterator().next();
    Assert.assertTrue(streamMetadata.getProperties().isEmpty());
    Assert.assertTrue(streamMetadata.getTags().isEmpty());
  }

  private HttpResponse addProperties(Id.Application app) throws IOException {
    return addProperties(app, null);
  }

  private HttpResponse addProperties(Id.Program program) throws IOException {
    return addProperties(program, null);
  }

  private HttpResponse addProperties(Id.DatasetInstance dataset) throws IOException {
    return addProperties(dataset, null);
  }

  private HttpResponse addProperties(Id.Stream stream) throws IOException {
    return addProperties(stream, null);
  }

  private HttpResponse addTags(Id.Application app) throws IOException {
    return addTags(app, null);
  }

  private HttpResponse addTags(Id.Program program) throws IOException {
    return addTags(program, null);
  }

  private HttpResponse addTags(Id.DatasetInstance dataset) throws IOException {
    return addTags(dataset, null);
  }

  private HttpResponse addTags(Id.Stream stream) throws IOException {
    return addTags(stream, null);
  }
}