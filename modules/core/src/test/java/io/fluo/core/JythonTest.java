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
package io.fluo.core;

import io.fluo.api.config.FluoConfiguration;
import org.junit.Test;
import org.python.core.PyInteger;
import org.python.core.PyObject;
import org.python.util.PythonInterpreter;

public class JythonTest {
  
  @Test
  public void myTest() {

    // Create an instance of the PythonInterpreter
    PythonInterpreter interp = new PythonInterpreter();

    // The exec() method executes strings of code
    interp.exec("import sys");
    interp.exec("print sys");

    // Set variable values within the PythonInterpreter instance
    interp.set("a", new PyInteger(42));
    FluoConfiguration config = new FluoConfiguration();
    config.setAccumuloTable("moose");
    System.out.println("outside - "+config.getAccumuloTable());
    
    interp.set("config", config);
    
    interp.exec("print a");
    interp.exec("x = 2 + '2'");
    interp.exec("print x");
    interp.exec("print config.getAccumuloTable()");
    interp.exec("config.setAccumuloTable('cow')");
    interp.exec("print config.getAccumuloTable()");
    
    System.out.println("outside - "+config.getAccumuloTable());
        
    // Obtain the value of an object from the PythonInterpreter and store it
    // into a PyObject.
    PyObject x = interp.get("x");
    System.out.println("x: " + x);
  }

}
