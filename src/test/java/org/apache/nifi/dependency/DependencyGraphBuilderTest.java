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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.collection.DependencyCollectionContext;
import org.eclipse.aether.collection.DependencySelector;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;
import org.eclipse.aether.util.graph.selector.AndDependencySelector;
import org.eclipse.aether.util.graph.selector.ExclusionDependencySelector;
import org.eclipse.aether.util.graph.selector.OptionalDependencySelector;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DependencyGraphBuilderTest {

    @Mock private ProjectDependenciesResolver projectDependenciesResolver;
    @Mock private RepositorySystem repositorySystem;
    @Mock private DependencyResolutionResult resolutionResult;

    private MavenProject project;
    private ProjectBuildingRequest buildingRequest;
    private DependencyGraphBuilder builder;

    @BeforeEach
    void setUp() {
        project = new MavenProject();
        project.setGroupId("org.example");
        project.setArtifactId("sample-nar");
        project.setVersion("1.0");
        project.setArtifact(mavenArtifact("org.example", "sample-nar", "1.0", "nar", null));
        project.setRemoteArtifactRepositories(Collections.emptyList());
        buildingRequest = new DefaultProjectBuildingRequest();
        buildingRequest.setProject(project);
        buildingRequest.setRepositorySession(new DefaultRepositorySystemSession());
        builder = new DependencyGraphBuilder(projectDependenciesResolver, repositorySystem);
    }

    @Test
    void buildsTheResolvedGraphWithTheProjectArtifactAsRoot() throws Exception {
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class))).thenReturn(resolutionResult);
        when(resolutionResult.getDependencyGraph()).thenReturn(sampleGraph());

        final DependencyNode root = builder.buildDependencyGraph(buildingRequest, null);

        assertSame(project.getArtifact(), root.getArtifact());
        assertNull(root.getParent());
        assertEquals(2, root.getChildren().size());
        final DependencyNode api = root.getChildren().get(0);
        assertEquals("org.example:api:jar:1.0:compile", api.getArtifact().toString());
        assertSame(root, api.getParent());
        final DependencyNode util = api.getChildren().get(0);
        assertEquals("org.example:util:jar:2.0:runtime", util.getArtifact().toString());
        final DependencyNode optional = root.getChildren().get(1);
        assertTrue(optional.getArtifact().isOptional());
        assertEquals("org.example:extra:jar:tests:3.0:test (optional)", optional.toNodeString());

        final ArgumentCaptor<DependencyResolutionRequest> request = ArgumentCaptor.forClass(DependencyResolutionRequest.class);
        verify(projectDependenciesResolver).resolve(request.capture());
        assertSame(project, request.getValue().getMavenProject());
        assertFalse(request.getValue().getResolutionFilter().accept(new DefaultDependencyNode(dependency("org.example", "api", "1.0", "compile", false)), Collections.emptyList()),
                "only the POMs are resolved, no artifact files");
    }

    @Test
    void filterDropsAnArtifactWithItsSubtree() throws Exception {
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class))).thenReturn(resolutionResult);
        when(resolutionResult.getDependencyGraph()).thenReturn(sampleGraph());
        final ArtifactFilter withoutApi = artifact -> !"api".equals(artifact.getArtifactId());

        final DependencyNode root = builder.buildDependencyGraph(buildingRequest, withoutApi);

        assertEquals(1, root.getChildren().size());
        assertEquals("extra", root.getChildren().get(0).getArtifact().getArtifactId());
    }

    @Test
    void resolutionFailureNamesTheUnresolvedDependencies() throws Exception {
        final DependencyResolutionResult result = mock(DependencyResolutionResult.class);
        when(result.getUnresolvedDependencies()).thenReturn(Collections.singletonList(dependency("org.example", "missing", "1.0", "compile", false)));
        final DependencyResolutionException failure = new DependencyResolutionException(result, "failed", null);
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class))).thenThrow(failure);

        final DependencyGraphException e = assertThrows(DependencyGraphException.class, () -> builder.buildDependencyGraph(buildingRequest, null));
        assertTrue(e.getMessage().startsWith("Could not resolve following dependencies: [org.example:missing:jar:1.0"), e.getMessage());
    }

    @Test
    void collectsTheVerboseGraphOfTheProjectDependencies() throws Exception {
        final Dependency direct = new Dependency();
        direct.setGroupId("org.example");
        direct.setArtifactId("api");
        direct.setVersion("1.0");
        project.getDependencies().add(direct);
        final ArgumentCaptor<RepositorySystemSession> session = ArgumentCaptor.forClass(RepositorySystemSession.class);
        final ArgumentCaptor<CollectRequest> collectRequest = ArgumentCaptor.forClass(CollectRequest.class);
        final CollectResult collectResult = new CollectResult(new CollectRequest());
        collectResult.setRoot(sampleGraph());
        when(repositorySystem.collectDependencies(session.capture(), collectRequest.capture())).thenReturn(collectResult);

        final DependencyNode root = builder.collectDependencyGraph(buildingRequest, null);

        assertSame(project.getArtifact(), root.getArtifact());
        assertEquals(2, root.getChildren().size());
        assertEquals(Boolean.TRUE, session.getValue().getConfigProperties().get(ConflictResolver.CONFIG_PROP_VERBOSE));
        assertEquals(Boolean.TRUE, session.getValue().getConfigProperties().get(DependencyManagerUtils.CONFIG_PROP_VERBOSE));
        assertTrue(session.getValue().getDependencyGraphTransformer() instanceof ConflictResolver);
        assertEquals(new AndDependencySelector(
                new DirectScopeDependencySelector("test"),
                new DirectScopeDependencySelector("provided"),
                new OptionalDependencySelector(),
                new ExclusionDependencySelector()), session.getValue().getDependencySelector());
        assertEquals("org.example:sample-nar:nar:1.0", collectRequest.getValue().getRootArtifact().toString());
        assertEquals(1, collectRequest.getValue().getDependencies().size());
        assertEquals("org.example:api:jar:1.0", collectRequest.getValue().getDependencies().get(0).getArtifact().toString());
        assertTrue(collectRequest.getValue().getManagedDependencies().isEmpty());
    }

    @Test
    void collectRequestCarriesTheManagedDependencies() throws Exception {
        final Dependency managed = new Dependency();
        managed.setGroupId("org.example");
        managed.setArtifactId("util");
        managed.setVersion("2.5");
        final DependencyManagement dependencyManagement = new DependencyManagement();
        dependencyManagement.addDependency(managed);
        project.getModel().setDependencyManagement(dependencyManagement);
        final ArgumentCaptor<CollectRequest> collectRequest = ArgumentCaptor.forClass(CollectRequest.class);
        final CollectResult collectResult = new CollectResult(new CollectRequest());
        collectResult.setRoot(sampleGraph());
        when(repositorySystem.collectDependencies(any(RepositorySystemSession.class), collectRequest.capture())).thenReturn(collectResult);

        builder.collectDependencyGraph(buildingRequest, null);

        assertEquals(1, collectRequest.getValue().getManagedDependencies().size());
        assertEquals("org.example:util:jar:2.5", collectRequest.getValue().getManagedDependencies().get(0).getArtifact().toString());
    }

    @Test
    void collectFilterDropsAnArtifactWithItsSubtree() throws Exception {
        final CollectResult collectResult = new CollectResult(new CollectRequest());
        collectResult.setRoot(sampleGraph());
        when(repositorySystem.collectDependencies(any(RepositorySystemSession.class), any(CollectRequest.class))).thenReturn(collectResult);

        final DependencyNode root = builder.collectDependencyGraph(buildingRequest, artifact -> !"api".equals(artifact.getArtifactId()));

        assertEquals(1, root.getChildren().size());
        assertEquals("extra", root.getChildren().get(0).getArtifact().getArtifactId());
    }

    @Test
    void collectionFailureIsReported() throws Exception {
        final CollectResult collectResult = new CollectResult(new CollectRequest());
        final DependencyCollectionException failure = new DependencyCollectionException(collectResult, "failed");
        when(repositorySystem.collectDependencies(any(RepositorySystemSession.class), any(CollectRequest.class))).thenThrow(failure);

        final DependencyGraphException e = assertThrows(DependencyGraphException.class, () -> builder.collectDependencyGraph(buildingRequest, null));
        assertTrue(e.getMessage().startsWith("Could not collect dependencies: "), e.getMessage());
        assertSame(failure, e.getCause());
    }

    @Test
    void acceptVisitsDepthFirstAndSkipsChildrenWhenVisitReturnsFalse() throws Exception {
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class))).thenReturn(resolutionResult);
        when(resolutionResult.getDependencyGraph()).thenReturn(sampleGraph());
        final DependencyNode root = builder.buildDependencyGraph(buildingRequest, null);
        final List<String> events = new ArrayList<>();

        root.accept(new DependencyNodeVisitor() {
            @Override
            public boolean visit(final DependencyNode node) {
                events.add("visit " + node.getArtifact().getArtifactId());
                // do not descend into api
                return !"api".equals(node.getArtifact().getArtifactId());
            }

            @Override
            public boolean endVisit(final DependencyNode node) {
                events.add("end " + node.getArtifact().getArtifactId());
                return true;
            }
        });

        assertEquals(List.of("visit sample-nar", "visit api", "end api", "visit extra", "end extra", "end sample-nar"), events);
    }

    @Test
    void endVisitReturningFalseStopsTheSiblings() throws Exception {
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class))).thenReturn(resolutionResult);
        when(resolutionResult.getDependencyGraph()).thenReturn(sampleGraph());
        final DependencyNode root = builder.buildDependencyGraph(buildingRequest, null);
        final List<String> visited = new ArrayList<>();

        root.accept(new DependencyNodeVisitor() {
            @Override
            public boolean visit(final DependencyNode node) {
                visited.add(node.getArtifact().getArtifactId());
                return true;
            }

            @Override
            public boolean endVisit(final DependencyNode node) {
                // stop after the subtree of api, so extra is not visited
                return !"api".equals(node.getArtifact().getArtifactId());
            }
        });

        assertEquals(List.of("sample-nar", "api", "util"), visited);
    }

    @Test
    void directScopeSelectorKeepsDirectDependenciesOfTheScope() {
        final DependencySelector root = new DirectScopeDependencySelector("test");
        final DependencyCollectionContext context = mock(DependencyCollectionContext.class);
        final DependencySelector direct = root.deriveChildSelector(context);
        final DependencySelector transitive = direct.deriveChildSelector(context);
        final org.eclipse.aether.graph.Dependency testDependency = dependency("org.example", "junit", "1.0", "test", false);

        assertTrue(direct.selectDependency(testDependency));
        assertFalse(transitive.selectDependency(testDependency));
        assertTrue(transitive.selectDependency(dependency("org.example", "api", "1.0", "compile", false)));
        assertSame(transitive, transitive.deriveChildSelector(context));
        assertEquals(transitive, direct.deriveChildSelector(context));
    }

    /**
     * root -> api:1.0 (compile) -> util:2.0 (runtime); root -> extra:tests:3.0 (test, optional)
     */
    private static DefaultDependencyNode sampleGraph() {
        final DefaultDependencyNode root = new DefaultDependencyNode(new org.eclipse.aether.artifact.DefaultArtifact("org.example:sample-nar:nar:1.0"));
        final DefaultDependencyNode api = new DefaultDependencyNode(dependency("org.example", "api", "1.0", "compile", false));
        api.setChildren(new ArrayList<>(List.of(new DefaultDependencyNode(dependency("org.example", "util", "2.0", "runtime", false)))));
        final DefaultDependencyNode extra = new DefaultDependencyNode(new org.eclipse.aether.graph.Dependency(
                new org.eclipse.aether.artifact.DefaultArtifact("org.example", "extra", "tests", "jar", "3.0"), "test", true));
        root.setChildren(new ArrayList<>(List.of(api, extra)));
        return root;
    }

    private static org.eclipse.aether.graph.Dependency dependency(final String groupId, final String artifactId, final String version, final String scope, final boolean optional) {
        return new org.eclipse.aether.graph.Dependency(new org.eclipse.aether.artifact.DefaultArtifact(groupId, artifactId, "jar", version), scope, optional);
    }

    private static Artifact mavenArtifact(final String groupId, final String artifactId, final String version, final String type, final String scope) {
        return new DefaultArtifact(groupId, artifactId, version, scope, type, null, new DefaultArtifactHandler(type));
    }
}
