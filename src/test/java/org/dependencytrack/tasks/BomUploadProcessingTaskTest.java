/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) Steve Springett. All Rights Reserved.
 */
package org.dependencytrack.tasks;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.dependencytrack.PersistenceCapableTest;
import org.dependencytrack.event.BomUploadEvent;
import org.dependencytrack.event.kafka.KafkaEventDispatcher;
import org.dependencytrack.event.kafka.KafkaTopics;
import org.dependencytrack.model.Bom;
import org.dependencytrack.model.Classifier;
import org.dependencytrack.model.Component;
import org.dependencytrack.model.ConfigPropertyConstants;
import org.dependencytrack.model.FetchStatus;
import org.dependencytrack.model.IntegrityMetaComponent;
import org.dependencytrack.model.License;
import org.dependencytrack.model.Project;
import org.dependencytrack.model.VulnerabilityScan;
import org.dependencytrack.model.WorkflowStep;
import org.dependencytrack.persistence.DefaultObjectGenerator;
import org.dependencytrack.proto.notification.v1.BomProcessingFailedSubject;
import org.dependencytrack.proto.notification.v1.Group;
import org.dependencytrack.proto.notification.v1.Notification;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.apache.commons.io.IOUtils.resourceToURL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;
import static org.dependencytrack.assertion.Assertions.assertConditionWithTimeout;
import static org.dependencytrack.model.WorkflowStatus.CANCELLED;
import static org.dependencytrack.model.WorkflowStatus.COMPLETED;
import static org.dependencytrack.model.WorkflowStatus.FAILED;
import static org.dependencytrack.model.WorkflowStatus.NOT_APPLICABLE;
import static org.dependencytrack.model.WorkflowStatus.PENDING;
import static org.dependencytrack.model.WorkflowStep.BOM_CONSUMPTION;
import static org.dependencytrack.model.WorkflowStep.BOM_PROCESSING;
import static org.dependencytrack.model.WorkflowStep.METRICS_UPDATE;
import static org.dependencytrack.model.WorkflowStep.POLICY_EVALUATION;
import static org.dependencytrack.model.WorkflowStep.VULN_ANALYSIS;
import static org.dependencytrack.proto.notification.v1.Group.GROUP_BOM_PROCESSING_FAILED;
import static org.dependencytrack.proto.notification.v1.Level.LEVEL_ERROR;
import static org.dependencytrack.proto.notification.v1.Scope.SCOPE_PORTFOLIO;
import static org.dependencytrack.util.KafkaTestUtil.deserializeKey;
import static org.dependencytrack.util.KafkaTestUtil.deserializeValue;

public class BomUploadProcessingTaskTest extends PersistenceCapableTest {

    @Before
    public void before() throws Exception {
        super.before();
        // Enable processing of CycloneDX BOMs
        qm.createConfigProperty(ConfigPropertyConstants.ACCEPT_ARTIFACT_CYCLONEDX.getGroupName(),
                ConfigPropertyConstants.ACCEPT_ARTIFACT_CYCLONEDX.getPropertyName(), "true",
                ConfigPropertyConstants.ACCEPT_ARTIFACT_CYCLONEDX.getPropertyType(),
                ConfigPropertyConstants.ACCEPT_ARTIFACT_CYCLONEDX.getDescription());
    }

