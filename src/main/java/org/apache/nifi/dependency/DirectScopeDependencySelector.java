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
 */
package org.apache.nifi.dependency;

import org.eclipse.aether.collection.DependencyCollectionContext;
import org.eclipse.aether.collection.DependencySelector;
import org.eclipse.aether.graph.Dependency;

import java.util.Objects;

/**
 * Selects every direct dependency, and a transitive one only if it does not have the given scope.
 * As in maven-dependency-tree, which used it for its verbose graph.
 */
final class DirectScopeDependencySelector implements DependencySelector {

    private final String scope;

    private final int depth;

    DirectScopeDependencySelector(final String scope) {
        this(scope, 0);
    }

    private DirectScopeDependencySelector(final String scope, final int depth) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.depth = depth;
    }

    @Override
    public boolean selectDependency(final Dependency dependency) {
        return depth < 2 || !scope.equals(dependency.getScope());
    }

    @Override
    public DependencySelector deriveChildSelector(final DependencyCollectionContext context) {
        if (depth >= 2) {
            return this;
        }
        return new DirectScopeDependencySelector(scope, depth + 1);
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DirectScopeDependencySelector)) {
            return false;
        }
        final DirectScopeDependencySelector other = (DirectScopeDependencySelector) obj;
        return depth == other.depth && scope.equals(other.scope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, depth);
    }
}
