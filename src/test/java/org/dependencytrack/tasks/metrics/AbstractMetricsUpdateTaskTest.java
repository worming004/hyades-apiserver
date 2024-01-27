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
package org.dependencytrack.tasks.metrics;

import org.dependencytrack.AbstractPostgresEnabledTest;
import org.dependencytrack.model.Component;
import org.dependencytrack.model.Policy;
import org.dependencytrack.model.PolicyCondition;
import org.dependencytrack.model.PolicyViolation;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.contrib.java.lang.system.EnvironmentVariables;

import java.util.Date;
import java.util.UUID;

abstract class AbstractMetricsUpdateTaskTest extends AbstractPostgresEnabledTest {

    @Rule
    public EnvironmentVariables environmentVariables = new EnvironmentVariables();

    @Before
    public void setUp() throws Exception {
        super.setUp();

        environmentVariables.set("TASK_METRICS_PORTFOLIO_LOCKATLEASTFORINMILLIS", "2000");
    }

    @After
    public void tearDown() {
        environmentVariables.clear("TASK_METRICS_PORTFOLIO_LOCKATLEASTFORINMILLIS");

        super.tearDown();
    }

    protected PolicyViolation createPolicyViolation(final Component component, final Policy.ViolationState violationState, final PolicyViolation.Type type) {
        final var policy = qm.createPolicy(UUID.randomUUID().toString(), Policy.Operator.ALL, violationState);
        final var policyCondition = qm.createPolicyCondition(policy, PolicyCondition.Subject.COORDINATES, PolicyCondition.Operator.MATCHES, "");
        final var policyViolation = new PolicyViolation();

        policyViolation.setComponent(component);
        policyViolation.setPolicyCondition(policyCondition);
        policyViolation.setTimestamp(new Date());
        policyViolation.setType(type);
        return qm.addPolicyViolationIfNotExist(policyViolation);
    }

}