    @Test
    public void informTest() throws Exception {
        // Required for license resolution.
        DefaultObjectGenerator.loadDefaultLicenses();

        Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        final var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-1.xml"));
        qm.createWorkflowSteps(bomUploadEvent.getChainIdentifier());

        new BomUploadProcessingTask().inform(bomUploadEvent);
        assertConditionWithTimeout(() -> kafkaMockProducer.history().size() >= 5, Duration.ofSeconds(5));
        assertThat(kafkaMockProducer.history()).satisfiesExactly(
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_PROJECT_CREATED.name()),
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name()),
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.VULN_ANALYSIS_COMMAND.name()),
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.REPO_META_ANALYSIS_COMMAND.name()),
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name())
        );
        qm.getPersistenceManager().refresh(project);
        assertThat(project.getClassifier()).isEqualTo(Classifier.APPLICATION);
        assertThat(project.getLastBomImport()).isNotNull();
        assertThat(project.getLastBomImportFormat()).isEqualTo("CycloneDX 1.5");
        assertThat(project.getExternalReferences()).isNotNull();
        assertThat(project.getExternalReferences()).hasSize(4);
        assertThat(project.getSupplier()).satisfies(supplier -> {
            assertThat(supplier.getName()).isEqualTo("Foo Incorporated");
            assertThat(supplier.getUrls()).containsOnly("https://foo.bar.com");
            assertThat(supplier.getContacts()).satisfiesExactly(contact -> {
                assertThat(contact.getName()).isEqualTo("Foo Jr.");
                assertThat(contact.getEmail()).isEqualTo("foojr@bar.com");
                assertThat(contact.getPhone()).isEqualTo("123-456-7890");
            });
        });
        assertThat(project.getManufacturer()).satisfies(manufacturer -> {
            assertThat(manufacturer.getName()).isEqualTo("Foo Incorporated");
            assertThat(manufacturer.getUrls()).containsOnly("https://foo.bar.com");
            assertThat(manufacturer.getContacts()).satisfiesExactly(contact -> {
                assertThat(contact.getName()).isEqualTo("Foo Sr.");
                assertThat(contact.getEmail()).isEqualTo("foo@bar.com");
                assertThat(contact.getPhone()).isEqualTo("800-123-4567");
            });
        });

        assertThat(project.getMetadata()).isNotNull();
        assertThat(project.getMetadata().getAuthors()).satisfiesExactly(contact -> {
            assertThat(contact.getName()).isEqualTo("Author");
            assertThat(contact.getEmail()).isEqualTo("author@example.com");
            assertThat(contact.getPhone()).isEqualTo("123-456-7890");
        });
        assertThat(project.getMetadata().getSupplier()).satisfies(manufacturer -> {
            assertThat(manufacturer.getName()).isEqualTo("Foo Incorporated");
            assertThat(manufacturer.getUrls()).containsOnly("https://foo.bar.com");
            assertThat(manufacturer.getContacts()).satisfiesExactly(contact -> {
                assertThat(contact.getName()).isEqualTo("Foo Jr.");
                assertThat(contact.getEmail()).isEqualTo("foojr@bar.com");
                assertThat(contact.getPhone()).isEqualTo("123-456-7890");
            });
        });

        final List<Component> components = qm.getAllComponents(project);
        assertThat(components).hasSize(1);

        final Component component = components.get(0);
        assertThat(component.getAuthor()).isEqualTo("Sometimes this field is long because it is composed of a list of authors......................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................");
        assertThat(component.getPublisher()).isEqualTo("Example Incorporated");
        assertThat(component.getSupplier().getName()).isEqualTo("Foo Incorporated");
        assertThat(component.getGroup()).isEqualTo("com.example");
        assertThat(component.getName()).isEqualTo("xmlutil");
        assertThat(component.getVersion()).isEqualTo("1.0.0");
        assertThat(component.getDescription()).isEqualTo("A makebelieve XML utility library");
        assertThat(component.getCpe()).isEqualTo("cpe:/a:example:xmlutil:1.0.0");
        assertThat(component.getPurl().canonicalize()).isEqualTo("pkg:maven/com.example/xmlutil@1.0.0?download_url=https%3A%2F%2Fon-premises.url%2Frepository%2Fnpm%2F%40babel%2Fhelper-split-export-declaration%2Fhelper-split-export-declaration%2Fhelper-split-export-declaration%2Fhelper-split-export-declaration%2Fhelper-split-export-declaration-7.18.6.tgz");
        assertThat(component.getResolvedLicense()).isNotNull();
        assertThat(component.getResolvedLicense().getLicenseId()).isEqualTo("Apache-2.0");
        assertThat(component.getLicense()).isNull();
        assertThat(component.getLicenseUrl()).isEqualTo("https://www.apache.org/licenses/LICENSE-2.0.txt");

        assertThat(qm.getAllWorkflowStatesForAToken(bomUploadEvent.getChainIdentifier())).satisfiesExactlyInAnyOrder(
                state -> {
                    assertThat(state.getStep()).isEqualTo(BOM_CONSUMPTION);
                    assertThat(state.getStatus()).isEqualTo(COMPLETED);
                    assertThat(state.getStartedAt()).isNotNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    assertThat(state.getStep()).isEqualTo(BOM_PROCESSING);
                    assertThat(state.getStatus()).isEqualTo(COMPLETED);
                    assertThat(state.getStartedAt()).isNotNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    //vuln analysis has not been handled yet, so it will be in pending state
                    assertThat(state.getStep()).isEqualTo(VULN_ANALYSIS);
                    assertThat(state.getStatus()).isEqualTo(PENDING);
                    assertThat(state.getStartedAt()).isBefore(Date.from(Instant.now()));
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    //policy evaluation has not been handled yet, so it will be in pending state
                    assertThat(state.getStep()).isEqualTo(POLICY_EVALUATION);
                    assertThat(state.getStatus()).isEqualTo(PENDING);
                    assertThat(state.getParent()).isNotNull();
                    assertThat(state.getStartedAt()).isNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    //metrics update has not been handled yet, so it will be in pending state
                    assertThat(state.getStep()).isEqualTo(METRICS_UPDATE);
                    assertThat(state.getStatus()).isEqualTo(PENDING);
                    assertThat(state.getParent()).isNotNull();
                    assertThat(state.getStartedAt()).isNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                }
        );
        final VulnerabilityScan vulnerabilityScan = qm.getVulnerabilityScan(bomUploadEvent.getChainIdentifier().toString());
        assertThat(vulnerabilityScan).isNotNull();
        var workflowStatus = qm.getWorkflowStateByTokenAndStep(bomUploadEvent.getChainIdentifier(), WorkflowStep.VULN_ANALYSIS);
        assertThat(workflowStatus.getStartedAt()).isNotNull();
    }

    @Test
    public void informTestWithComponentAlreadyExistsForIntegrityCheck() throws Exception {
        // Required for license resolution.
        DefaultObjectGenerator.loadDefaultLicenses();

        Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        final var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-1.xml"));
        qm.createWorkflowSteps(bomUploadEvent.getChainIdentifier());
        var integrityMeta = new IntegrityMetaComponent();
        integrityMeta.setPurl("pkg:maven/com.example/xmlutil@1.0.0?download_url=https%3A%2F%2Fon-premises.url%2Frepository%2Fnpm%2F%40babel%2Fhelper-split-export-declaration%2Fhelper-split-export-declaration%2Fhelper-split-export-declaration%2Fhelper-split-export-declaration%2Fhelper-split-export-declaration-7.18.6.tgz");
        integrityMeta.setStatus(FetchStatus.IN_PROGRESS);
        integrityMeta.setLastFetch(Date.from(Instant.now().minus(2, ChronoUnit.HOURS)));
        qm.createIntegrityMetaComponent(integrityMeta);
        new BomUploadProcessingTask().inform(bomUploadEvent);
        assertConditionWithTimeout(() -> kafkaMockProducer.history().size() >= 5, Duration.ofSeconds(5));
        assertThat(kafkaMockProducer.history()).satisfiesExactly(
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_PROJECT_CREATED.name()),
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name()),
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.VULN_ANALYSIS_COMMAND.name()),
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.REPO_META_ANALYSIS_COMMAND.name()),
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name())
        );
        qm.getPersistenceManager().refresh(project);
        assertThat(project.getClassifier()).isEqualTo(Classifier.APPLICATION);
        assertThat(project.getLastBomImport()).isNotNull();
        assertThat(project.getLastBomImportFormat()).isEqualTo("CycloneDX 1.5");
        assertThat(project.getExternalReferences()).isNotNull();
        assertThat(project.getExternalReferences()).hasSize(4);

        final List<Component> components = qm.getAllComponents(project);
        assertThat(components).hasSize(1);

        final Component component = components.get(0);
        assertThat(component.getAuthor()).isEqualTo("Sometimes this field is long because it is composed of a list of authors......................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................................");
        assertThat(component.getPublisher()).isEqualTo("Example Incorporated");
        assertThat(component.getGroup()).isEqualTo("com.example");
        assertThat(component.getName()).isEqualTo("xmlutil");
        assertThat(component.getVersion()).isEqualTo("1.0.0");
        assertThat(component.getDescription()).isEqualTo("A makebelieve XML utility library");
        assertThat(component.getCpe()).isEqualTo("cpe:/a:example:xmlutil:1.0.0");
        assertThat(component.getPurl().canonicalize()).isEqualTo("pkg:maven/com.example/xmlutil@1.0.0?download_url=https%3A%2F%2Fon-premises.url%2Frepository%2Fnpm%2F%40babel%2Fhelper-split-export-declaration%2Fhelper-split-export-declaration%2Fhelper-split-export-declaration%2Fhelper-split-export-declaration%2Fhelper-split-export-declaration-7.18.6.tgz");
        assertThat(component.getResolvedLicense()).isNotNull();
        assertThat(component.getResolvedLicense().getLicenseId()).isEqualTo("Apache-2.0");
        assertThat(component.getLicense()).isNull();
        assertThat(component.getLicenseUrl()).isEqualTo("https://www.apache.org/licenses/LICENSE-2.0.txt");

        assertThat(qm.getAllWorkflowStatesForAToken(bomUploadEvent.getChainIdentifier())).satisfiesExactlyInAnyOrder(
                state -> {
                    assertThat(state.getStep()).isEqualTo(BOM_CONSUMPTION);
                    assertThat(state.getStatus()).isEqualTo(COMPLETED);
                    assertThat(state.getStartedAt()).isNotNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    assertThat(state.getStep()).isEqualTo(BOM_PROCESSING);
                    assertThat(state.getStatus()).isEqualTo(COMPLETED);
                    assertThat(state.getStartedAt()).isNotNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    //vuln analysis has not been handled yet, so it will be in pending state
                    assertThat(state.getStep()).isEqualTo(VULN_ANALYSIS);
                    assertThat(state.getStatus()).isEqualTo(PENDING);
                    assertThat(state.getStartedAt()).isBefore(Date.from(Instant.now()));
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    //policy evaluation has not been handled yet, so it will be in pending state
                    assertThat(state.getStep()).isEqualTo(POLICY_EVALUATION);
                    assertThat(state.getStatus()).isEqualTo(PENDING);
                    assertThat(state.getParent()).isNotNull();
                    assertThat(state.getStartedAt()).isNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    //metrics update has not been handled yet, so it will be in pending state
                    assertThat(state.getStep()).isEqualTo(METRICS_UPDATE);
                    assertThat(state.getStatus()).isEqualTo(PENDING);
                    assertThat(state.getParent()).isNotNull();
                    assertThat(state.getStartedAt()).isNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                }
        );
        final VulnerabilityScan vulnerabilityScan = qm.getVulnerabilityScan(bomUploadEvent.getChainIdentifier().toString());
        assertThat(vulnerabilityScan).isNotNull();
        var workflowStatus = qm.getWorkflowStateByTokenAndStep(bomUploadEvent.getChainIdentifier(), WorkflowStep.VULN_ANALYSIS);
        assertThat(workflowStatus.getStartedAt()).isNotNull();
    }

    @Test
    public void informWithEmptyBomTest() throws Exception {
        Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        final var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-empty.json"));
        qm.createWorkflowSteps(bomUploadEvent.getChainIdentifier());
        new BomUploadProcessingTask().inform(bomUploadEvent);
        assertConditionWithTimeout(() -> kafkaMockProducer.history().size() >= 3, Duration.ofSeconds(5));
        assertThat(kafkaMockProducer.history()).satisfiesExactly(
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_PROJECT_CREATED.name()),
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name()),
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name())
        );

        qm.getPersistenceManager().refresh(project);
        assertThat(project.getClassifier()).isNull();
        assertThat(project.getLastBomImport()).isNotNull();

        assertThat(qm.getAllWorkflowStatesForAToken(bomUploadEvent.getChainIdentifier())).satisfiesExactlyInAnyOrder(
                state -> {
                    assertThat(state.getStep()).isEqualTo(BOM_CONSUMPTION);
                    assertThat(state.getStatus()).isEqualTo(COMPLETED);
                    assertThat(state.getStartedAt()).isNotNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    assertThat(state.getStep()).isEqualTo(BOM_PROCESSING);
                    assertThat(state.getStatus()).isEqualTo(COMPLETED);
                    assertThat(state.getStartedAt()).isNotNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    assertThat(state.getStep()).isEqualTo(VULN_ANALYSIS);
                    assertThat(state.getStatus()).isEqualTo(NOT_APPLICABLE);
                    assertThat(state.getParent()).isNotNull();
                    assertThat(state.getStartedAt()).isNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    assertThat(state.getStep()).isEqualTo(POLICY_EVALUATION);
                    assertThat(state.getStatus()).isEqualTo(NOT_APPLICABLE);
                    assertThat(state.getParent()).isNotNull();
                    assertThat(state.getStartedAt()).isNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    assertThat(state.getStep()).isEqualTo(METRICS_UPDATE);
                    assertThat(state.getStatus()).isEqualTo(PENDING);
                    assertThat(state.getParent()).isNotNull();
                    assertThat(state.getStartedAt()).isNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                }
        );

        final List<Component> components = qm.getAllComponents(project);
        assertThat(components).isEmpty();
        final VulnerabilityScan vulnerabilityScan = qm.getVulnerabilityScan(bomUploadEvent.getChainIdentifier().toString());
        assertThat(vulnerabilityScan).isNull();
    }

    @Test
    public void informWithInvalidBomTest() throws Exception {
        Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);
        var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-invalid.json"));
        qm.createWorkflowSteps(bomUploadEvent.getChainIdentifier());
        new BomUploadProcessingTask().inform(bomUploadEvent);
        assertConditionWithTimeout(() -> kafkaMockProducer.history().size() >= 2, Duration.ofSeconds(5));
        assertThat(kafkaMockProducer.history()).satisfiesExactly(
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_PROJECT_CREATED.name()),
                event -> {
                    assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name());
                    final Notification notification = deserializeValue(KafkaTopics.NOTIFICATION_BOM, event);
                    assertThat(notification.getScope()).isEqualTo(SCOPE_PORTFOLIO);
                    assertThat(notification.getGroup()).isEqualTo(GROUP_BOM_PROCESSING_FAILED);
                    assertThat(notification.getLevel()).isEqualTo(LEVEL_ERROR);
                    assertThat(notification.getTitle()).isNotEmpty();
                    assertThat(notification.getContent()).isNotEmpty();
                    assertThat(notification.hasSubject()).isTrue();
                    assertThat(notification.getSubject().is(BomProcessingFailedSubject.class)).isTrue();
                    final var subject = notification.getSubject().unpack(BomProcessingFailedSubject.class);
                    assertThat(subject.hasProject()).isTrue();
                    assertThat(subject.getProject().getUuid()).isEqualTo(project.getUuid().toString());
                    assertThat(subject.getBom().getContent()).isEqualTo("(Omitted)");
                    assertThat(subject.getBom().getFormat()).isEqualTo("CycloneDX");
                    assertThat(subject.getBom().getSpecVersion()).isEmpty();
                }
        );

        qm.getPersistenceManager().refresh(project);

        assertThat(qm.getAllWorkflowStatesForAToken(bomUploadEvent.getChainIdentifier())).satisfiesExactlyInAnyOrder(
                state -> {
                    assertThat(state.getStep()).isEqualTo(BOM_CONSUMPTION);
                    assertThat(state.getStatus()).isEqualTo(FAILED);
                    assertThat(state.getFailureReason()).isEqualTo("Failed to parse BOM");
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    assertThat(state.getStep()).isEqualTo(BOM_PROCESSING);
                    assertThat(state.getStatus()).isEqualTo(CANCELLED);
                    assertThat(state.getStartedAt()).isNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    assertThat(state.getStep()).isEqualTo(VULN_ANALYSIS);
                    assertThat(state.getStatus()).isEqualTo(CANCELLED);
                    assertThat(state.getStartedAt()).isNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    assertThat(state.getStep()).isEqualTo(POLICY_EVALUATION);
                    assertThat(state.getStatus()).isEqualTo(CANCELLED);
                    assertThat(state.getParent()).isNotNull();
                    assertThat(state.getStartedAt()).isNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    assertThat(state.getStep()).isEqualTo(METRICS_UPDATE);
                    assertThat(state.getStatus()).isEqualTo(CANCELLED);
                    assertThat(state.getParent()).isNotNull();
                    assertThat(state.getStartedAt()).isNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                }
        );
        assertThat(project.getClassifier()).isNull();
        assertThat(project.getLastBomImport()).isNull();
        assertThat(project.getExternalReferences()).isNull();
        assertThat(project.getExternalReferences()).isNull();

        final List<Component> components = qm.getAllComponents(project);
        assertThat(components).isEmpty();
    }

    @Test
    public void testBomProcessingShouldFailIfProjectDoesNotExists() throws Exception {
        //project should not be persisted for this test condition
        Project project = new Project();
        project.setUuid(UUID.randomUUID());
        project.setName("test-project");
        project.setId(1);
        var bomUploadEvent = new BomUploadEvent(project, createTempBomFile("bom-1.xml"));
        qm.createWorkflowSteps(bomUploadEvent.getChainIdentifier());
        new BomUploadProcessingTask().inform(bomUploadEvent);

        assertThat(qm.getAllWorkflowStatesForAToken(bomUploadEvent.getChainIdentifier())).satisfiesExactlyInAnyOrder(
                state -> {
                    assertThat(state.getStep()).isEqualTo(BOM_CONSUMPTION);
                    assertThat(state.getStatus()).isEqualTo(COMPLETED);
                    assertThat(state.getStartedAt()).isNotNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    assertThat(state.getStep()).isEqualTo(BOM_PROCESSING);
                    assertThat(state.getStatus()).isEqualTo(FAILED);
                    assertThat(state.getStartedAt()).isNotNull();
                    assertThat(state.getFailureReason()).isEqualTo("Project does not exist");
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    assertThat(state.getStep()).isEqualTo(VULN_ANALYSIS);
                    assertThat(state.getStatus()).isEqualTo(CANCELLED);
                    assertThat(state.getStartedAt()).isNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    assertThat(state.getStep()).isEqualTo(POLICY_EVALUATION);
                    assertThat(state.getStatus()).isEqualTo(CANCELLED);
                    assertThat(state.getParent()).isNotNull();
                    assertThat(state.getStartedAt()).isNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                },
                state -> {
                    assertThat(state.getStep()).isEqualTo(METRICS_UPDATE);
                    assertThat(state.getStatus()).isEqualTo(CANCELLED);
                    assertThat(state.getParent()).isNotNull();
                    assertThat(state.getStartedAt()).isNull();
                    assertThat(state.getUpdatedAt()).isBefore(Date.from(Instant.now()));
                }
        );
    }

    @Test
    public void informWithBloatedBomTest() throws Exception {
        final var project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        final var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-bloated.json"));
        new BomUploadProcessingTask().inform(bomUploadEvent);

        assertThat(kafkaMockProducer.history())
                .anySatisfy(record -> {
                    assertThat(deserializeKey(KafkaTopics.NOTIFICATION_BOM, record)).isEqualTo(project.getUuid().toString());
                    assertThat(record.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name());
                    final Notification notification = deserializeValue(KafkaTopics.NOTIFICATION_BOM, record);
                    assertThat(notification.getGroup()).isEqualTo(Group.GROUP_BOM_CONSUMED);
                })
                .anySatisfy(record -> {
                    assertThat(deserializeKey(KafkaTopics.NOTIFICATION_BOM, record)).isEqualTo(project.getUuid().toString());
                    assertThat(record.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name());
                    final Notification notification = deserializeValue(KafkaTopics.NOTIFICATION_BOM, record);
                    assertThat(notification.getGroup()).isEqualTo(Group.GROUP_BOM_PROCESSED);
                })
                .noneSatisfy(record -> {
                    assertThat(record.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name());
                    final Notification notification = deserializeValue(KafkaTopics.NOTIFICATION_BOM, record);
                    assertThat(notification.getGroup()).isEqualTo(GROUP_BOM_PROCESSING_FAILED);
                });

        final List<Bom> boms = qm.getAllBoms(project);
        assertThat(boms).hasSize(1);
        final Bom bom = boms.get(0);
        assertThat(bom.getBomFormat()).isEqualTo("CycloneDX");
        assertThat(bom.getSpecVersion()).isEqualTo("1.3");
        assertThat(bom.getBomVersion()).isEqualTo(1);
        assertThat(bom.getSerialNumber()).isEqualTo("6d780157-0f8e-4ef1-8e9b-1eb48b2fad6f");

        qm.getPersistenceManager().refresh(project);
        assertThat(project.getGroup()).isNull(); // Not overridden by BOM import
        assertThat(project.getName()).isEqualTo("Acme Example"); // Not overridden by BOM import
        assertThat(project.getVersion()).isEqualTo("1.0"); // Not overridden by BOM import
        assertThat(project.getClassifier()).isEqualTo(Classifier.APPLICATION);
        assertThat(project.getPurl()).isNotNull();
        assertThat(project.getPurl().canonicalize()).isEqualTo("pkg:npm/bloated@1.0.0");
        assertThat(project.getDirectDependencies()).isNotNull();

        // Make sure we ingested all components of the BOM.
        final List<Component> components = qm.getAllComponents(project);
        assertThat(components).hasSize(9056);

        // Assert some basic properties that should be present on all components.
        for (final Component component : components) {
            assertThat(component.getName()).isNotEmpty();
            assertThat(component.getVersion()).isNotEmpty();
            assertThat(component.getPurl()).isNotNull();
        }

        // Ensure dependency graph has been ingested completely, by asserting on the number leaf nodes of the graph.
        // This number can be verified using this Python script:
        //
        // import json
        // with open("bloated.bom.json", "r") as f:
        //     bom = json.load(f)
        // len(list(filter(lambda x: len(x.get("dependsOn", [])) == 0, bom["dependencies"])))
        final long componentsWithoutDirectDependencies = components.stream()
                .map(Component::getDirectDependencies)
                .filter(Objects::isNull)
                .count();
        assertThat(componentsWithoutDirectDependencies).isEqualTo(6378);

        // A VulnerabilityScan should've been initiated properly.
        final VulnerabilityScan vulnerabilityScan = qm.getVulnerabilityScan(bomUploadEvent.getChainIdentifier().toString());
        assertThat(vulnerabilityScan).isNotNull();
        assertThat(vulnerabilityScan.getTargetType()).isEqualTo(VulnerabilityScan.TargetType.PROJECT);
        assertThat(vulnerabilityScan.getTargetIdentifier()).isEqualTo(project.getUuid());
        assertThat(vulnerabilityScan.getExpectedResults()).isEqualTo(9056);
        assertThat(vulnerabilityScan.getReceivedResults()).isZero();

        // Verify that all vulnerability analysis commands have been sent.
        final long vulnAnalysisCommandsSent = kafkaMockProducer.history().stream()
                .map(ProducerRecord::topic)
                .filter(KafkaTopics.VULN_ANALYSIS_COMMAND.name()::equals)
                .count();
        assertThat(vulnAnalysisCommandsSent).isEqualTo(9056);

        // Verify that all repository meta analysis commands have been sent.
        final long repoMetaAnalysisCommandsSent = kafkaMockProducer.history().stream()
                .map(ProducerRecord::topic)
                .filter(KafkaTopics.REPO_META_ANALYSIS_COMMAND.name()::equals)
                .count();
        assertThat(repoMetaAnalysisCommandsSent).isEqualTo(9056);
    }

    @Test // https://github.com/DependencyTrack/dependency-track/issues/2519
    public void informIssue2519Test() throws Exception {
        final var project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        // Upload the same BOM again a few times.
        // Ensure processing does not fail, and the number of components ingested doesn't change.
        for (int i = 0; i < 3; i++) {
            var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-issue2519.xml"));
            new BomUploadProcessingTask().inform(bomUploadEvent);

            // Make sure processing did not fail.
            assertThat(kafkaMockProducer.history())
                    .noneSatisfy(record -> {
                        assertThat(record.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name());
                        final Notification notification = deserializeValue(KafkaTopics.NOTIFICATION_BOM, record);
                        assertThat(notification.getGroup()).isEqualTo(GROUP_BOM_PROCESSING_FAILED);
                    });

            // Ensure the expected amount of components is present.
            assertThat(qm.getAllComponents(project)).hasSize(1756);
        }
    }

    @Test // https://github.com/DependencyTrack/dependency-track/issues/2859
    public void informIssue2859Test() {
        final Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        assertThatNoException()
                .isThrownBy(() -> new BomUploadProcessingTask().inform(new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-issue2859.xml"))));
    }

    @Test // https://github.com/DependencyTrack/dependency-track/issues/1905
    public void informIssue1905Test() throws Exception {
        final var project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        for (int i = 0; i < 3; i++) {
            var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-issue1905.json"));
            new BomUploadProcessingTask().inform(bomUploadEvent);

            // Make sure processing did not fail.
            assertThat(kafkaMockProducer.history())
                    .noneSatisfy(record -> {
                        assertThat(record.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name());
                        final Notification notification = deserializeValue(KafkaTopics.NOTIFICATION_BOM, record);
                        assertThat(notification.getGroup()).isEqualTo(GROUP_BOM_PROCESSING_FAILED);
                    });

            // Ensure all expected components are present.
            // In this particular case, both components from the BOM are supposed to NOT be merged.
            assertThat(qm.getAllComponents(project)).satisfiesExactlyInAnyOrder(
                    component -> {
                        assertThat(component.getClassifier()).isEqualTo(Classifier.LIBRARY);
                        assertThat(component.getName()).isEqualTo("cloud.google.com/go/storage");
                        assertThat(component.getVersion()).isEqualTo("v1.13.0");
                        assertThat(component.getPurl().canonicalize()).isEqualTo("pkg:golang/cloud.google.com/go/storage@v1.13.0?type=package");
                        assertThat(component.getSha256()).isNull();
                    },
                    component -> {
                        assertThat(component.getClassifier()).isEqualTo(Classifier.LIBRARY);
                        assertThat(component.getName()).isEqualTo("cloud.google.com/go/storage");
                        assertThat(component.getVersion()).isEqualTo("v1.13.0");
                        assertThat(component.getPurl().canonicalize()).isEqualTo("pkg:golang/cloud.google.com/go/storage@v1.13.0?goarch=amd64&goos=darwin&type=module");
                        assertThat(component.getSha256()).isEqualTo("6a63ef842388f8796da7aacfbbeeb661dc2122b8dffb7e0f29500be07c206309");
                    }
            );
        }
    }

    @Test
    public void informWithComponentsUnderMetadataBomTest() throws Exception {
        final var project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        final var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-metadata-components.json"));
        new BomUploadProcessingTask().inform(bomUploadEvent);

        assertThat(kafkaMockProducer.history())
                .anySatisfy(record -> {
                    assertThat(deserializeKey(KafkaTopics.NOTIFICATION_BOM, record)).isEqualTo(project.getUuid().toString());
                    assertThat(record.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name());
                    final Notification notification = deserializeValue(KafkaTopics.NOTIFICATION_BOM, record);
                    assertThat(notification.getGroup()).isEqualTo(Group.GROUP_BOM_CONSUMED);
                })
                .anySatisfy(record -> {
                    assertThat(deserializeKey(KafkaTopics.NOTIFICATION_BOM, record)).isEqualTo(project.getUuid().toString());
                    assertThat(record.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name());
                    final Notification notification = deserializeValue(KafkaTopics.NOTIFICATION_BOM, record);
                    assertThat(notification.getGroup()).isEqualTo(Group.GROUP_BOM_PROCESSED);
                })
                .noneSatisfy(record -> {
                    assertThat(record.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name());
                    final Notification notification = deserializeValue(KafkaTopics.NOTIFICATION_BOM, record);
                    assertThat(notification.getGroup()).isEqualTo(GROUP_BOM_PROCESSING_FAILED);
                });

        final List<Bom> boms = qm.getAllBoms(project);
        assertThat(boms).hasSize(1);
        final Bom bom = boms.get(0);
        assertThat(bom.getBomFormat()).isEqualTo("CycloneDX");
        assertThat(bom.getSpecVersion()).isEqualTo("1.4");
        assertThat(bom.getBomVersion()).isEqualTo(1);
        assertThat(bom.getSerialNumber()).isEqualTo("d7cf8503-6d80-4219-ab4c-3bab8f250ee7");

        qm.getPersistenceManager().refresh(project);
        assertThat(project.getGroup()).isNull(); // Not overridden by BOM import
        assertThat(project.getName()).isEqualTo("Acme Example"); // Not overridden by BOM import
        assertThat(project.getVersion()).isEqualTo("1.0"); // Not overridden by BOM import
        assertThat(project.getClassifier()).isEqualTo(Classifier.APPLICATION);
        assertThat(project.getPurl()).isNotNull();
        assertThat(project.getPurl().canonicalize()).isEqualTo("pkg:maven/test/Test@latest?type=jar");
        assertThat(project.getDirectDependencies()).isNotNull();

        // Make sure we ingested all components of the BOM.
        final List<Component> components = qm.getAllComponents(project);
        assertThat(components).hasSize(185);
    }

    @Test
    public void informWithDelayedBomProcessedNotification() throws Exception {
        Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        final var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-1.xml"));
        qm.createWorkflowSteps(bomUploadEvent.getChainIdentifier());

        new BomUploadProcessingTask(new KafkaEventDispatcher(), /* delayBomProcessedNotification */ true).inform(bomUploadEvent);
        assertConditionWithTimeout(() -> kafkaMockProducer.history().size() >= 4, Duration.ofSeconds(5));
        assertThat(kafkaMockProducer.history()).satisfiesExactly(
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_PROJECT_CREATED.name()),
                event -> {
                    assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name());
                    final Notification notification = deserializeValue(KafkaTopics.NOTIFICATION_BOM, event);
                    assertThat(notification.getGroup()).isEqualTo(Group.GROUP_BOM_CONSUMED);
                },
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.VULN_ANALYSIS_COMMAND.name()),
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.REPO_META_ANALYSIS_COMMAND.name())
                // BOM_PROCESSED notification should not have been sent.
        );
    }

    @Test
    public void informWithDelayedBomProcessedNotificationAndNoComponents() throws Exception {
        Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        final var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-empty.json"));
        qm.createWorkflowSteps(bomUploadEvent.getChainIdentifier());

        new BomUploadProcessingTask(new KafkaEventDispatcher(), /* delayBomProcessedNotification */ true).inform(bomUploadEvent);
        assertConditionWithTimeout(() -> kafkaMockProducer.history().size() >= 3, Duration.ofSeconds(5));
        assertThat(kafkaMockProducer.history()).satisfiesExactly(
                event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_PROJECT_CREATED.name()),
                event -> {
                    assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name());
                    final Notification notification = deserializeValue(KafkaTopics.NOTIFICATION_BOM, event);
                    assertThat(notification.getGroup()).isEqualTo(Group.GROUP_BOM_CONSUMED);
                },
                event -> {
                    assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name());
                    final Notification notification = deserializeValue(KafkaTopics.NOTIFICATION_BOM, event);
                    assertThat(notification.getGroup()).isEqualTo(Group.GROUP_BOM_PROCESSED);
                }
        );
    }

    @Test
    public void informWithComponentWithoutPurl() throws Exception {
        Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        final var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-no-purl.json"));
        qm.createWorkflowSteps(bomUploadEvent.getChainIdentifier());
        new BomUploadProcessingTask().inform(bomUploadEvent);

        await("BOM processing")
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(kafkaMockProducer.history()).satisfiesExactly(
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_PROJECT_CREATED.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.VULN_ANALYSIS_COMMAND.name()),
                        // (No REPO_META_ANALYSIS_COMMAND event because the component doesn't have a PURL)
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name())
                ));

        assertThat(qm.getAllComponents(project))
                .satisfiesExactly(component -> assertThat(component.getName()).isEqualTo("acme-lib"));
    }

    @Test
    public void informWithCustomLicenseResolutionTest() throws Exception {
        final var customLicense = new License();
        customLicense.setName("custom license foobar");
        qm.createCustomLicense(customLicense, false);

        final Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        final var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-custom-license.json"));
        new BomUploadProcessingTask().inform(bomUploadEvent);
        qm.createWorkflowSteps(bomUploadEvent.getChainIdentifier());

        await("BOM processing")
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(kafkaMockProducer.history()).satisfiesExactly(
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_PROJECT_CREATED.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.VULN_ANALYSIS_COMMAND.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.VULN_ANALYSIS_COMMAND.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.VULN_ANALYSIS_COMMAND.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name())
                ));

        assertThat(qm.getAllComponents(project)).satisfiesExactly(
                component -> {
                    assertThat(component.getName()).isEqualTo("acme-lib-a");
                    assertThat(component.getResolvedLicense()).isNotNull();
                    assertThat(component.getResolvedLicense().getName()).isEqualTo("custom license foobar");
                    assertThat(component.getLicense()).isNull();
                },
                component -> {
                    assertThat(component.getName()).isEqualTo("acme-lib-b");
                    assertThat(component.getResolvedLicense()).isNull();
                    assertThat(component.getLicense()).isEqualTo("does not exist");
                },
                component -> {
                    assertThat(component.getName()).isEqualTo("acme-lib-c");
                    assertThat(component.getResolvedLicense()).isNull();
                    assertThat(component.getLicense()).isNull();
                }
        );
    }

    @Test
    public void informWithBomContainingLicenseExpressionTest() throws Exception {
        final Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        final var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-license-expression.json"));
        new BomUploadProcessingTask().inform(bomUploadEvent);
        qm.createWorkflowSteps(bomUploadEvent.getChainIdentifier());

        await("BOM processing")
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(kafkaMockProducer.history()).satisfiesExactly(
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_PROJECT_CREATED.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.VULN_ANALYSIS_COMMAND.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name())
                ));

        assertThat(qm.getAllComponents(project)).satisfiesExactly(component -> {
            assertThat(component.getLicense()).isNull();
            assertThat(component.getLicenseExpression()).isEqualTo("EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0");
            assertThat(component.getResolvedLicense()).isNull();
        });
    }

    @Test
    public void informWithBomContainingLicenseExpressionWithSingleIdTest() throws Exception {
        final var license = new License();
        license.setLicenseId("EPL-2.0");
        license.setName("Eclipse Public License 2.0");
        qm.persist(license);

        final Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        final var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-license-expression-single-license.json"));
        new BomUploadProcessingTask().inform(bomUploadEvent);
        qm.createWorkflowSteps(bomUploadEvent.getChainIdentifier());

        await("BOM processing")
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(kafkaMockProducer.history()).satisfiesExactly(
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_PROJECT_CREATED.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.VULN_ANALYSIS_COMMAND.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name())
                ));

        assertThat(qm.getAllComponents(project)).satisfiesExactly(component -> {
            assertThat(component.getResolvedLicense()).isNotNull();
            assertThat(component.getResolvedLicense().getLicenseId()).isEqualTo("EPL-2.0");
            assertThat(component.getLicense()).isNull();
            assertThat(component.getLicenseExpression()).isEqualTo("EPL-2.0");
        });
    }

    @Test
    public void informWithBomContainingInvalidLicenseExpressionTest() throws Exception {
        final Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        final var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-invalid-license-expression.json"));
        new BomUploadProcessingTask().inform(bomUploadEvent);
        qm.createWorkflowSteps(bomUploadEvent.getChainIdentifier());

        await("BOM processing")
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(kafkaMockProducer.history()).satisfiesExactly(
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_PROJECT_CREATED.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.VULN_ANALYSIS_COMMAND.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name())
                ));

        assertThat(qm.getAllComponents(project)).satisfiesExactly(component -> {
            assertThat(component.getLicense()).isNull();
            assertThat(component.getLicenseExpression()).isNull();
            assertThat(component.getResolvedLicense()).isNull();
        });
    }

    @Test
    public void informWithBomContainingServiceTest() throws Exception {
        final Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);

        final var bomUploadEvent = new BomUploadEvent(qm.detach(Project.class, project.getId()), createTempBomFile("bom-service.json"));
        new BomUploadProcessingTask().inform(bomUploadEvent);
        qm.createWorkflowSteps(bomUploadEvent.getChainIdentifier());

        await("BOM processing")
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(kafkaMockProducer.history()).satisfiesExactly(
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_PROJECT_CREATED.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.VULN_ANALYSIS_COMMAND.name()),
                        event -> assertThat(event.topic()).isEqualTo(KafkaTopics.NOTIFICATION_BOM.name())
                ));

        assertThat(qm.getAllComponents(project)).isNotEmpty();
        assertThat(qm.getAllServiceComponents(project)).isNotEmpty();
    }

    private static File createTempBomFile(final String testFileName) throws Exception {
        // The task will delete the input file after processing it,
        // so create a temporary copy to not impact other tests.
        final Path bomFilePath = Files.createTempFile(null, null);
        Files.copy(Paths.get(resourceToURL("/unit/" + testFileName).toURI()), bomFilePath, StandardCopyOption.REPLACE_EXISTING);
        return bomFilePath.toFile();
    }

}
