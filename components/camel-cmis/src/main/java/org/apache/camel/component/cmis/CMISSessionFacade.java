/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.cmis;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.DocumentType;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.runtime.ObjectIdImpl;
import org.apache.chemistry.opencmis.client.runtime.OperationContextImpl;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CMISSessionFacade {
    private static final transient Logger LOG = LoggerFactory.getLogger(CMISSessionFacade.class);
    private final String url;
    private int pageSize = 100;
    private int readCount;
    private boolean readContent;
    private String username;
    private String password;
    private String repositoryId;
    private String query;
    private Session session;

    public CMISSessionFacade(String url) {
        this.url = url;
    }

    void initSession() {
        Map<String, String> parameter = new HashMap<String, String>();
        parameter.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
        parameter.put(SessionParameter.ATOMPUB_URL, this.url);
        parameter.put(SessionParameter.USER, this.username);
        parameter.put(SessionParameter.PASSWORD, this.password);
        if (this.repositoryId != null) {
            parameter.put(SessionParameter.REPOSITORY_ID, this.repositoryId);
            this.session = SessionFactoryImpl.newInstance().createSession(parameter);
        } else {
            this.session = SessionFactoryImpl.newInstance().getRepositories(parameter).get(0).createSession();
        }
    }

    public int poll(CMISConsumer cmisConsumer) throws Exception {
        if (query != null) {
            return pollWithQuery(cmisConsumer);
        }
        return pollTree(cmisConsumer);
    }

    private int pollTree(CMISConsumer cmisConsumer) throws Exception {
        Folder rootFolder = session.getRootFolder();
        RecursiveTreeWalker treeWalker = new RecursiveTreeWalker(cmisConsumer, readContent, readCount,
                pageSize);
        return treeWalker.processFolderRecursively(rootFolder);
    }

    private int pollWithQuery(CMISConsumer cmisConsumer) throws Exception {
        int count = 0;
        int pageNumber = 0;
        boolean finished = false;
        ItemIterable<QueryResult> itemIterable = executeQuery(query);
        while (!finished) {
            ItemIterable<QueryResult> currentPage = itemIterable.skipTo(count).getPage();
            LOG.debug("Processing page {}", pageNumber);
            for (QueryResult item : currentPage) {
                Map<String, Object> properties = CMISHelper.propertyDataToMap(item.getProperties());
                Object objectTypeId = item.getPropertyValueById(PropertyIds.OBJECT_TYPE_ID);
                InputStream inputStream = null;
                if (readContent && CamelCMISConstants.CMIS_DOCUMENT.equals(objectTypeId)) {
                    inputStream = getContentStreamFor(item);
                }

                cmisConsumer.sendExchangeWithPropsAndBody(properties, inputStream);
                count++;
                if (count == readCount) {
                    finished = true;
                    break;
                }
            }
            pageNumber++;
            if (!currentPage.getHasMoreItems()) {
                finished = true;
            }
        }
        return count;
    }

    //some duplication
    public List<Map<String, Object>> retrieveResult(boolean retrieveContent, int readSize,
                                                    ItemIterable<QueryResult> itemIterable) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        int count = 0;
        int pageNumber = 0;
        boolean finished = false;
        while (!finished) {
            ItemIterable<QueryResult> currentPage = itemIterable.skipTo(count).getPage();
            LOG.debug("Processing page {}", pageNumber);
            for (QueryResult item : currentPage) {
                Map<String, Object> properties = CMISHelper.propertyDataToMap(item.getProperties());
                if (retrieveContent) {
                    InputStream inputStream = getContentStreamFor(item);
                    properties.put(CamelCMISConstants.CAMEL_CMIS_CONTENT_STREAM, inputStream);
                }

                result.add(properties);
                count++;
                if (count == readSize) {
                    finished = true;
                    break;
                }
            }
            pageNumber++;
            if (!currentPage.getHasMoreItems()) {
                finished = true;
            }
        }
        return result;
    }

    public ItemIterable<QueryResult> executeQuery(String query) {
        OperationContext operationContext = new OperationContextImpl();
        operationContext.setMaxItemsPerPage(pageSize);
        return session.query(query, false, operationContext);
    }

    public Document getDocument(QueryResult queryResult) {
        if (CamelCMISConstants.CMIS_DOCUMENT
                .equals(queryResult.getPropertyValueById(PropertyIds.OBJECT_TYPE_ID))) {
            String objectId = (String)queryResult.getPropertyById(PropertyIds.OBJECT_ID).getFirstValue();
            ObjectIdImpl objectIdImpl = new ObjectIdImpl(objectId);
            return (org.apache.chemistry.opencmis.client.api.Document)session.getObject(objectIdImpl);
        }
        return null;
    }

    public InputStream getContentStreamFor(QueryResult item) {
        Document document = getDocument(item);
        if (document != null && document.getContentStream() != null) {
            return document.getContentStream().getStream();
        }
        return null;
    }

    public CmisObject getObjectByPath(String path) {
        return session.getObjectByPath(path);
    }

    public boolean isObjectTypeVersionable(String objectType) {
        return ((DocumentType)session.getTypeDefinition(objectType)).isVersionable();
    }

    public ContentStream createContentStream(String fileName, byte[] buf, String mimeType) throws Exception {
        return buf != null ? session.getObjectFactory()
                .createContentStream(fileName, buf.length, mimeType, new ByteArrayInputStream(buf)) : null;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    public void setReadContent(boolean readContent) {
        this.readContent = readContent;
    }

    public void setReadCount(int readCount) {
        this.readCount = readCount;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}
