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

package org.apache.shardingsphere.mode.manager.cluster.coordinator.subscriber;

import com.google.common.eventbus.Subscribe;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;
import org.apache.shardingsphere.infra.metadata.database.schema.QualifiedDatabase;
import org.apache.shardingsphere.infra.rule.attribute.datasource.StaticDataSourceRuleAttribute;
import org.apache.shardingsphere.infra.state.datasource.DataSourceState;
import org.apache.shardingsphere.mode.event.storage.StorageNodeDataSource;
import org.apache.shardingsphere.mode.event.storage.StorageNodeDataSourceChangedEvent;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.RegistryCenter;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.config.event.datasource.DataSourceUnitsChangedEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.config.event.props.PropertiesChangedEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.config.event.rule.GlobalRuleConfigurationsChangedEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.config.event.rule.RuleConfigurationsChangedEvent;

import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * Configuration changed subscriber.
 */
public final class ConfigurationChangedSubscriber {
    
    private final RegistryCenter registryCenter;
    
    private final ContextManager contextManager;
    
    public ConfigurationChangedSubscriber(final RegistryCenter registryCenter, final ContextManager contextManager) {
        this.registryCenter = registryCenter;
        this.contextManager = contextManager;
        contextManager.getInstanceContext().getEventBusContext().register(this);
        disableDataSources();
    }
    
    /**
     * Renew data source units configuration.
     *
     * @param event data source changed event.
     */
    @Subscribe
    public synchronized void renew(final DataSourceUnitsChangedEvent event) {
        contextManager.getConfigurationContextManager().alterDataSourceUnitsConfiguration(event.getDatabaseName(), event.getDataSourcePoolPropertiesMap());
        disableDataSources();
    }
    
    /**
     * Renew rule configurations.
     *
     * @param event rule configurations changed event
     */
    @Subscribe
    public synchronized void renew(final RuleConfigurationsChangedEvent event) {
        contextManager.getConfigurationContextManager().alterRuleConfiguration(event.getDatabaseName(), event.getRuleConfigs());
        disableDataSources();
    }
    
    /**
     * Renew global rule configurations.
     *
     * @param event global rule configurations changed event
     */
    @Subscribe
    public synchronized void renew(final GlobalRuleConfigurationsChangedEvent event) {
        contextManager.getConfigurationContextManager().alterGlobalRuleConfiguration(event.getRuleConfigs());
        disableDataSources();
    }
    
    /**
     * Renew properties.
     *
     * @param event properties changed event
     */
    @Subscribe
    public synchronized void renew(final PropertiesChangedEvent event) {
        contextManager.getConfigurationContextManager().alterProperties(event.getProps());
    }
    
    private void disableDataSources() {
        Map<String, StorageNodeDataSource> storageNodes = getDisabledDataSources();
        for (Entry<String, ShardingSphereDatabase> entry : contextManager.getMetaDataContexts().getMetaData().getDatabases().entrySet()) {
            entry.getValue().getRuleMetaData().getAttributes(StaticDataSourceRuleAttribute.class).forEach(each -> disableDataSources(entry.getKey(), each, storageNodes));
        }
    }
    
    private void disableDataSources(final String databaseName, final StaticDataSourceRuleAttribute ruleAttribute, final Map<String, StorageNodeDataSource> storageNodes) {
        for (Entry<String, StorageNodeDataSource> entry : storageNodes.entrySet()) {
            QualifiedDatabase database = new QualifiedDatabase(entry.getKey());
            if (!database.getDatabaseName().equals(databaseName)) {
                continue;
            }
            disableDataSources(entry.getValue(), ruleAttribute, database);
        }
    }
    
    private void disableDataSources(final StorageNodeDataSource storageNodeDataSource, final StaticDataSourceRuleAttribute ruleAttribute, final QualifiedDatabase database) {
        for (Entry<String, Collection<String>> entry : ruleAttribute.getDataSourceMapper().entrySet()) {
            if (database.getGroupName().equals(entry.getKey())) {
                entry.getValue().forEach(each -> ruleAttribute.updateStatus(new StorageNodeDataSourceChangedEvent(database, storageNodeDataSource)));
            }
        }
    }
    
    private Map<String, StorageNodeDataSource> getDisabledDataSources() {
        return registryCenter.getStorageNodeStatusService().loadStorageNodes().entrySet()
                .stream().filter(entry -> DataSourceState.DISABLED == entry.getValue().getStatus()).collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    }
}
