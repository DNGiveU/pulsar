/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.admin.impl;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.broker.admin.AdminResource;
import org.apache.pulsar.broker.web.RestException;
import org.apache.pulsar.packages.management.core.PackagesManagement;
import org.apache.pulsar.packages.management.core.common.PackageMetadata;
import org.apache.pulsar.packages.management.core.common.PackageName;
import org.apache.pulsar.packages.management.core.common.PackageType;
import org.apache.pulsar.packages.management.core.exceptions.PackagesManagementException;


@Slf4j
public class PackagesBase extends AdminResource {
    private PackagesManagement getPackagesManagement() {
        return pulsar().getPackagesManagement();
    }

    private CompletableFuture<PackageName> getPackageNameAsync(String type, String tenant, String namespace,
                                                               String packageName, String version) {
        CompletableFuture<PackageName> future = new CompletableFuture<>();
        try {
            PackageName name = PackageName.get(type, tenant, namespace, packageName, version);
            future.complete(name);
        } catch (IllegalArgumentException illegalArgumentException) {
            future.completeExceptionally(illegalArgumentException);
        }
        return future;
    }

    private Void handleError(Throwable throwable, AsyncResponse asyncResponse) {
        if (throwable instanceof IllegalArgumentException) {
            asyncResponse.resume(new RestException(Response.Status.PRECONDITION_FAILED, throwable.getMessage()));
        } else if (throwable instanceof PackagesManagementException.NotFoundException) {
            asyncResponse.resume(new RestException(Response.Status.NOT_FOUND, throwable.getMessage()));
        } else {
            asyncResponse.resume(new RestException(Response.Status.INTERNAL_SERVER_ERROR, throwable.getMessage()));
        }
        return null;
    }

    protected void internalGetMetadata(String type, String tenant, String namespace, String packageName,
                                                  String version, AsyncResponse asyncResponse) {
        getPackageNameAsync(type, tenant, namespace, packageName, version)
            .thenCompose(name -> getPackagesManagement().getMeta(name))
            .thenAccept(asyncResponse::resume)
            .exceptionally(e -> handleError(e.getCause(), asyncResponse));
    }

    protected void internalUpdateMetadata(String type, String tenant, String namespace, String packageName,
                                          String version, PackageMetadata metadata, AsyncResponse asyncResponse) {
        getPackageNameAsync(type, tenant, namespace, packageName, version)
            .thenCompose(name -> getPackagesManagement().updateMeta(name, metadata))
            .thenAccept(ignore -> asyncResponse.resume(Response.noContent().build()))
            .exceptionally(e -> handleError(e.getCause(), asyncResponse));
    }

    protected StreamingOutput internalDownload(String type, String tenant, String namespace,
                                               String packageName, String version) {
        try {
            PackageName name = PackageName.get(type, tenant, namespace, packageName, version);
            return output -> {
                try {
                    getPackagesManagement().download(name, output).get();
                } catch (InterruptedException e) {
                    throw new RestException(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof PackagesManagementException.NotFoundException) {
                        throw new RestException(Response.Status.NOT_FOUND, e.getCause().getMessage());
                    } else {
                        throw new RestException(Response.Status.INTERNAL_SERVER_ERROR, e.getCause().getMessage());
                    }
                }
            };
        } catch (IllegalArgumentException illegalArgumentException) {
            throw new RestException(Response.Status.PRECONDITION_FAILED, illegalArgumentException.getMessage());
        }
    }

    protected void internalUpload(String type, String tenant, String namespace, String packageName, String version,
                                  PackageMetadata metadata, InputStream uploadedInputStream,
                                  AsyncResponse asyncResponse) {
        getPackageNameAsync(type, tenant, namespace, packageName, version)
            .thenCompose(name -> getPackagesManagement().upload(name, metadata, uploadedInputStream))
            .thenAccept(ignore -> asyncResponse.resume(Response.noContent().build()))
            .exceptionally(e -> handleError(e.getCause(), asyncResponse));
    }

    protected void internalDelete(String type, String tenant, String namespace, String packageName, String version,
                                  AsyncResponse asyncResponse) {
        getPackageNameAsync(type, tenant, namespace, packageName, version)
            .thenCompose(name -> getPackagesManagement().delete(name))
            .thenAccept(ignore -> asyncResponse.resume(Response.noContent().build()))
            .exceptionally(e -> handleError(e.getCause(), asyncResponse));
    }

    protected void internalListVersions(String type, String tenant, String namespace, String packageName,
                                                     AsyncResponse asyncResponse) {
        getPackageNameAsync(type, tenant, namespace, packageName, "")
            .thenCompose(name -> getPackagesManagement().list(name))
            .thenAccept(asyncResponse::resume)
            .exceptionally(e -> handleError(e.getCause(), asyncResponse));
    }

    protected void internalListPackages(String type, String tenant, String namespace, AsyncResponse asyncResponse) {
        try {
            PackageType packageType = PackageType.getEnum(type);
            getPackagesManagement().list(packageType, tenant, namespace)
                .thenAccept(asyncResponse::resume)
                .exceptionally(e -> handleError(e.getCause(), asyncResponse));
        } catch (IllegalArgumentException iae) {
            asyncResponse.resume(new RestException(Response.Status.PRECONDITION_FAILED, iae.getMessage()));
        }
    }
}
