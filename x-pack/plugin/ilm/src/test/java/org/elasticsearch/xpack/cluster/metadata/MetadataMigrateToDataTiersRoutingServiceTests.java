/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.cluster.metadata;

import org.elasticsearch.Version;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.IndexTemplateMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.Template;
import org.elasticsearch.cluster.routing.allocation.DataTier;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.cluster.metadata.MetadataMigrateToDataTiersRoutingService.MigratedEntities;
import org.elasticsearch.xpack.core.ilm.AllocateAction;
import org.elasticsearch.xpack.core.ilm.IndexLifecycleMetadata;
import org.elasticsearch.xpack.core.ilm.LifecycleAction;
import org.elasticsearch.xpack.core.ilm.LifecycleExecutionState;
import org.elasticsearch.xpack.core.ilm.LifecyclePolicy;
import org.elasticsearch.xpack.core.ilm.LifecyclePolicyMetadata;
import org.elasticsearch.xpack.core.ilm.LifecycleSettings;
import org.elasticsearch.xpack.core.ilm.MigrateAction;
import org.elasticsearch.xpack.core.ilm.OperationMode;
import org.elasticsearch.xpack.core.ilm.Phase;
import org.elasticsearch.xpack.core.ilm.SetPriorityAction;
import org.elasticsearch.xpack.core.ilm.ShrinkAction;
import org.junit.Before;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.cluster.metadata.IndexMetadata.INDEX_ROUTING_EXCLUDE_GROUP_SETTING;
import static org.elasticsearch.cluster.metadata.IndexMetadata.INDEX_ROUTING_INCLUDE_GROUP_SETTING;
import static org.elasticsearch.cluster.metadata.IndexMetadata.INDEX_ROUTING_REQUIRE_GROUP_SETTING;
import static org.elasticsearch.cluster.routing.allocation.DataTier.ENFORCE_DEFAULT_TIER_PREFERENCE;
import static org.elasticsearch.cluster.routing.allocation.DataTier.TIER_PREFERENCE;
import static org.elasticsearch.xpack.cluster.metadata.MetadataMigrateToDataTiersRoutingService.allocateActionDefinesRoutingRules;
import static org.elasticsearch.xpack.cluster.metadata.MetadataMigrateToDataTiersRoutingService.convertAttributeValueToTierPreference;
import static org.elasticsearch.xpack.cluster.metadata.MetadataMigrateToDataTiersRoutingService.migrateIlmPolicies;
import static org.elasticsearch.xpack.cluster.metadata.MetadataMigrateToDataTiersRoutingService.migrateIndices;
import static org.elasticsearch.xpack.cluster.metadata.MetadataMigrateToDataTiersRoutingService.migrateToDataTiersRouting;
import static org.elasticsearch.xpack.core.ilm.LifecycleExecutionState.ILM_CUSTOM_METADATA_KEY;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;

public class MetadataMigrateToDataTiersRoutingServiceTests extends ESTestCase {

    private static final String DATA_ROUTING_REQUIRE_SETTING = INDEX_ROUTING_REQUIRE_GROUP_SETTING.getKey() + "data";
    private static final String DATA_ROUTING_EXCLUDE_SETTING = INDEX_ROUTING_EXCLUDE_GROUP_SETTING.getKey() + "data";
    private static final String DATA_ROUTING_INCLUDE_SETTING = INDEX_ROUTING_INCLUDE_GROUP_SETTING.getKey() + "data";
    private static final String BOX_ROUTING_REQUIRE_SETTING = INDEX_ROUTING_REQUIRE_GROUP_SETTING.getKey() + "box";
    private static final NamedXContentRegistry REGISTRY;

    static {
        REGISTRY = new NamedXContentRegistry(
            List.of(
                new NamedXContentRegistry.Entry(LifecycleAction.class, new ParseField(ShrinkAction.NAME), ShrinkAction::parse),
                new NamedXContentRegistry.Entry(LifecycleAction.class, new ParseField(AllocateAction.NAME), AllocateAction::parse)
            )
        );
    }

    private String lifecycleName;
    private String indexName;
    private Client client;

    @Before
    public void setupTestEntities() {
        lifecycleName = randomAlphaOfLengthBetween(10, 15);
        indexName = randomAlphaOfLengthBetween(10, 15);
        client = mock(Client.class);
        logger.info("--> running [{}] with indexName [{}] and ILM policy [{}]", getTestName(), indexName, lifecycleName);
    }

    public void testMigrateIlmPolicyForIndexWithoutILMMetadata() {
        ShrinkAction shrinkAction = new ShrinkAction(2, null);
        AllocateAction warmAllocateAction = new AllocateAction(null, null, Map.of("data", "warm"), null, Map.of("rack", "rack1"));
        AllocateAction coldAllocateAction = new AllocateAction(0, null, null, null, Map.of("data", "cold"));
        SetPriorityAction warmSetPriority = new SetPriorityAction(100);
        LifecyclePolicyMetadata policyMetadata = getWarmColdPolicyMeta(
            warmSetPriority,
            shrinkAction,
            warmAllocateAction,
            coldAllocateAction
        );

        ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
            .metadata(
                Metadata.builder()
                    .putCustom(
                        IndexLifecycleMetadata.TYPE,
                        new IndexLifecycleMetadata(
                            Collections.singletonMap(policyMetadata.getName(), policyMetadata),
                            OperationMode.STOPPED
                        )
                    )
                    .put(IndexMetadata.builder(indexName).settings(getBaseIndexSettings()))
                    .build()
            )
            .build();

        Metadata.Builder newMetadata = Metadata.builder(state.metadata());
        List<String> migratedPolicies = migrateIlmPolicies(newMetadata, state, "data", REGISTRY, client, null);
        assertThat(migratedPolicies.size(), is(1));
        assertThat(migratedPolicies.get(0), is(lifecycleName));

        ClusterState newState = ClusterState.builder(state).metadata(newMetadata).build();
        IndexLifecycleMetadata updatedLifecycleMetadata = newState.metadata().custom(IndexLifecycleMetadata.TYPE);
        LifecyclePolicy lifecyclePolicy = updatedLifecycleMetadata.getPolicies().get(lifecycleName);
        Map<String, LifecycleAction> warmActions = lifecyclePolicy.getPhases().get("warm").getActions();
        assertThat(
            "allocate action in the warm phase didn't specify any number of replicas so it must be removed",
            warmActions.size(),
            is(2)
        );
        assertThat(warmActions.get(shrinkAction.getWriteableName()), is(shrinkAction));
        assertThat(warmActions.get(warmSetPriority.getWriteableName()), is(warmSetPriority));

        Map<String, LifecycleAction> coldActions = lifecyclePolicy.getPhases().get("cold").getActions();
        assertThat(coldActions.size(), is(1));
        AllocateAction migratedColdAllocateAction = (AllocateAction) coldActions.get(coldAllocateAction.getWriteableName());
        assertThat(migratedColdAllocateAction.getNumberOfReplicas(), is(0));
        assertThat(migratedColdAllocateAction.getRequire().size(), is(0));
    }

