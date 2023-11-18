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

package org.apache.shardingsphere.data.pipeline.core.job.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.data.pipeline.common.config.job.PipelineJobConfiguration;
import org.apache.shardingsphere.data.pipeline.common.config.process.PipelineProcessConfiguration;
import org.apache.shardingsphere.data.pipeline.common.config.process.PipelineProcessConfigurationUtils;
import org.apache.shardingsphere.data.pipeline.common.context.PipelineContextKey;
import org.apache.shardingsphere.data.pipeline.common.job.JobStatus;
import org.apache.shardingsphere.data.pipeline.common.job.progress.InventoryIncrementalJobItemProgress;
import org.apache.shardingsphere.data.pipeline.common.job.progress.JobOffsetInfo;
import org.apache.shardingsphere.data.pipeline.common.job.progress.yaml.YamlJobOffsetInfo;
import org.apache.shardingsphere.data.pipeline.common.job.progress.yaml.YamlJobOffsetInfoSwapper;
import org.apache.shardingsphere.data.pipeline.common.pojo.DataConsistencyCheckAlgorithmInfo;
import org.apache.shardingsphere.data.pipeline.common.pojo.InventoryIncrementalJobItemInfo;
import org.apache.shardingsphere.data.pipeline.common.pojo.TableBasedPipelineJobInfo;
import org.apache.shardingsphere.data.pipeline.core.consistencycheck.result.TableDataConsistencyCheckResult;
import org.apache.shardingsphere.data.pipeline.core.consistencycheck.table.TableDataConsistencyChecker;
import org.apache.shardingsphere.data.pipeline.core.job.PipelineJobIdUtils;
import org.apache.shardingsphere.data.pipeline.core.job.service.InventoryIncrementalJobAPI;
import org.apache.shardingsphere.data.pipeline.core.job.service.PipelineAPIFactory;
import org.apache.shardingsphere.data.pipeline.core.job.service.PipelineJobItemManager;
import org.apache.shardingsphere.data.pipeline.core.job.service.PipelineJobManager;
import org.apache.shardingsphere.data.pipeline.core.metadata.PipelineProcessConfigurationPersistService;
import org.apache.shardingsphere.elasticjob.infra.pojo.JobConfigurationPOJO;
import org.apache.shardingsphere.infra.database.core.type.DatabaseType;
import org.apache.shardingsphere.infra.spi.ShardingSphereServiceLoader;
import org.apache.shardingsphere.infra.spi.annotation.SPIDescription;
import org.apache.shardingsphere.infra.util.yaml.YamlEngine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Abstract inventory incremental job API implementation.
 */
@Slf4j
public abstract class AbstractInventoryIncrementalJobAPIImpl implements InventoryIncrementalJobAPI {
    
    private final PipelineProcessConfigurationPersistService processConfigPersistService = new PipelineProcessConfigurationPersistService();
    
    private final YamlJobOffsetInfoSwapper jobOffsetInfoSwapper = new YamlJobOffsetInfoSwapper();
    
    @Override
    public void alterProcessConfiguration(final PipelineContextKey contextKey, final PipelineProcessConfiguration processConfig) {
        // TODO check rateLimiter type match or not
        processConfigPersistService.persist(contextKey, getType(), processConfig);
    }
    
    @Override
    public PipelineProcessConfiguration showProcessConfiguration(final PipelineContextKey contextKey) {
        return PipelineProcessConfigurationUtils.convertWithDefaultValue(processConfigPersistService.load(contextKey, getType()));
    }
    
    @Override
    public Map<Integer, InventoryIncrementalJobItemProgress> getJobProgress(final PipelineJobConfiguration jobConfig) {
        PipelineJobItemManager<InventoryIncrementalJobItemProgress> jobItemManager = new PipelineJobItemManager<>(getYamlJobItemProgressSwapper());
        String jobId = jobConfig.getJobId();
        JobConfigurationPOJO jobConfigPOJO = PipelineJobIdUtils.getElasticJobConfigurationPOJO(jobId);
        return IntStream.range(0, jobConfig.getJobShardingCount()).boxed().collect(LinkedHashMap::new, (map, each) -> {
            Optional<InventoryIncrementalJobItemProgress> jobItemProgress = jobItemManager.getProgress(jobId, each);
            jobItemProgress.ifPresent(optional -> optional.setActive(!jobConfigPOJO.isDisabled()));
            map.put(each, jobItemProgress.orElse(null));
        }, LinkedHashMap::putAll);
    }
    
