﻿// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

using System;
using System.Threading;
using System.Threading.Tasks;
using Org.Apache.REEF.Client.YARN.RestClient.DataModel;
using Org.Apache.REEF.Tang.Annotations;

namespace Org.Apache.REEF.Client.Yarn.RestClient
{
    [DefaultImplementation(typeof(YarnClient))]
    internal interface IYarnRMClient
    {
        Task<ClusterInfo> GetClusterInfoAsync();

        Task<ClusterInfo> GetClusterInfoAsync(CancellationToken cancellationToken);

        Task<ClusterMetrics> GetClusterMetricsAsync();

        Task<ClusterMetrics> GetClusterMetricsAsync(CancellationToken cancellationToken);

        Task<Application> GetApplicationAsync(string appId);

        Task<Application> GetApplicationAsync(string appId, CancellationToken cancellationToken);

        Task<NewApplication> CreateNewApplicationAsync();

        Task<NewApplication> CreateNewApplicationAsync(CancellationToken cancellationToken);
    }

    [DefaultImplementation(typeof(YarnConfigurationUrlProvider))]
    internal interface IUrlProvider
    {
        Task<Uri> GetUrlAsync();
    }
}