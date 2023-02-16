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

package org.apache.shardingsphere.proxy.backend.handler.distsql.ral;

import org.apache.shardingsphere.distsql.handler.ral.update.GlobalRuleRALUpdater;
import org.apache.shardingsphere.distsql.parser.statement.ral.RALStatement;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.proxy.backend.handler.distsql.DistSQLBackendHandler;
import org.apache.shardingsphere.proxy.backend.response.header.ResponseHeader;
import org.apache.shardingsphere.proxy.backend.response.header.update.UpdateResponseHeader;

/**
 * Updatable RAL backend handler for global rule.
 */
public final class UpdatableGlobalRuleRALBackendHandler implements DistSQLBackendHandler {
    
    private final RALStatement sqlStatement;
    
    private final GlobalRuleRALUpdater updater;
    
    public UpdatableGlobalRuleRALBackendHandler(final RALStatement sqlStatement, final GlobalRuleRALUpdater updater) {
        this.sqlStatement = sqlStatement;
        this.updater = updater;
    }
    
    @Override
    public ResponseHeader execute() {
        updater.executeUpdate(ProxyContext.getInstance().getContextManager().getMetaDataContexts().getMetaData(), sqlStatement);
        ContextManager contextManager = ProxyContext.getInstance().getContextManager();
        contextManager.getInstanceContext().getModeContextManager().alterGlobalRuleConfiguration(contextManager.getMetaDataContexts().getMetaData().getGlobalRuleMetaData().getConfigurations());
        return new UpdateResponseHeader(sqlStatement);
    }
}