    public void testMigrateIlmPolicyFOrPhaseWithDeactivatedMigrateAction() {
        ShrinkAction shrinkAction = new ShrinkAction(2, null);
        AllocateAction warmAllocateAction = new AllocateAction(null, null, Map.of("data", "warm"), null, Map.of("rack", "rack1"));

        LifecyclePolicy policy = new LifecyclePolicy(
            lifecycleName,
            Map.of(
                "warm",
                new Phase(
                    "warm",
                    TimeValue.ZERO,
                    Map.of(
                        shrinkAction.getWriteableName(),
                        shrinkAction,
                        warmAllocateAction.getWriteableName(),
                        warmAllocateAction,
                        MigrateAction.DISABLED.getWriteableName(),
                        MigrateAction.DISABLED
                    )
                )
            )
        );
        LifecyclePolicyMetadata policyMetadata = new LifecyclePolicyMetadata(
            policy,
            Collections.emptyMap(),
            randomNonNegativeLong(),
            randomNonNegativeLong()
        );

        ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
            .metadata(
                Metadata.builder()
                    .putCustom(
                        IndexLifecycleMetadata.TYPE,
                        new IndexLifecycleMetadata(
                            Collections.singletonMap(policyMetadata.getName(), policyMetadata),
                            OperationMode.STOPPED
                        )
                    )
                    .put(IndexMetadata.builder(indexName).settings(getBaseIndexSettings()))
                    .build()
            )
            .build();

        Metadata.Builder newMetadata = Metadata.builder(state.metadata());
        List<String> migratedPolicies = migrateIlmPolicies(newMetadata, state, "data", REGISTRY, client, null);
        assertThat(migratedPolicies.size(), is(1));
        assertThat(migratedPolicies.get(0), is(lifecycleName));

        ClusterState newState = ClusterState.builder(state).metadata(newMetadata).build();
        IndexLifecycleMetadata updatedLifecycleMetadata = newState.metadata().custom(IndexLifecycleMetadata.TYPE);
        LifecyclePolicy lifecyclePolicy = updatedLifecycleMetadata.getPolicies().get(lifecycleName);
        Map<String, LifecycleAction> warmActions = lifecyclePolicy.getPhases().get("warm").getActions();
        assertThat(
            "allocate action in the warm phase didn't specify any number of replicas so it must be removed, together with the "
                + "deactivated migrate action",
            warmActions.size(),
            is(1)
        );
        assertThat(warmActions.get(shrinkAction.getWriteableName()), is(shrinkAction));
    }

