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
using System.Globalization;
using System.IO;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Org.Apache.REEF.Client.Yarn.RestClient;
using Org.Apache.REEF.Tang.Exceptions;
using Org.Apache.REEF.Tang.Implementations.Tang;

namespace Org.Apache.REEF.Client.Tests
{
    [TestClass]
    public class YarnConfigurationUrlProviderTests
    {
        private const string HadoopConfDirEnvVariable = "HADOOP_CONF_DIR";
        private const string YarnConfigFileName = "yarn-site.xml";
        private const string AnyHttpAddressConfig = @"anyhost:8088";
        private const string AnyHttpAddressConfigUpdated = @"anyhost1:8088";
        private const string AnyHttpsAddressConfig = @"anyotherhost:9088";

        private const string YarnConfigurationXmlContent = @"<?xml version=""1.0""?>
<?xml-stylesheet type=""text/xsl"" href=""configuration.xsl""?>
<!-- Put site-specific property overrides in this file. -->
<configuration xmlns:xi=""http://www.w3.org/2001/XInclude"">
  <property>
    <name>yarn.resourcemanager.webapp.https.address</name>
    <value>" + AnyHttpsAddressConfig + @"</value>
  </property>
  <property>
    <name>yarn.resourcemanager.webapp.address</name>
    <value>" + AnyHttpAddressConfig + @"</value>
  </property>
  <property>
    <name>yarn.nodemanager.local-dirs</name>
    <value>C:\hdpdata\hadoop\local</value>
  </property>
</configuration>";

        private const string YarnConfigurationXmlContentChanged = @"<?xml version=""1.0""?>
<?xml-stylesheet type=""text/xsl"" href=""configuration.xsl""?>
<!-- Put site-specific property overrides in this file. -->
<configuration xmlns:xi=""http://www.w3.org/2001/XInclude"">
  <property>
    <name>yarn.resourcemanager.webapp.https.address</name>
    <value>" + AnyHttpsAddressConfig + @"</value>
  </property>
  <property>
    <name>yarn.resourcemanager.webapp.address</name>
    <value>" + AnyHttpAddressConfigUpdated + @"</value>
  </property>
  <property>
    <name>yarn.nodemanager.local-dirs</name>
    <value>C:\hdpdata\hadoop\local</value>
  </property>
</configuration>";

        [TestMethod]
        public void UrlProviderReadsEnvVarConfiguredConfigFileAndParsesCorrectHttpUrl()
        {
            string tempFile = Path.GetTempFileName();
            string tempDir = Path.GetDirectoryName(tempFile);
            string yarnConfigFile = Path.Combine(tempDir, YarnConfigFileName);

            using (new TempFileWriter(yarnConfigFile, YarnConfigurationXmlContent))
            using (new TemporaryOverrideEnvironmentVariable(HadoopConfDirEnvVariable, tempDir))
            {
                YarnConfigurationUrlProvider urlProvider = GetYarnConfigurationUrlProvider();
                var url = urlProvider.GetUrlAsync().GetAwaiter().GetResult();

                Assert.AreEqual("http", url.Scheme);
                Assert.AreEqual(AnyHttpAddressConfig.Split(':')[0], url.Host);
                Assert.AreEqual(AnyHttpAddressConfig.Split(':')[1], url.Port.ToString(CultureInfo.InvariantCulture));
            }
        }

        [TestMethod]
        public void UrlProviderReadsEnvVarConfiguredConfigFileAndParsesCorrectHttpsUrl()
        {
            string tempFile = Path.GetTempFileName();
            string tempDir = Path.GetDirectoryName(tempFile);
            string yarnConfigFile = Path.Combine(tempDir, YarnConfigFileName);

            using (new TempFileWriter(yarnConfigFile, YarnConfigurationXmlContent))
            using (new TemporaryOverrideEnvironmentVariable(HadoopConfDirEnvVariable, tempDir))
            {
                YarnConfigurationUrlProvider urlProvider = GetYarnConfigurationUrlProvider(useHttps: true);
                var url = urlProvider.GetUrlAsync().GetAwaiter().GetResult();

                Assert.AreEqual("https", url.Scheme);
                Assert.AreEqual(AnyHttpsAddressConfig.Split(':')[0], url.Host);
                Assert.AreEqual(AnyHttpsAddressConfig.Split(':')[1], url.Port.ToString(CultureInfo.InvariantCulture));
            }
        }

