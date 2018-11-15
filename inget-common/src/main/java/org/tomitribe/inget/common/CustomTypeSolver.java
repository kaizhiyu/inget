/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */
package org.tomitribe.inget.common;

import com.github.javaparser.symbolsolver.model.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.File;

public class CustomTypeSolver {

    private CustomTypeSolver() {
        // utility class
    }

    private static final CombinedTypeSolver COMBINED_TYPE_SOLVER = new CombinedTypeSolver();

    public static void init() {
        COMBINED_TYPE_SOLVER.add(new ReflectionTypeSolver());
        COMBINED_TYPE_SOLVER.add(new JavaParserTypeSolver(new File(Configuration.modelSources)));
        COMBINED_TYPE_SOLVER.add(new JavaParserTypeSolver(new File(Configuration.resourceSources)));
        COMBINED_TYPE_SOLVER.add(new JavaParserTypeSolver(new File(Configuration.generatedSources)));
    }

    public static TypeSolver get() {
        return COMBINED_TYPE_SOLVER;
    }
}
