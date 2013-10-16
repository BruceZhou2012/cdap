package com.continuuity.internal.app.runtime.webapp;

import com.continuuity.app.program.ManifestFields;
import com.continuuity.app.program.Program;
import com.continuuity.app.program.Type;
import com.continuuity.app.runtime.ProgramController;
import com.continuuity.app.runtime.ProgramOptions;
import com.continuuity.app.runtime.ProgramRunner;
import com.continuuity.common.conf.Constants;
import com.continuuity.common.http.core.NettyHttpService;
import com.continuuity.common.utils.Networks;
import com.continuuity.weave.api.RunId;
import com.continuuity.weave.api.ServiceAnnouncer;
import com.continuuity.weave.discovery.DiscoveryServiceClient;
import com.continuuity.weave.internal.RunIds;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

/**
 * Run Webapp server.
 */
public class WebappProgramRunner implements ProgramRunner {
  private static final Logger LOG = LoggerFactory.getLogger(WebappProgramRunner.class);

  private final ServiceAnnouncer serviceAnnouncer;
  private final InetAddress hostname;
  private final WebappHttpHandlerFactory handlerFactory;
  private final DiscoveryServiceClient discoveryServiceClient;

  @Inject
  public WebappProgramRunner(ServiceAnnouncer serviceAnnouncer,
                             @Named(Constants.AppFabric.SERVER_ADDRESS) InetAddress hostname,
                             WebappHttpHandlerFactory handlerFactory, DiscoveryServiceClient discoveryServiceClient) {
    this.serviceAnnouncer = serviceAnnouncer;
    this.hostname = hostname;
    this.handlerFactory = handlerFactory;
    this.discoveryServiceClient = discoveryServiceClient;
  }

  @Override
  public ProgramController run(Program program, ProgramOptions options) {
    try {

      Type processorType = program.getType();
      Preconditions.checkNotNull(processorType, "Missing processor type.");
      Preconditions.checkArgument(processorType == Type.WEBAPP, "Only WEBAPP process type is supported.");

      LOG.info("Initializing web app for app {} with jar {}", program.getApplicationId(),
               program.getJarLocation().getName());

      // Generate the service name using manifest information.
      String serviceName = getServiceName(program.getJarLocation().getInputStream(),
                                          Type.WEBAPP, program);
      Preconditions.checkNotNull(serviceName, "Cannot determine service name for program %s", program.getName());
      LOG.info("Got service name {}", serviceName);

      // Start netty server
      // TODO: add metrics reporting
      NettyHttpService.Builder builder = NettyHttpService.builder();
      builder.addHttpHandlers(ImmutableList.of(handlerFactory.createHandler(program.getJarLocation()),
                                               new GatewayForwardingHandler(discoveryServiceClient)));
      builder.setHost(hostname.getCanonicalHostName());
      NettyHttpService httpService = builder.build();
      httpService.startAndWait();
      InetSocketAddress address = httpService.getBindAddress();

      RunId runId = RunIds.generate();
      LOG.info("Webapp running on address {} registering as {}", address, serviceName);

      return new WebappProgramController(program.getName(), runId, httpService,
                                         serviceAnnouncer.announce(serviceName, address.getPort()));

    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  public static String getServiceName(InputStream jarInputStream, Type type, Program program) throws Exception {
    try {
      JarInputStream jarInput = new JarInputStream(jarInputStream);
      Manifest manifest = jarInput.getManifest();
      String host = manifest.getMainAttributes().getValue(ManifestFields.WEBAPP_HOST);
      host = Networks.normalizeHost(host);

      return String.format("%s.%s.%s.%s", type.name().toLowerCase(),
                           program.getAccountId(), program.getApplicationId(), host);
    } finally {
      jarInputStream.close();
    }
  }
}