        [TestMethod]
        public void UrlProviderReadsUserProvidedConfigFileAndParsesCorrectHttpsUrl()
        {
            string tempFile = Path.GetTempFileName();
            string tempDir = Path.GetDirectoryName(tempFile);
            string yarnConfigFile = Path.Combine(tempDir, YarnConfigFileName);

            using (new TempFileWriter(yarnConfigFile, YarnConfigurationXmlContent))
            {
                YarnConfigurationUrlProvider urlProvider = GetYarnConfigurationUrlProvider(
                    anyHadoopConfigDir: tempDir,
                    useHttps: true);
                var url = urlProvider.GetUrlAsync().GetAwaiter().GetResult();

                Assert.AreEqual("https", url.Scheme);
                Assert.AreEqual(AnyHttpsAddressConfig.Split(':')[0], url.Host);
                Assert.AreEqual(AnyHttpsAddressConfig.Split(':')[1], url.Port.ToString(CultureInfo.InvariantCulture));
            }
        }

        [TestMethod]
        public void UrlProviderReadsUpdatedConfigFile()
        {
            string tempFile = Path.GetTempFileName();
            string tempDir = Path.GetDirectoryName(tempFile);
            string yarnConfigFile = Path.Combine(tempDir, YarnConfigFileName);

            using (new TempFileWriter(yarnConfigFile, YarnConfigurationXmlContent))
            {
                YarnConfigurationUrlProvider urlProvider = GetYarnConfigurationUrlProvider(
                    anyHadoopConfigDir: tempDir);
                var url = urlProvider.GetUrlAsync().GetAwaiter().GetResult();

                Assert.AreEqual("http", url.Scheme);
                Assert.AreEqual(AnyHttpAddressConfig.Split(':')[0], url.Host);
                Assert.AreEqual(AnyHttpAddressConfig.Split(':')[1], url.Port.ToString(CultureInfo.InvariantCulture));

                File.WriteAllText(yarnConfigFile, YarnConfigurationXmlContentChanged);

                // Wait for file to be reloaded or timeout
                Assert.IsTrue(urlProvider.TestHook_ConfigReloaded.Wait(TimeSpan.FromSeconds(10)), "Config reload failed");

                url = urlProvider.GetUrlAsync().GetAwaiter().GetResult();
                // assert updated changes loaded
                Assert.AreEqual("http", url.Scheme);
                Assert.AreEqual(AnyHttpAddressConfigUpdated.Split(':')[0], url.Host);
                Assert.AreEqual(AnyHttpAddressConfigUpdated.Split(':')[1], url.Port.ToString(CultureInfo.InvariantCulture));
            }
        }

        [TestMethod]
        public void CannotFindHadoopConfigDirThrowsArgumentException()
        {
            using (new TemporaryOverrideEnvironmentVariable(HadoopConfDirEnvVariable, string.Empty))
            {
                try
                {
                    YarnConfigurationUrlProvider urlProviderNotUsed = GetYarnConfigurationUrlProvider();
                    Assert.Fail("Should throw exception");
                }
                catch (InjectionException injectionException)
                {
                    Assert.IsTrue(injectionException.GetBaseException() is ArgumentException);
                }
            }
        }

        private YarnConfigurationUrlProvider GetYarnConfigurationUrlProvider(
            string anyHadoopConfigDir = null,
            bool useHttps = false)
        {
            var injector = TangFactory.GetTang().NewInjector();
            if (anyHadoopConfigDir != null)
            {
                injector.BindVolatileParameter<YarnConfigurationUrlProvider.HadoopConfigurationDirectory, string>(
                    anyHadoopConfigDir);
            }

            injector.BindVolatileParameter<YarnConfigurationUrlProvider.UseHttpsForYarnCommunication, bool>(useHttps);
            return injector.GetInstance<YarnConfigurationUrlProvider>();
        }

        /// <summary>
        /// Convenient syntax support for confirmed revert of environment variable change
        /// even in the case of failure.
        /// Other tests in the assembly will see changed env var
        /// </summary>
        private class TemporaryOverrideEnvironmentVariable : IDisposable
        {
            private readonly string _variableName;
            private readonly string _oldValue;

            public TemporaryOverrideEnvironmentVariable(string variableName, string newValue)
            {
                _variableName = variableName;
                _oldValue = Environment.GetEnvironmentVariable(variableName);
                Environment.SetEnvironmentVariable(variableName, newValue);
            }

            public void Dispose()
            {
                Environment.SetEnvironmentVariable(_variableName, _oldValue);
            }
        }

        /// <summary>
        /// Convenient syntax to make sure we clean up the temporary file
        /// even in the case of failures.
        /// It is a shame we are writing to disk in unit test
        /// </summary>
        private class TempFileWriter : IDisposable
        {
            private readonly string _filePath;

            public TempFileWriter(string filePath, string fileContent)
            {
                _filePath = filePath;
                File.WriteAllText(filePath, fileContent);
            }

            public void Dispose()
            {
                File.Delete(_filePath);
            }
        }
    }
}