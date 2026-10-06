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

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;
import org.eclipse.aether.util.graph.selector.AndDependencySelector;
import org.eclipse.aether.util.graph.selector.ExclusionDependencySelector;
import org.eclipse.aether.util.graph.selector.OptionalDependencySelector;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.eclipse.aether.util.graph.transformer.JavaScopeDeriver;
import org.eclipse.aether.util.graph.transformer.JavaScopeSelector;
import org.eclipse.aether.util.graph.transformer.NearestVersionSelector;
import org.eclipse.aether.util.graph.transformer.SimpleOptionalitySelector;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the dependency graph of a project on the Maven and Resolver APIs, the way maven-dependency-tree's
 * {@code DependencyGraphBuilder} and {@code DependencyCollectorBuilder} did, which this replaces now that
 * maven-dependency-tree is to be retired.
 */
@Named
@Singleton
public class DependencyGraphBuilder {

    private final ProjectDependenciesResolver projectDependenciesResolver;

    private final RepositorySystem repositorySystem;

    @Inject
    public DependencyGraphBuilder(final ProjectDependenciesResolver projectDependenciesResolver, final RepositorySystem repositorySystem) {
        this.projectDependenciesResolver = Objects.requireNonNull(projectDependenciesResolver, "projectDependenciesResolver");
        this.repositorySystem = Objects.requireNonNull(repositorySystem, "repositorySystem");
    }

    /**
     * Builds the graph of the project of the request after conflict resolution, as Maven resolves it for the build.
     * Only POMs are downloaded, no artifact files.
     *
     * @param buildingRequest the request whose project and repository session to use
     * @param filter the artifacts to keep with their subtrees, or {@code null} for all
     * @return the root node, which stands for the project
     * @throws DependencyGraphException if the dependencies cannot be collected
     */
    public DependencyNode buildDependencyGraph(final ProjectBuildingRequest buildingRequest, final ArtifactFilter filter) throws DependencyGraphException {
        final MavenProject project = buildingRequest.getProject();
        final DependencyResolutionRequest request = new DefaultDependencyResolutionRequest(project, buildingRequest.getRepositorySession());
        // only download the POMs, not the artifacts
        request.setResolutionFilter((node, parents) -> false);
        try {
            return toNode(null, projectDependenciesResolver.resolve(request).getDependencyGraph(), project.getArtifact(), filter);
        } catch (final DependencyResolutionException e) {
            throw new DependencyGraphException("Could not resolve following dependencies: " + e.getResult().getUnresolvedDependencies(), e);
        }
    }

    /**
     * Collects the verbose graph of the project of the request: dependencies that lose a version conflict stay in it,
     * without their own dependencies, and transitive test and provided dependencies are left out.
     *
     * @param buildingRequest the request whose project and repository session to use
     * @param filter the artifacts to keep with their subtrees, or {@code null} for all
     * @return the root node, which stands for the project
     * @throws DependencyGraphException if the dependencies cannot be collected
     */
    public DependencyNode collectDependencyGraph(final ProjectBuildingRequest buildingRequest, final ArtifactFilter filter) throws DependencyGraphException {
        final MavenProject project = buildingRequest.getProject();
        final DefaultRepositorySystemSession session = new DefaultRepositorySystemSession(buildingRequest.getRepositorySession());
        session.setDependencySelector(new AndDependencySelector(
                new DirectScopeDependencySelector(JavaScopes.TEST),
                new DirectScopeDependencySelector(JavaScopes.PROVIDED),
                new OptionalDependencySelector(),
                new ExclusionDependencySelector()));
        session.setDependencyGraphTransformer(new ConflictResolver(
                new NearestVersionSelector(), new JavaScopeSelector(), new SimpleOptionalitySelector(), new JavaScopeDeriver()));
        session.setConfigProperty(ConflictResolver.CONFIG_PROP_VERBOSE, true);
        session.setConfigProperty(DependencyManagerUtils.CONFIG_PROP_VERBOSE, true);

        final ArtifactTypeRegistry stereotypes = session.getArtifactTypeRegistry();
        final CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRootArtifact(RepositoryUtils.toArtifact(project.getArtifact()));
        collectRequest.setRepositories(RepositoryUtils.toRepos(project.getRemoteArtifactRepositories()));
        for (final org.apache.maven.model.Dependency dependency : project.getDependencies()) {
            collectRequest.addDependency(RepositoryUtils.toDependency(dependency, stereotypes));
        }
        if (project.getDependencyManagement() != null) {
            for (final org.apache.maven.model.Dependency dependency : project.getDependencyManagement().getDependencies()) {
                collectRequest.addManagedDependency(RepositoryUtils.toDependency(dependency, stereotypes));
            }
        }

        try {
            return toNode(null, repositorySystem.collectDependencies(session, collectRequest).getRoot(), project.getArtifact(), filter);
        } catch (final DependencyCollectionException e) {
            throw new DependencyGraphException("Could not collect dependencies: " + e.getResult(), e);
        } finally {
            session.setReadOnly();
        }
    }

    private static DependencyNode toNode(final DependencyNode parent, final org.eclipse.aether.graph.DependencyNode node, final Artifact artifact, final ArtifactFilter filter) {
        final DependencyNode current = new DependencyNode(parent, artifact);
        final List<DependencyNode> children = new ArrayList<>(node.getChildren().size());
        for (final org.eclipse.aether.graph.DependencyNode child : node.getChildren()) {
            final Artifact childArtifact = toArtifact(child.getDependency());
            if (filter == null || filter.include(childArtifact)) {
                children.add(toNode(current, child, childArtifact, filter));
            }
        }
        current.setChildren(children);
        return current;
    }

    private static Artifact toArtifact(final Dependency dependency) {
        final Artifact artifact = RepositoryUtils.toArtifact(dependency.getArtifact());
        artifact.setScope(dependency.getScope());
        artifact.setOptional(dependency.isOptional());
        return artifact;
    }
}
