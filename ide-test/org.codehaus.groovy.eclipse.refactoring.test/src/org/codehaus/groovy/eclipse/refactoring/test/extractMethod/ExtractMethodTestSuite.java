/*
 * Copyright (C) 2007, 2009 Martin Kempf, Reto Kleeb, Michael Klenk
 *
 * IFS Institute for Software, HSR Rapperswil, Switzerland
 * http://ifs.hsr.ch/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.eclipse.refactoring.test.extractMethod;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

import org.codehaus.groovy.eclipse.refactoring.test.BaseTestSuite;

import junit.framework.TestSuite;

public class ExtractMethodTestSuite extends BaseTestSuite {

    public static TestSuite suite() throws FileNotFoundException, IOException {
        TestSuite ts = new TestSuite("Extract Method Suite");
        List<File> files = getFileList("/ExtractMethod", "ExtractMethod_Test_");
        for (File file : files) {
//            if (file.getName().contains("Closure_with_implicit"))
            ts.addTest(new ExtractMethodTestCase(file.getName(),file));
        }
        return ts;
    }
}