    @Override
    public List<InventoryIncrementalJobItemInfo> getJobItemInfos(final String jobId) {
        PipelineJobManager jobManager = new PipelineJobManager(this);
        PipelineJobItemManager<InventoryIncrementalJobItemProgress> jobItemManager = new PipelineJobItemManager<>(getYamlJobItemProgressSwapper());
        JobConfigurationPOJO jobConfigPOJO = PipelineJobIdUtils.getElasticJobConfigurationPOJO(jobId);
        PipelineJobConfiguration jobConfig = jobManager.getJobConfiguration(jobConfigPOJO);
        long startTimeMillis = Long.parseLong(Optional.ofNullable(jobConfigPOJO.getProps().getProperty("start_time_millis")).orElse("0"));
        Map<Integer, InventoryIncrementalJobItemProgress> jobProgress = getJobProgress(jobConfig);
        List<InventoryIncrementalJobItemInfo> result = new LinkedList<>();
        for (Entry<Integer, InventoryIncrementalJobItemProgress> entry : jobProgress.entrySet()) {
            int shardingItem = entry.getKey();
            TableBasedPipelineJobInfo jobInfo = (TableBasedPipelineJobInfo) getJobInfo(jobId);
            InventoryIncrementalJobItemProgress jobItemProgress = entry.getValue();
            String errorMessage = jobItemManager.getErrorMessage(jobId, shardingItem);
            if (null == jobItemProgress) {
                result.add(new InventoryIncrementalJobItemInfo(shardingItem, jobInfo.getTable(), null, startTimeMillis, 0, errorMessage));
                continue;
            }
            int inventoryFinishedPercentage = 0;
            if (JobStatus.EXECUTE_INCREMENTAL_TASK == jobItemProgress.getStatus() || JobStatus.FINISHED == jobItemProgress.getStatus()) {
                inventoryFinishedPercentage = 100;
            } else if (0 != jobItemProgress.getProcessedRecordsCount() && 0 != jobItemProgress.getInventoryRecordsCount()) {
                inventoryFinishedPercentage = (int) Math.min(100, jobItemProgress.getProcessedRecordsCount() * 100 / jobItemProgress.getInventoryRecordsCount());
            }
            result.add(new InventoryIncrementalJobItemInfo(shardingItem, jobInfo.getTable(), jobItemProgress, startTimeMillis, inventoryFinishedPercentage, errorMessage));
        }
        return result;
    }
    
    @Override
    public void persistJobOffsetInfo(final String jobId, final JobOffsetInfo jobOffsetInfo) {
        String value = YamlEngine.marshal(jobOffsetInfoSwapper.swapToYamlConfiguration(jobOffsetInfo));
        PipelineAPIFactory.getGovernanceRepositoryAPI(PipelineJobIdUtils.parseContextKey(jobId)).persistJobOffsetInfo(jobId, value);
    }
    
    @Override
    public JobOffsetInfo getJobOffsetInfo(final String jobId) {
        Optional<String> offsetInfo = PipelineAPIFactory.getGovernanceRepositoryAPI(PipelineJobIdUtils.parseContextKey(jobId)).getJobOffsetInfo(jobId);
        if (offsetInfo.isPresent()) {
            YamlJobOffsetInfo info = YamlEngine.unmarshal(offsetInfo.get(), YamlJobOffsetInfo.class);
            return jobOffsetInfoSwapper.swapToObject(info);
        }
        return jobOffsetInfoSwapper.swapToObject(new YamlJobOffsetInfo());
    }
    
    @Override
    public Collection<DataConsistencyCheckAlgorithmInfo> listDataConsistencyCheckAlgorithms() {
        Collection<DataConsistencyCheckAlgorithmInfo> result = new LinkedList<>();
        for (TableDataConsistencyChecker each : ShardingSphereServiceLoader.getServiceInstances(TableDataConsistencyChecker.class)) {
            SPIDescription description = each.getClass().getAnnotation(SPIDescription.class);
            String typeAliases = each.getTypeAliases().stream().map(Object::toString).collect(Collectors.joining(","));
            result.add(new DataConsistencyCheckAlgorithmInfo(each.getType(), typeAliases, getSupportedDatabaseTypes(each.getSupportedDatabaseTypes()), null == description ? "" : description.value()));
        }
        return result;
    }
    
    private Collection<DatabaseType> getSupportedDatabaseTypes(final Collection<DatabaseType> supportedDatabaseTypes) {
        return supportedDatabaseTypes.isEmpty() ? ShardingSphereServiceLoader.getServiceInstances(DatabaseType.class) : supportedDatabaseTypes;
    }
    
    @Override
    public boolean aggregateDataConsistencyCheckResults(final String jobId, final Map<String, TableDataConsistencyCheckResult> checkResults) {
        if (checkResults.isEmpty()) {
            throw new IllegalArgumentException("checkResults empty, jobId:" + jobId);
        }
        for (Entry<String, TableDataConsistencyCheckResult> entry : checkResults.entrySet()) {
            TableDataConsistencyCheckResult checkResult = entry.getValue();
            if (!checkResult.isMatched()) {
                return false;
            }
        }
        return true;
    }
}
