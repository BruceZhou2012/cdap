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

package co.cask.cdap.security.auth.context;

import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.security.Principal;
import co.cask.cdap.security.spi.authentication.AuthenticationContext;
import co.cask.cdap.security.spi.authentication.SecurityRequestContext;
import com.google.common.base.Throwables;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.apache.hadoop.security.UserGroupInformation;

import java.io.IOException;

/**
 * An {@link AuthenticationContext} to be used while accessing datasets through an internal dataset client.
 *
 * <p>
 *  If the request originated from the router and was forwarded to any service other than dataset service, before
 *  being routed to dataset service via dataset service client, the userId could be set in the SecurityRequestContext.
 *  e.g. deploying an app that contains a dataset. In this case, {@link HttpAuthenticationContext} will be injected to
 *  this context {@link Named} as {@code basic-authentication-context}.
 *
 *  For user datasets, if a dataset call originated from a program runtime, then find the userId from
 *  {@link UserGroupInformation#getCurrentUser()} via {@link ProgramContainerAuthenticationContext} injected into this
 *  context {@link Named} as {@code basic-authentication-context}.
 *
 *  For getting a system dataset like MDS, this will override the injected {@link AuthenticationContext} to always
 *  return {@link Principal#SYSTEM}. It is ok to do so, since this {@link AuthenticationContext} will only be used in
 *  an internal client that is not exposed to users.
 * </p>
 */
public class SystemDatasetAuthenticationContext implements AuthenticationContext {
  private final AuthenticationContext authenticationContext;

  @Inject
  SystemDatasetAuthenticationContext(
    @Named("basic-authentication-context") AuthenticationContext authenticationContext) {
    this.authenticationContext = authenticationContext;
  }

  @Override
  public Principal getRequestingPrincipal() {

    return null;
    /*String userId = SecurityRequestContext.getUserId();
    if (NamespaceId.SYSTEM.equals(namespaceId.toEntityId())) {
      userId = Principal.SYSTEM.getName();
    } else if (userId == null) {
      //
      try {
        userId = UserGroupInformation.getCurrentUser().getShortUserName();
      } catch (IOException e) {
        throw Throwables.propagate(e);
      }
    }*/
  }
}
