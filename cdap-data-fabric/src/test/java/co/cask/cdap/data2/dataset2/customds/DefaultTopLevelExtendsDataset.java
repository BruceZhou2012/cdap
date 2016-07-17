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

package co.cask.cdap.data2.dataset2.customds;

import co.cask.cdap.api.annotation.NoAccess;
import co.cask.cdap.api.annotation.ReadOnly;
import co.cask.cdap.api.annotation.ReadWrite;
import co.cask.cdap.api.annotation.WriteOnly;

import java.io.IOException;

/**
 *
 */
public class DefaultTopLevelExtendsDataset implements TopLevelExtendsDataset, CustomOperations {

  @Override
  public void close() throws IOException {
    // no-op
  }

  @ReadOnly
  @Override
  public void read() {
  }

  @WriteOnly
  @Override
  public void write() {
  }

  @ReadWrite
  @Override
  public void readWrite() {
    read();
    write();
  }

  @NoAccess
  @Override
  public void invalidRead() {
    read();
  }

  @NoAccess
  @Override
  public void invalidWrite() {
    write();
  }

  @WriteOnly
  @Override
  public void invalidReadFromWriteOnly() {
    read();
  }

  @ReadOnly
  @Override
  public void invalidWriteFromReadOnly() {
    write();
  }
}