    @SuppressWarnings("unchecked")
    public void testMigrateIlmPolicyRefreshesCachedPhase() {
        ShrinkAction shrinkAction = new ShrinkAction(2, null);
        AllocateAction warmAllocateAction = new AllocateAction(null, null, Map.of("data", "warm"), null, Map.of("rack", "rack1"));
        AllocateAction coldAllocateAction = new AllocateAction(0, null, null, null, Map.of("data", "cold"));
        SetPriorityAction warmSetPriority = new SetPriorityAction(100);
        LifecyclePolicyMetadata policyMetadata = getWarmColdPolicyMeta(
            warmSetPriority,
            shrinkAction,
            warmAllocateAction,
            coldAllocateAction
        );

        {
            // index is in the cold phase and the migrated allocate action is not removed
            LifecycleExecutionState preMigrationExecutionState = LifecycleExecutionState.builder()
                .setPhase("cold")
                .setAction("allocate")
                .setStep("allocate")
                .setPhaseDefinition(getColdPhaseDefinition())
                .build();

            IndexMetadata.Builder indexMetadata = IndexMetadata.builder(indexName)
                .settings(getBaseIndexSettings())
                .putCustom(ILM_CUSTOM_METADATA_KEY, preMigrationExecutionState.asMap());

            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(
                    Metadata.builder()
                        .putCustom(
                            IndexLifecycleMetadata.TYPE,
                            new IndexLifecycleMetadata(
                                Collections.singletonMap(policyMetadata.getName(), policyMetadata),
                                OperationMode.STOPPED
                            )
                        )
                        .put(indexMetadata)
                        .build()
                )
                .build();

            Metadata.Builder newMetadata = Metadata.builder(state.metadata());
            List<String> migratedPolicies = migrateIlmPolicies(newMetadata, state, "data", REGISTRY, client, null);

            assertThat(migratedPolicies.get(0), is(lifecycleName));
            ClusterState newState = ClusterState.builder(state).metadata(newMetadata).build();
            LifecycleExecutionState newLifecycleState = LifecycleExecutionState.fromIndexMetadata(newState.metadata().index(indexName));

            Map<String, Object> migratedPhaseDefAsMap = getPhaseDefinitionAsMap(newLifecycleState);

            // expecting the phase definition to be refreshed with the migrated phase representation
            // ie. allocate action does not contain any allocation rules
            Map<String, Object> actions = (Map<String, Object>) migratedPhaseDefAsMap.get("actions");
            assertThat(actions.size(), is(1));
            Map<String, Object> allocateDef = (Map<String, Object>) actions.get(AllocateAction.NAME);
            assertThat(allocateDef, notNullValue());
            assertThat(allocateDef.get("include"), is(Map.of()));
            assertThat(allocateDef.get("exclude"), is(Map.of()));
            assertThat(allocateDef.get("require"), is(Map.of()));
        }

        {
            // index is in the cold phase and the migrated allocate action is not removed due to allocate specifying
            // total_shards_per_node
            LifecyclePolicyMetadata policyMetadataWithTotalShardsPerNode = getWarmColdPolicyMeta(
                warmSetPriority,
                shrinkAction,
                warmAllocateAction,
                new AllocateAction(null, 1, null, null, Map.of("data", "cold"))
            );

            LifecycleExecutionState preMigrationExecutionState = LifecycleExecutionState.builder()
                .setPhase("cold")
                .setAction("allocate")
                .setStep("allocate")
                .setPhaseDefinition(getColdPhaseDefinitionWithTotalShardsPerNode())
                .build();

            IndexMetadata.Builder indexMetadata = IndexMetadata.builder(indexName)
                .settings(getBaseIndexSettings())
                .putCustom(ILM_CUSTOM_METADATA_KEY, preMigrationExecutionState.asMap());

            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(
                    Metadata.builder()
                        .putCustom(
                            IndexLifecycleMetadata.TYPE,
                            new IndexLifecycleMetadata(
                                Collections.singletonMap(
                                    policyMetadataWithTotalShardsPerNode.getName(),
                                    policyMetadataWithTotalShardsPerNode
                                ),
                                OperationMode.STOPPED
                            )
                        )
                        .put(indexMetadata)
                        .build()
                )
                .build();

            Metadata.Builder newMetadata = Metadata.builder(state.metadata());
            List<String> migratedPolicies = migrateIlmPolicies(newMetadata, state, "data", REGISTRY, client, null);

            assertThat(migratedPolicies.get(0), is(lifecycleName));
            ClusterState newState = ClusterState.builder(state).metadata(newMetadata).build();
            LifecycleExecutionState newLifecycleState = LifecycleExecutionState.fromIndexMetadata(newState.metadata().index(indexName));

            Map<String, Object> migratedPhaseDefAsMap = getPhaseDefinitionAsMap(newLifecycleState);

            // expecting the phase definition to be refreshed with the migrated phase representation
            // ie. allocate action does not contain any allocation rules
            Map<String, Object> actions = (Map<String, Object>) migratedPhaseDefAsMap.get("actions");
            assertThat(actions.size(), is(1));
            Map<String, Object> allocateDef = (Map<String, Object>) actions.get(AllocateAction.NAME);
            assertThat(allocateDef, notNullValue());
            assertThat(allocateDef.get("include"), is(Map.of()));
            assertThat(allocateDef.get("exclude"), is(Map.of()));
            assertThat(allocateDef.get("require"), is(Map.of()));
        }

        {
            // index is in the warm phase executing the allocate action, the migrated allocate action is removed
            LifecycleExecutionState preMigrationExecutionState = LifecycleExecutionState.builder()
                .setPhase("warm")
                .setAction("allocate")
                .setStep("allocate")
                .setPhaseDefinition(getWarmPhaseDef())
                .build();

            IndexMetadata.Builder indexMetadata = IndexMetadata.builder(indexName)
                .settings(getBaseIndexSettings())
                .putCustom(ILM_CUSTOM_METADATA_KEY, preMigrationExecutionState.asMap());

            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(
                    Metadata.builder()
                        .putCustom(
                            IndexLifecycleMetadata.TYPE,
                            new IndexLifecycleMetadata(
                                Collections.singletonMap(policyMetadata.getName(), policyMetadata),
                                OperationMode.STOPPED
                            )
                        )
                        .put(indexMetadata)
                        .build()
                )
                .build();

            Metadata.Builder newMetadata = Metadata.builder(state.metadata());
            List<String> migratedPolicies = migrateIlmPolicies(newMetadata, state, "data", REGISTRY, client, null);

            assertThat(migratedPolicies.get(0), is(lifecycleName));
            ClusterState newState = ClusterState.builder(state).metadata(newMetadata).build();
            LifecycleExecutionState newLifecycleState = LifecycleExecutionState.fromIndexMetadata(newState.metadata().index(indexName));

            Map<String, Object> migratedPhaseDefAsMap = getPhaseDefinitionAsMap(newLifecycleState);

            // expecting the phase definition to be refreshed with the index being in the migrate action.
            // even though the policy above doesn't mention migrate specifically, it has been injected.
            Map<String, Object> actions = (Map<String, Object>) migratedPhaseDefAsMap.get("actions");
            assertThat(actions.size(), is(2));
            Map<String, Object> allocateDef = (Map<String, Object>) actions.get(AllocateAction.NAME);
            assertThat(allocateDef, nullValue());
            assertThat(newLifecycleState.getAction(), is(MigrateAction.NAME));
            assertThat(newLifecycleState.getStep(), is(MigrateAction.CONDITIONAL_SKIP_MIGRATE_STEP));

            // the shrink and set_priority actions are unchanged
            Map<String, Object> shrinkDef = (Map<String, Object>) actions.get(ShrinkAction.NAME);
            assertThat(shrinkDef.get("number_of_shards"), is(2));
            Map<String, Object> setPriorityDef = (Map<String, Object>) actions.get(SetPriorityAction.NAME);
            assertThat(setPriorityDef.get("priority"), is(100));
        }

        {
            // index is in the warm phase executing the set priority action (executes BEFORE allocate), the migrated allocate action is
            // removed
            LifecycleExecutionState preMigrationExecutionState = LifecycleExecutionState.builder()
                .setPhase("warm")
                .setAction(SetPriorityAction.NAME)
                .setStep(SetPriorityAction.NAME)
                .setPhaseDefinition(getWarmPhaseDef())
                .build();

            IndexMetadata.Builder indexMetadata = IndexMetadata.builder(indexName)
                .settings(getBaseIndexSettings())
                .putCustom(ILM_CUSTOM_METADATA_KEY, preMigrationExecutionState.asMap());

            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(
                    Metadata.builder()
                        .putCustom(
                            IndexLifecycleMetadata.TYPE,
                            new IndexLifecycleMetadata(
                                Collections.singletonMap(policyMetadata.getName(), policyMetadata),
                                OperationMode.STOPPED
                            )
                        )
                        .put(indexMetadata)
                        .build()
                )
                .build();

            Metadata.Builder newMetadata = Metadata.builder(state.metadata());
            List<String> migratedPolicies = migrateIlmPolicies(newMetadata, state, "data", REGISTRY, client, null);

            assertThat(migratedPolicies.get(0), is(lifecycleName));
            ClusterState newState = ClusterState.builder(state).metadata(newMetadata).build();
            LifecycleExecutionState newLifecycleState = LifecycleExecutionState.fromIndexMetadata(newState.metadata().index(indexName));
            Map<String, Object> migratedPhaseDefAsMap = getPhaseDefinitionAsMap(newLifecycleState);

            // expecting the phase definition to be refreshed with the index being in the set_priority action
            Map<String, Object> actions = (Map<String, Object>) migratedPhaseDefAsMap.get("actions");
            assertThat(actions.size(), is(2));
            Map<String, Object> allocateDef = (Map<String, Object>) actions.get(AllocateAction.NAME);
            assertThat(allocateDef, nullValue());
            Map<String, Object> shrinkDef = (Map<String, Object>) actions.get(ShrinkAction.NAME);
            assertThat(shrinkDef.get("number_of_shards"), is(2));
            Map<String, Object> setPriorityDef = (Map<String, Object>) actions.get(SetPriorityAction.NAME);
            assertThat(setPriorityDef.get("priority"), is(100));
            assertThat(newLifecycleState.getAction(), is(SetPriorityAction.NAME));
            assertThat(newLifecycleState.getStep(), is(SetPriorityAction.NAME));
        }

        {
            // index is in the warm phase executing the shrink action (executes AFTER allocate), the migrated allocate action is
            // removed
            LifecycleExecutionState preMigrationExecutionState = LifecycleExecutionState.builder()
                .setPhase("warm")
                .setAction(ShrinkAction.NAME)
                .setStep(ShrinkAction.CONDITIONAL_SKIP_SHRINK_STEP)
                .setPhaseDefinition(getWarmPhaseDef())
                .build();

            IndexMetadata.Builder indexMetadata = IndexMetadata.builder(indexName)
                .settings(getBaseIndexSettings())
                .putCustom(ILM_CUSTOM_METADATA_KEY, preMigrationExecutionState.asMap());

            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(
                    Metadata.builder()
                        .putCustom(
                            IndexLifecycleMetadata.TYPE,
                            new IndexLifecycleMetadata(
                                Collections.singletonMap(policyMetadata.getName(), policyMetadata),
                                OperationMode.STOPPED
                            )
                        )
                        .put(indexMetadata)
                        .build()
                )
                .build();

            Metadata.Builder newMetadata = Metadata.builder(state.metadata());
            List<String> migratedPolicies = migrateIlmPolicies(newMetadata, state, "data", REGISTRY, client, null);

            assertThat(migratedPolicies.get(0), is(lifecycleName));
            ClusterState newState = ClusterState.builder(state).metadata(newMetadata).build();
            LifecycleExecutionState newLifecycleState = LifecycleExecutionState.fromIndexMetadata(newState.metadata().index(indexName));

            Map<String, Object> migratedPhaseDefAsMap = getPhaseDefinitionAsMap(newLifecycleState);

            // expecting the phase definition to be refreshed with the index being in the shrink action
            Map<String, Object> actions = (Map<String, Object>) migratedPhaseDefAsMap.get("actions");
            assertThat(actions.size(), is(2));
            Map<String, Object> allocateDef = (Map<String, Object>) actions.get(AllocateAction.NAME);
            assertThat(allocateDef, nullValue());
            assertThat(newLifecycleState.getAction(), is(ShrinkAction.NAME));
            assertThat(newLifecycleState.getStep(), is(ShrinkAction.CONDITIONAL_SKIP_SHRINK_STEP));

            Map<String, Object> shrinkDef = (Map<String, Object>) actions.get(ShrinkAction.NAME);
            assertThat(shrinkDef.get("number_of_shards"), is(2));
            Map<String, Object> setPriorityDef = (Map<String, Object>) actions.get(SetPriorityAction.NAME);
            assertThat(setPriorityDef.get("priority"), is(100));
        }
    }

