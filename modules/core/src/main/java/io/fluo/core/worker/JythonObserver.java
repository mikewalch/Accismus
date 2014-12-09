/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluo.core.worker;

import io.fluo.api.client.TransactionBase;
import io.fluo.api.config.ObserverConfiguration;
import io.fluo.api.data.Bytes;
import io.fluo.api.data.Column;
import io.fluo.api.observer.AbstractObserver;
import io.fluo.core.impl.Environment;
import org.python.util.PythonInterpreter;

/**
 * Java utility that calls underlying Jython observer
 */
public class JythonObserver extends AbstractObserver {
  
  private PythonInterpreter interp = new PythonInterpreter();
  private Environment env;
  
  public JythonObserver(Environment env, ObserverConfiguration config) {
    this.env = env;
  }
  
  @Override
  public void init(Context context) throws Exception {
    
  }

  @Override
  public void close() {
    
  }

  @Override
  public void process(TransactionBase tx, Bytes row, Column col) throws Exception {
    // TODO Auto-generated method stub
  }

  @Override
  public ObservedColumn getObservedColumn() {
    // TODO Auto-generated method stub
    return null;
  }
}