    private Settings.Builder getBaseIndexSettings() {
        return Settings.builder()
            .put(LifecycleSettings.LIFECYCLE_NAME, lifecycleName)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, randomIntBetween(1, 10))
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, randomIntBetween(0, 5))
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT);
    }

    public void testAllocateActionDefinesRoutingRules() {
        assertThat(allocateActionDefinesRoutingRules("data", new AllocateAction(null, null, Map.of("data", "cold"), null, null)), is(true));
        assertThat(allocateActionDefinesRoutingRules("data", new AllocateAction(null, null, null, Map.of("data", "cold"), null)), is(true));
        assertThat(
            allocateActionDefinesRoutingRules(
                "data",
                new AllocateAction(null, null, Map.of("another_attribute", "rack1"), null, Map.of("data", "cold"))
            ),
            is(true)
        );
        assertThat(
            allocateActionDefinesRoutingRules("data", new AllocateAction(null, null, null, null, Map.of("another_attribute", "cold"))),
            is(false)
        );
        assertThat(allocateActionDefinesRoutingRules("data", null), is(false));
    }

    public void testConvertAttributeValueToTierPreference() {
        assertThat(convertAttributeValueToTierPreference("frozen"), is("data_frozen,data_cold,data_warm,data_hot"));
        assertThat(convertAttributeValueToTierPreference("cold"), is("data_cold,data_warm,data_hot"));
        assertThat(convertAttributeValueToTierPreference("warm"), is("data_warm,data_hot"));
        assertThat(convertAttributeValueToTierPreference("hot"), is("data_hot"));
        assertThat(convertAttributeValueToTierPreference("content"), nullValue());
        assertThat(convertAttributeValueToTierPreference("rack1"), nullValue());
    }

    public void testMigrateIndices() {
        {
            // index with `warm` data attribute is migrated to the equivalent _tier_preference routing
            IndexMetadata.Builder indexWithWarmDataAttribute = IndexMetadata.builder("indexWithWarmDataAttribute")
                .settings(getBaseIndexSettings().put(DATA_ROUTING_REQUIRE_SETTING, "warm"));
            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(Metadata.builder().put(indexWithWarmDataAttribute))
                .build();

            Metadata.Builder mb = Metadata.builder(state.metadata());

            List<String> migratedIndices = migrateIndices(mb, state, "data");
            assertThat(migratedIndices.size(), is(1));
            assertThat(migratedIndices.get(0), is("indexWithWarmDataAttribute"));

            ClusterState migratedState = ClusterState.builder(ClusterName.DEFAULT).metadata(mb).build();
            IndexMetadata migratedIndex = migratedState.metadata().index("indexWithWarmDataAttribute");
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_REQUIRE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_warm,data_hot"));
        }

        {
            // test the migration of the `include.data` configuration to the equivalent _tier_preference routing
            IndexMetadata.Builder indexWithWarmDataAttribute = IndexMetadata.builder("indexWithWarmDataAttribute")
                .settings(getBaseIndexSettings().put(DATA_ROUTING_INCLUDE_SETTING, "warm"));
            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(Metadata.builder().put(indexWithWarmDataAttribute))
                .build();

            Metadata.Builder mb = Metadata.builder(state.metadata());

            List<String> migratedIndices = migrateIndices(mb, state, "data");
            assertThat(migratedIndices.size(), is(1));
            assertThat(migratedIndices.get(0), is("indexWithWarmDataAttribute"));

            ClusterState migratedState = ClusterState.builder(ClusterName.DEFAULT).metadata(mb).build();
            IndexMetadata migratedIndex = migratedState.metadata().index("indexWithWarmDataAttribute");
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_INCLUDE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_warm,data_hot"));
        }

        {
            // since the index has a _tier_preference configuration the migrated index should still contain it and have ALL the `data`
            // attributes routing removed
            // given the `require.data` attribute configuration is colder than the existing _tier_preference configuration, the
            // _tier_preference must be updated to reflect the coldest tier configured in the `require.data` attribute
            IndexMetadata.Builder indexWithTierPreferenceAndDataAttribute = IndexMetadata.builder("indexWithTierPreferenceAndDataAttribute")
                .settings(
                    getBaseIndexSettings().put(DATA_ROUTING_REQUIRE_SETTING, "cold")
                        .put(DATA_ROUTING_INCLUDE_SETTING, "hot")
                        .put(TIER_PREFERENCE, "data_warm,data_hot")
                );
            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(Metadata.builder().put(indexWithTierPreferenceAndDataAttribute))
                .build();

            Metadata.Builder mb = Metadata.builder(state.metadata());

            List<String> migratedIndices = migrateIndices(mb, state, "data");
            assertThat(migratedIndices.size(), is(1));
            assertThat(migratedIndices.get(0), is("indexWithTierPreferenceAndDataAttribute"));

            ClusterState migratedState = ClusterState.builder(ClusterName.DEFAULT).metadata(mb).build();
            IndexMetadata migratedIndex = migratedState.metadata().index("indexWithTierPreferenceAndDataAttribute");
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_REQUIRE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_INCLUDE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_cold,data_warm,data_hot"));
        }

        {
            // since the index has a _tier_preference configuration the migrated index should still contain it and have ALL the `data`
            // attributes routing removed
            // given the `include.data` attribute configuration is colder than the existing _tier_preference configuration, the
            // _tier_preference must be updated to reflect the coldest tier configured in the `include.data` attribute
            IndexMetadata.Builder indexWithTierPreferenceAndDataAttribute = IndexMetadata.builder("indexWithTierPreferenceAndDataAttribute")
                .settings(
                    getBaseIndexSettings().put(DATA_ROUTING_REQUIRE_SETTING, "hot")
                        .put(DATA_ROUTING_INCLUDE_SETTING, "cold")
                        .put(TIER_PREFERENCE, "data_warm,data_hot")
                );
            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(Metadata.builder().put(indexWithTierPreferenceAndDataAttribute))
                .build();

            Metadata.Builder mb = Metadata.builder(state.metadata());

            List<String> migratedIndices = migrateIndices(mb, state, "data");
            assertThat(migratedIndices.size(), is(1));
            assertThat(migratedIndices.get(0), is("indexWithTierPreferenceAndDataAttribute"));

            ClusterState migratedState = ClusterState.builder(ClusterName.DEFAULT).metadata(mb).build();
            IndexMetadata migratedIndex = migratedState.metadata().index("indexWithTierPreferenceAndDataAttribute");
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_REQUIRE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_INCLUDE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_cold,data_warm,data_hot"));
        }

        {
            // like above, test a combination of node attribute and _tier_preference routings configured for the original index, but this
            // time using the `include.data` setting
            // given the `include.data` attribute configuration is colder than the existing _tier_preference configuration, the
            // _tier_preference must be updated to reflect the coldest tier configured in the `include.data` attribute
            IndexMetadata.Builder indexWithTierPreferenceAndDataAttribute = IndexMetadata.builder("indexWithTierPreferenceAndDataAttribute")
                .settings(getBaseIndexSettings().put(DATA_ROUTING_INCLUDE_SETTING, "cold").put(TIER_PREFERENCE, "data_warm,data_hot"));
            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(Metadata.builder().put(indexWithTierPreferenceAndDataAttribute))
                .build();

            Metadata.Builder mb = Metadata.builder(state.metadata());

            List<String> migratedIndices = migrateIndices(mb, state, "data");
            assertThat(migratedIndices.size(), is(1));
            assertThat(migratedIndices.get(0), is("indexWithTierPreferenceAndDataAttribute"));

            ClusterState migratedState = ClusterState.builder(ClusterName.DEFAULT).metadata(mb).build();
            IndexMetadata migratedIndex = migratedState.metadata().index("indexWithTierPreferenceAndDataAttribute");
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_INCLUDE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_cold,data_warm,data_hot"));
        }

        {
            // test a combination of node attribute and _tier_preference routings configured for the original index
            // where the tier_preference is `data_content`
            // given the `include.data` attribute configuration is "colder" than the existing `data_content` _tier_preference configuration,
            // the _tier_preference must be updated to reflect the coldest tier configured in the `include.data` attribute
            IndexMetadata.Builder indexWithTierPreferenceAndDataAttribute = IndexMetadata.builder("indexWithTierPreferenceAndDataAttribute")
                .settings(getBaseIndexSettings().put(DATA_ROUTING_INCLUDE_SETTING, "cold").put(TIER_PREFERENCE, "data_content"));
            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(Metadata.builder().put(indexWithTierPreferenceAndDataAttribute))
                .build();

            Metadata.Builder mb = Metadata.builder(state.metadata());

            List<String> migratedIndices = migrateIndices(mb, state, "data");
            assertThat(migratedIndices.size(), is(1));
            assertThat(migratedIndices.get(0), is("indexWithTierPreferenceAndDataAttribute"));

            ClusterState migratedState = ClusterState.builder(ClusterName.DEFAULT).metadata(mb).build();
            IndexMetadata migratedIndex = migratedState.metadata().index("indexWithTierPreferenceAndDataAttribute");
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_INCLUDE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_cold,data_warm,data_hot"));
        }

        {
            // test a combination of node attribute and _tier_preference routings configured for the original index
            // where the tier_preference is `data_content`
            // given the `require.data` attribute configuration is `hot` the existing `data_content` _tier_preference
            // configuration must NOT be changed
            IndexMetadata.Builder indexWithTierPreferenceAndDataAttribute = IndexMetadata.builder("indexWithTierPreferenceAndDataAttribute")
                .settings(getBaseIndexSettings().put(DATA_ROUTING_REQUIRE_SETTING, "hot").put(TIER_PREFERENCE, "data_content"));
            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(Metadata.builder().put(indexWithTierPreferenceAndDataAttribute))
                .build();

            Metadata.Builder mb = Metadata.builder(state.metadata());

            List<String> migratedIndices = migrateIndices(mb, state, "data");
            assertThat(migratedIndices.size(), is(1));
            assertThat(migratedIndices.get(0), is("indexWithTierPreferenceAndDataAttribute"));

            ClusterState migratedState = ClusterState.builder(ClusterName.DEFAULT).metadata(mb).build();
            IndexMetadata migratedIndex = migratedState.metadata().index("indexWithTierPreferenceAndDataAttribute");
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_INCLUDE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_content"));
        }

        {
            // combination of both data attributes and _tier_preference, but the require data attribute has an unrecognized value
            IndexMetadata.Builder indexWithTierPreferenceAndDataAttribute = IndexMetadata.builder("indexWithTierPreferenceAndDataAttribute")
                .settings(
                    getBaseIndexSettings().put(DATA_ROUTING_REQUIRE_SETTING, "some_value")
                        .put(DATA_ROUTING_INCLUDE_SETTING, "cold")
                        .put(TIER_PREFERENCE, "data_warm,data_hot")
                );
            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(Metadata.builder().put(indexWithTierPreferenceAndDataAttribute))
                .build();

            Metadata.Builder mb = Metadata.builder(state.metadata());

            List<String> migratedIndices = migrateIndices(mb, state, "data");
            assertThat(migratedIndices.size(), is(1));
            assertThat(migratedIndices.get(0), is("indexWithTierPreferenceAndDataAttribute"));

            ClusterState migratedState = ClusterState.builder(ClusterName.DEFAULT).metadata(mb).build();
            IndexMetadata migratedIndex = migratedState.metadata().index("indexWithTierPreferenceAndDataAttribute");
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_REQUIRE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_INCLUDE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_cold,data_warm,data_hot"));
        }

        {
            // the include attribute routing is not colder than the existing _tier_preference
            IndexMetadata.Builder indexWithTierPreferenceAndDataAttribute = IndexMetadata.builder("indexWithTierPreferenceAndDataAttribute")
                .settings(getBaseIndexSettings().put(DATA_ROUTING_INCLUDE_SETTING, "hot").put(TIER_PREFERENCE, "data_warm,data_hot"));
            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(Metadata.builder().put(indexWithTierPreferenceAndDataAttribute))
                .build();

            Metadata.Builder mb = Metadata.builder(state.metadata());

            List<String> migratedIndices = migrateIndices(mb, state, "data");
            assertThat(migratedIndices.size(), is(1));
            assertThat(migratedIndices.get(0), is("indexWithTierPreferenceAndDataAttribute"));

            ClusterState migratedState = ClusterState.builder(ClusterName.DEFAULT).metadata(mb).build();
            IndexMetadata migratedIndex = migratedState.metadata().index("indexWithTierPreferenceAndDataAttribute");
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_REQUIRE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_INCLUDE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_warm,data_hot"));
        }

        {
            // index with an unknown `data` attribute routing value should be migrated
            IndexMetadata.Builder indexWithUnknownDataAttribute = IndexMetadata.builder("indexWithUnknownDataAttribute")
                .settings(getBaseIndexSettings().put(DATA_ROUTING_REQUIRE_SETTING, "something_else"));
            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(Metadata.builder().put(indexWithUnknownDataAttribute))
                .build();

            Metadata.Builder mb = Metadata.builder(state.metadata());
            List<String> migratedIndices = migrateIndices(mb, state, "data");
            assertThat(migratedIndices.size(), is(1));

            ClusterState migratedState = ClusterState.builder(ClusterName.DEFAULT).metadata(mb).build();
            IndexMetadata migratedIndex = migratedState.metadata().index("indexWithUnknownDataAttribute");
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_REQUIRE_SETTING), is("something_else"));
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_content"));
        }

        {
            // index with data and another attribute should only see the data attribute removed and the corresponding tier_preference
            // configured
            IndexMetadata.Builder indexDataAndBoxAttribute = IndexMetadata.builder("indexWithDataAndBoxAttribute")
                .settings(getBaseIndexSettings().put(DATA_ROUTING_REQUIRE_SETTING, "warm").put(BOX_ROUTING_REQUIRE_SETTING, "box1"));
            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(Metadata.builder().put(indexDataAndBoxAttribute))
                .build();

            Metadata.Builder mb = Metadata.builder(state.metadata());
            List<String> migratedIndices = migrateIndices(mb, state, "data");
            assertThat(migratedIndices.size(), is(1));
            assertThat(migratedIndices.get(0), is("indexWithDataAndBoxAttribute"));

            ClusterState migratedState = ClusterState.builder(ClusterName.DEFAULT).metadata(mb).build();
            IndexMetadata migratedIndex = migratedState.metadata().index("indexWithDataAndBoxAttribute");
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_REQUIRE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(BOX_ROUTING_REQUIRE_SETTING), is("box1"));
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_warm,data_hot"));
        }

        {
            // index that doesn't have any data attribute routing but has another attribute should be migrated
            IndexMetadata.Builder indexBoxAttribute = IndexMetadata.builder("indexWithBoxAttribute")
                .settings(getBaseIndexSettings().put(BOX_ROUTING_REQUIRE_SETTING, "warm"));
            ClusterState state = ClusterState.builder(ClusterName.DEFAULT).metadata(Metadata.builder().put(indexBoxAttribute)).build();

            Metadata.Builder mb = Metadata.builder(state.metadata());
            List<String> migratedIndices = migrateIndices(mb, state, "data");
            assertThat(migratedIndices.size(), is(1));

            ClusterState migratedState = ClusterState.builder(ClusterName.DEFAULT).metadata(mb).build();
            IndexMetadata migratedIndex = migratedState.metadata().index("indexWithBoxAttribute");
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_REQUIRE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(BOX_ROUTING_REQUIRE_SETTING), is("warm"));
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_content"));
        }

        {
            IndexMetadata.Builder indexNoRoutingAttribute = IndexMetadata.builder("indexNoRoutingAttribute")
                .settings(getBaseIndexSettings());
            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(Metadata.builder().put(indexNoRoutingAttribute))
                .build();

            Metadata.Builder mb = Metadata.builder(state.metadata());
            List<String> migratedIndices = migrateIndices(mb, state, "data");
            assertThat(migratedIndices.size(), is(1));

            ClusterState migratedState = ClusterState.builder(ClusterName.DEFAULT).metadata(mb).build();
            IndexMetadata migratedIndex = migratedState.metadata().index("indexNoRoutingAttribute");
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_REQUIRE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(BOX_ROUTING_REQUIRE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_content"));
        }
    }

    public void testColdestAttributeIsConvertedToTierPreference() {
        // `include` is colder than `require`
        {
            IndexMetadata.Builder indexWithAllRoutingSettings = IndexMetadata.builder("indexWithAllRoutingSettings")
                .settings(
                    getBaseIndexSettings().put(DATA_ROUTING_REQUIRE_SETTING, "warm")
                        .put(DATA_ROUTING_INCLUDE_SETTING, "cold")
                        .put(DATA_ROUTING_EXCLUDE_SETTING, "hot")
                );
            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(Metadata.builder().put(indexWithAllRoutingSettings))
                .build();

            Metadata.Builder mb = Metadata.builder(state.metadata());

            List<String> migratedIndices = migrateIndices(mb, state, "data");
            assertThat(migratedIndices.size(), is(1));
            assertThat(migratedIndices.get(0), is("indexWithAllRoutingSettings"));

            ClusterState migratedState = ClusterState.builder(ClusterName.DEFAULT).metadata(mb).build();
            IndexMetadata migratedIndex = migratedState.metadata().index("indexWithAllRoutingSettings");
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_INCLUDE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_REQUIRE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_EXCLUDE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_cold,data_warm,data_hot"));
        }

        {
            // `require` is colder than `include`
            IndexMetadata.Builder indexWithAllRoutingSettings = IndexMetadata.builder("indexWithAllRoutingSettings")
                .settings(
                    getBaseIndexSettings().put(DATA_ROUTING_REQUIRE_SETTING, "cold")
                        .put(DATA_ROUTING_INCLUDE_SETTING, "warm")
                        .put(DATA_ROUTING_EXCLUDE_SETTING, "hot")
                );
            ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(Metadata.builder().put(indexWithAllRoutingSettings))
                .build();

            Metadata.Builder mb = Metadata.builder(state.metadata());

            List<String> migratedIndices = migrateIndices(mb, state, "data");
            assertThat(migratedIndices.size(), is(1));
            assertThat(migratedIndices.get(0), is("indexWithAllRoutingSettings"));

            ClusterState migratedState = ClusterState.builder(ClusterName.DEFAULT).metadata(mb).build();
            IndexMetadata migratedIndex = migratedState.metadata().index("indexWithAllRoutingSettings");
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_INCLUDE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_REQUIRE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(DATA_ROUTING_EXCLUDE_SETTING), nullValue());
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_cold,data_warm,data_hot"));
        }
    }

    public void testMigrateToDataTiersRouting() {
        AllocateAction allocateActionWithDataAttribute = new AllocateAction(
            null,
            null,
            Map.of("data", "warm"),
            null,
            Map.of("rack", "rack1")
        );
        AllocateAction allocateActionWithOtherAttribute = new AllocateAction(0, null, null, null, Map.of("other", "cold"));

        LifecyclePolicy policyToMigrate = new LifecyclePolicy(
            lifecycleName,
            Map.of(
                "warm",
                new Phase(
                    "warm",
                    TimeValue.ZERO,
                    Map.of(allocateActionWithDataAttribute.getWriteableName(), allocateActionWithDataAttribute)
                )
            )
        );
        LifecyclePolicyMetadata policyWithDataAttribute = new LifecyclePolicyMetadata(
            policyToMigrate,
            Collections.emptyMap(),
            randomNonNegativeLong(),
            randomNonNegativeLong()
        );

        LifecyclePolicy shouldntBeMigratedPolicy = new LifecyclePolicy(
            "dont-migrate",
            Map.of(
                "warm",
                new Phase(
                    "warm",
                    TimeValue.ZERO,
                    Map.of(allocateActionWithOtherAttribute.getWriteableName(), allocateActionWithOtherAttribute)
                )
            )
        );
        LifecyclePolicyMetadata policyWithOtherAttribute = new LifecyclePolicyMetadata(
            shouldntBeMigratedPolicy,
            Collections.emptyMap(),
            randomNonNegativeLong(),
            randomNonNegativeLong()
        );

        IndexMetadata.Builder indexWithUnknownDataAttribute = IndexMetadata.builder("indexWithUnknownDataAttribute")
            .settings(getBaseIndexSettings().put(DATA_ROUTING_REQUIRE_SETTING, "something_else"));
        IndexMetadata.Builder indexWithWarmDataAttribute = IndexMetadata.builder("indexWithWarmDataAttribute")
            .settings(getBaseIndexSettings().put(DATA_ROUTING_REQUIRE_SETTING, "warm"));

        ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
            .metadata(
                Metadata.builder()
                    .putCustom(
                        IndexLifecycleMetadata.TYPE,
                        new IndexLifecycleMetadata(
                            Map.of(
                                policyToMigrate.getName(),
                                policyWithDataAttribute,
                                shouldntBeMigratedPolicy.getName(),
                                policyWithOtherAttribute
                            ),
                            OperationMode.STOPPED
                        )
                    )
                    .put(
                        IndexTemplateMetadata.builder("catch-all")
                            .patterns(List.of("*"))
                            .settings(Settings.builder().put(DATA_ROUTING_REQUIRE_SETTING, "hot"))
                            .build()
                    )
                    .put(
                        IndexTemplateMetadata.builder("other-template")
                            .patterns(List.of("other-*"))
                            .settings(Settings.builder().put(DATA_ROUTING_REQUIRE_SETTING, "hot"))
                            .build()
                    )
                    .put(indexWithUnknownDataAttribute)
                    .put(indexWithWarmDataAttribute)
            )
            .build();

        {
            Tuple<ClusterState, MigratedEntities> migratedEntitiesTuple = migrateToDataTiersRouting(
                state,
                "data",
                "catch-all",
                REGISTRY,
                client,
                null,
                false
            );

            MigratedEntities migratedEntities = migratedEntitiesTuple.v2();
            assertThat(migratedEntities.removedIndexTemplateName, is("catch-all"));
            assertThat(migratedEntities.migratedPolicies.size(), is(1));
            assertThat(migratedEntities.migratedPolicies.get(0), is(lifecycleName));
            assertThat(migratedEntities.migratedIndices.size(), is(2));
            assertThat(migratedEntities.migratedIndices, hasItems("indexWithWarmDataAttribute", "indexWithUnknownDataAttribute"));

            ClusterState newState = migratedEntitiesTuple.v1();
            assertThat(newState.metadata().getTemplates().size(), is(1));
            assertThat(newState.metadata().getTemplates().get("catch-all"), nullValue());
            assertThat(newState.metadata().getTemplates().get("other-template"), notNullValue());
        }

        {
            // let's test a null template name to make sure nothing is removed
            Tuple<ClusterState, MigratedEntities> migratedEntitiesTuple = migrateToDataTiersRouting(
                state,
                "data",
                null,
                REGISTRY,
                client,
                null,
                false
            );

            MigratedEntities migratedEntities = migratedEntitiesTuple.v2();
            assertThat(migratedEntities.removedIndexTemplateName, nullValue());
            assertThat(migratedEntities.migratedPolicies.size(), is(1));
            assertThat(migratedEntities.migratedPolicies.get(0), is(lifecycleName));
            assertThat(migratedEntities.migratedIndices.size(), is(2));
            assertThat(migratedEntities.migratedIndices, hasItems("indexWithWarmDataAttribute", "indexWithUnknownDataAttribute"));

            ClusterState newState = migratedEntitiesTuple.v1();
            assertThat(newState.metadata().getTemplates().size(), is(2));
            assertThat(newState.metadata().getTemplates().get("catch-all"), notNullValue());
            assertThat(newState.metadata().getTemplates().get("other-template"), notNullValue());
        }

        {
            // let's test a null node attribute parameter defaults to "data"
            Tuple<ClusterState, MigratedEntities> migratedEntitiesTuple = migrateToDataTiersRouting(
                state,
                null,
                null,
                REGISTRY,
                client,
                null,
                false
            );

            MigratedEntities migratedEntities = migratedEntitiesTuple.v2();
            assertThat(migratedEntities.migratedPolicies.size(), is(1));
            assertThat(migratedEntities.migratedPolicies.get(0), is(lifecycleName));
            assertThat(migratedEntities.migratedIndices.size(), is(2));
            assertThat(migratedEntities.migratedIndices, hasItems("indexWithWarmDataAttribute", "indexWithUnknownDataAttribute"));

            IndexMetadata migratedIndex;
            migratedIndex = migratedEntitiesTuple.v1().metadata().index("indexWithWarmDataAttribute");
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_warm,data_hot"));
            migratedIndex = migratedEntitiesTuple.v1().metadata().index("indexWithUnknownDataAttribute");
            assertThat(migratedIndex.getSettings().get(TIER_PREFERENCE), is("data_content"));
        }
    }

    public void testMigrateToDataTiersRoutingRequiresILMStopped() {
        {
            ClusterState ilmRunningState = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(
                    Metadata.builder().putCustom(IndexLifecycleMetadata.TYPE, new IndexLifecycleMetadata(Map.of(), OperationMode.RUNNING))
                )
                .build();
            IllegalStateException illegalStateException = expectThrows(
                IllegalStateException.class,
                () -> migrateToDataTiersRouting(ilmRunningState, "data", "catch-all", REGISTRY, client, null, false)
            );
            assertThat(illegalStateException.getMessage(), is("stop ILM before migrating to data tiers, current state is [RUNNING]"));
        }

        {
            ClusterState ilmStoppingState = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(
                    Metadata.builder().putCustom(IndexLifecycleMetadata.TYPE, new IndexLifecycleMetadata(Map.of(), OperationMode.STOPPING))
                )
                .build();
            IllegalStateException illegalStateException = expectThrows(
                IllegalStateException.class,
                () -> migrateToDataTiersRouting(ilmStoppingState, "data", "catch-all", REGISTRY, client, null, false)
            );
            assertThat(illegalStateException.getMessage(), is("stop ILM before migrating to data tiers, current state is [STOPPING]"));
        }

        {
            ClusterState ilmStoppedState = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(
                    Metadata.builder().putCustom(IndexLifecycleMetadata.TYPE, new IndexLifecycleMetadata(Map.of(), OperationMode.STOPPED))
                )
                .build();
            Tuple<ClusterState, MigratedEntities> migratedState = migrateToDataTiersRouting(
                ilmStoppedState,
                "data",
                "catch-all",
                REGISTRY,
                client,
                null,
                false
            );
            assertThat(migratedState.v2().migratedIndices, empty());
            assertThat(migratedState.v2().migratedPolicies, empty());
            assertThat(migratedState.v2().removedIndexTemplateName, nullValue());
        }
    }

    public void testDryRunDoesntRequireILMStopped() {
        {
            ClusterState ilmRunningState = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(
                    Metadata.builder().putCustom(IndexLifecycleMetadata.TYPE, new IndexLifecycleMetadata(Map.of(), OperationMode.RUNNING))
                )
                .build();
            migrateToDataTiersRouting(ilmRunningState, "data", "catch-all", REGISTRY, client, null, true);
            // no exceptions
        }

        {
            ClusterState ilmStoppingState = ClusterState.builder(ClusterName.DEFAULT)
                .metadata(
                    Metadata.builder().putCustom(IndexLifecycleMetadata.TYPE, new IndexLifecycleMetadata(Map.of(), OperationMode.STOPPING))
                )
                .build();
            migrateToDataTiersRouting(ilmStoppingState, "data", "catch-all", REGISTRY, client, null, true);
            // no exceptions
        }
    }

    public void testMigrationDoesNotRemoveComposableTemplates() {
        ComposableIndexTemplate composableIndexTemplate = new ComposableIndexTemplate.Builder().indexPatterns(
            Collections.singletonList("*")
        ).template(new Template(Settings.builder().put(DATA_ROUTING_REQUIRE_SETTING, "hot").build(), null, null)).build();

        String composableTemplateName = "catch-all-composable-template";
        ClusterState clusterState = ClusterState.builder(ClusterName.DEFAULT)
            .metadata(Metadata.builder().put(composableTemplateName, composableIndexTemplate).build())
            .build();
        Tuple<ClusterState, MigratedEntities> migratedEntitiesTuple = migrateToDataTiersRouting(
            clusterState,
            "data",
            composableTemplateName,
            REGISTRY,
            client,
            null,
            false
        );
        assertThat(migratedEntitiesTuple.v2().removedIndexTemplateName, nullValue());
        assertThat(migratedEntitiesTuple.v1().metadata().templatesV2().get(composableTemplateName), is(composableIndexTemplate));
    }

    public void testMigrationSetsEnforceTierPreferenceToTrue() {
        ClusterState clusterState;
        Tuple<ClusterState, MigratedEntities> migratedEntitiesTuple;
        Metadata.Builder metadata;

        // if the cluster state doesn't mention the setting, it ends up true
        clusterState = ClusterState.builder(ClusterName.DEFAULT).build();
        migratedEntitiesTuple = migrateToDataTiersRouting(clusterState, null, null, REGISTRY, client, null, false);
        assertTrue(DataTier.ENFORCE_DEFAULT_TIER_PREFERENCE_SETTING.get(migratedEntitiesTuple.v1().metadata().persistentSettings()));
        assertFalse(migratedEntitiesTuple.v1().metadata().transientSettings().keySet().contains(DataTier.ENFORCE_DEFAULT_TIER_PREFERENCE));

        // regardless of the true/false combinations of persistent/transient settings for ENFORCE_DEFAULT_TIER_PREFERENCE,
        // it ends up set to true as a persistent setting (and if there was a transient setting, it was removed)
        metadata = Metadata.builder();
        metadata.persistentSettings(Settings.builder().put(ENFORCE_DEFAULT_TIER_PREFERENCE, randomBoolean()).build());
        metadata.transientSettings(Settings.builder().put(ENFORCE_DEFAULT_TIER_PREFERENCE, randomBoolean()).build());
        clusterState = ClusterState.builder(ClusterName.DEFAULT).metadata(metadata).build();
        migratedEntitiesTuple = migrateToDataTiersRouting(clusterState, null, null, REGISTRY, client, null, false);
        assertTrue(DataTier.ENFORCE_DEFAULT_TIER_PREFERENCE_SETTING.get(migratedEntitiesTuple.v1().metadata().persistentSettings()));
        assertFalse(migratedEntitiesTuple.v1().metadata().transientSettings().keySet().contains(DataTier.ENFORCE_DEFAULT_TIER_PREFERENCE));
    }

    private LifecyclePolicyMetadata getWarmColdPolicyMeta(
        SetPriorityAction setPriorityAction,
        ShrinkAction shrinkAction,
        AllocateAction warmAllocateAction,
        AllocateAction coldAllocateAction
    ) {
        LifecyclePolicy policy = new LifecyclePolicy(
            lifecycleName,
            Map.of(
                "warm",
                new Phase(
                    "warm",
                    TimeValue.ZERO,
                    Map.of(
                        shrinkAction.getWriteableName(),
                        shrinkAction,
                        warmAllocateAction.getWriteableName(),
                        warmAllocateAction,
                        setPriorityAction.getWriteableName(),
                        setPriorityAction
                    )
                ),
                "cold",
                new Phase("cold", TimeValue.ZERO, Map.of(coldAllocateAction.getWriteableName(), coldAllocateAction))
            )
        );
        return new LifecyclePolicyMetadata(policy, Collections.emptyMap(), randomNonNegativeLong(), randomNonNegativeLong());
    }

    private String getWarmPhaseDef() {
        return """
            {
              "policy": "%s",
              "phase_definition": {
                "min_age": "0m",
                "actions": {
                  "allocate": {
                    "number_of_replicas": "0",
                    "require": {
                      "data": "cold"
                    }
                  },
                  "set_priority": {
                    "priority": 100
                  },
                  "shrink": {
                    "number_of_shards": 2
                  }
                }
              },
              "version": 1,
              "modified_date_in_millis": 1578521007076
            }""".formatted(lifecycleName);
    }

    private String getColdPhaseDefinitionWithTotalShardsPerNode() {
        return """
            {
              "policy": "%s",
              "phase_definition": {
                "min_age": "0m",
                "actions": {
                  "allocate": {
                    "total_shards_per_node": "1",
                    "require": {
                      "data": "cold"
                    }
                  }
                }
              },
              "version": 1,
              "modified_date_in_millis": 1578521007076
            }""".formatted(lifecycleName);
    }

    private String getColdPhaseDefinition() {
        return """
            {
              "policy": "%s",
              "phase_definition": {
                "min_age": "0m",
                "actions": {
                  "allocate": {
                    "number_of_replicas": "0",
                    "require": {
                      "data": "cold"
                    }
                  }
                }
              },
              "version": 1,
              "modified_date_in_millis": 1578521007076
            }""".formatted(lifecycleName);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPhaseDefinitionAsMap(LifecycleExecutionState newLifecycleState) {
        XContentType entityContentType = XContentType.fromMediaType("application/json");
        return (Map<String, Object>) XContentHelper.convertToMap(
            entityContentType.xContent(),
            new ByteArrayInputStream(newLifecycleState.getPhaseDefinition().getBytes(StandardCharsets.UTF_8)),
            false
        ).get("phase_definition");
    }

}
